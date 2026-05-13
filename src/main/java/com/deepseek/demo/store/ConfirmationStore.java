package com.deepseek.demo.store;

import com.deepseek.demo.dto.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 确认点存储器，按 confirmationId 存储用户确认点状态。
 * <p>
 * 使用 ConcurrentHashMap 作为线程安全的存储后端，支持 plan 和 exec 两种确认点类型。
 * plan 类型存储 LLM 提议的完整操作计划，exec 类型存储单个写工具调用及其参数。
 * 附带定时清理过期确认点的机制，防止内存泄漏。
 */
public class ConfirmationStore {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationStore.class);

    /** 确认点过期时间：5 分钟无操作即视为过期 */
    private static final long EXPIRATION_MINUTES = 5;

    /** 清理任务执行间隔：1 分钟 */
    private static final long CLEANUP_INTERVAL_MINUTES = 1;

    /** 线程安全的确认点存储映射 */
    private final Map<String, ConfirmationState> confirmations = new ConcurrentHashMap<>();

    /** 定时清理过期确认点的调度器 */
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "confirmation-store-cleaner");
        t.setDaemon(true);
        return t;
    });

    /**
     * 确认点状态，存储单个确认点的完整上下文信息。
     * <p>
     * 使用 public static 修饰，以便 AgentService 等外部类直接访问和判读状态。
     */
    public static class ConfirmationState {
        /** 关联的对话会话 ID */
        private String conversationId;
        /** 确认点类型：plan（计划确认）或 exec（写操作二次确认） */
        private String type;
        /** plan 类型：LLM 提议的完整操作列表 */
        private List<Map<String, Object>> planToolCalls;
        /** exec 类型：要执行的工具名称 */
        private String toolName;
        /** exec 类型：原始 tool_call 的 ID */
        private String toolCallId;
        /**
         * exec 类型：工具参数的 JSON 字符串。
         * 跨确认边界保留原始 JSON 参数，避免反序列化/再序列化造成精度丢失。
         */
        private String toolArguments;
        /** exec 类型：待执行的 tool_call 列表（包含当前调用及其后续调用） */
        private List<ToolCall> pendingToolCalls;
        /** 创建时间戳（毫秒） */
        private long createdAt;
        /** 是否已被消费（确认或拒绝后标记） */
        private boolean consumed;

        public ConfirmationState() {
            this.createdAt = System.currentTimeMillis();
        }

        public String getConversationId() { return conversationId; }
        public void setConversationId(String conversationId) { this.conversationId = conversationId; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public List<Map<String, Object>> getPlanToolCalls() { return planToolCalls; }
        public void setPlanToolCalls(List<Map<String, Object>> planToolCalls) { this.planToolCalls = planToolCalls; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public String getToolCallId() { return toolCallId; }
        public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
        public String getToolArguments() { return toolArguments; }
        public void setToolArguments(String toolArguments) { this.toolArguments = toolArguments; }
        public List<ToolCall> getPendingToolCalls() { return pendingToolCalls; }
        public void setPendingToolCalls(List<ToolCall> pendingToolCalls) { this.pendingToolCalls = pendingToolCalls; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
        public boolean isConsumed() { return consumed; }
        public void setConsumed(boolean consumed) { this.consumed = consumed; }
    }

    /**
     * 构造器，启动定时清理任务。
     * 每隔 CLEANUP_INTERVAL_MINUTES 分钟清理一次过期确认点。
     */
    public ConfirmationStore() {
        cleaner.scheduleWithFixedDelay(this::cleanupExpired,
                CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
        log.info("确认点存储定时清理任务已启动，过期时间={}分钟，清理间隔={}分钟",
                EXPIRATION_MINUTES, CLEANUP_INTERVAL_MINUTES);
    }

    /**
     * 创建 plan 类型确认点。
     *
     * @param conversationId 关联的对话会话 ID
     * @param plan           LLM 提议的完整操作列表
     * @return 确认点唯一标识（UUID 字符串）
     */
    public String createPlanConfirmation(String conversationId, List<Map<String, Object>> plan) {
        ConfirmationState state = new ConfirmationState();
        state.setConversationId(conversationId);
        state.setType("plan");
        state.setPlanToolCalls(plan);

        String confirmationId = UUID.randomUUID().toString();
        confirmations.put(confirmationId, state);
        log.info("已创建 plan 确认点：confirmationId={}, conversationId={}, 操作数={}",
                confirmationId, conversationId, plan != null ? plan.size() : 0);
        return confirmationId;
    }

    /**
     * 创建 exec 类型确认点。
     *
     * @param conversationId 关联的对话会话 ID
     * @param toolCall       待确认的单个工具调用
     * @param pendingToolCalls 待执行的 tool_call 列表（包含当前调用）
     * @return 确认点唯一标识（UUID 字符串）
     */
    public String createExecConfirmation(String conversationId, ToolCall toolCall,
                                          List<ToolCall> pendingToolCalls) {
        ConfirmationState state = new ConfirmationState();
        state.setConversationId(conversationId);
        state.setType("exec");
        if (toolCall != null) {
            state.setToolName(toolCall.getFunction() != null ? toolCall.getFunction().getName() : null);
            state.setToolCallId(toolCall.getId());
            state.setToolArguments(toolCall.getFunction() != null ? toolCall.getFunction().getArguments() : null);
        }
        state.setPendingToolCalls(pendingToolCalls);

        String confirmationId = UUID.randomUUID().toString();
        confirmations.put(confirmationId, state);
        log.info("已创建 exec 确认点：confirmationId={}, conversationId={}, toolName={}",
                confirmationId, conversationId, state.toolName);
        return confirmationId;
    }

    /**
     * 获取指定确认点的状态。
     *
     * @param confirmationId 确认点 ID
     * @return ConfirmationState 对象，已过期或不存在则返回 null
     */
    public ConfirmationState get(String confirmationId) {
        ConfirmationState state = confirmations.get(confirmationId);
        if (state == null) {
            return null;
        }
        // 检查是否已过期（兜底检查，防止清理任务未及时执行）
        if (System.currentTimeMillis() - state.getCreatedAt() > TimeUnit.MINUTES.toMillis(EXPIRATION_MINUTES)) {
            confirmations.remove(confirmationId);
            log.debug("确认点 {} 已过期，自动移除", confirmationId);
            return null;
        }
        return state;
    }

    /**
     * 消费指定确认点，将其标记为已消费。
     * <p>
     * 确认或拒绝操作后调用此方法，防止同一确认点被重复处理。
     *
     * @param confirmationId 确认点 ID
     */
    public void consume(String confirmationId) {
        ConfirmationState state = confirmations.get(confirmationId);
        if (state != null) {
            state.setConsumed(true);
            log.debug("确认点 {} 已标记为已消费", confirmationId);
        }
    }

    /**
     * 检查指定确认点是否已被消费。
     *
     * @param confirmationId 确认点 ID
     * @return 已消费返回 true，不存在或未消费返回 false
     */
    public boolean isConsumed(String confirmationId) {
        ConfirmationState state = confirmations.get(confirmationId);
        return state != null && state.consumed;
    }

    // ==================== 内部方法 ====================

    /**
     * 清理过期确认点：遍历所有确认点，移除创建时间超过 EXPIRATION_MINUTES 的确认点。
     */
    void cleanupExpired() {
        long now = System.currentTimeMillis();
        long expiryMillis = TimeUnit.MINUTES.toMillis(EXPIRATION_MINUTES);
        int removed = 0;
        for (Map.Entry<String, ConfirmationState> entry : confirmations.entrySet()) {
            if (now - entry.getValue().getCreatedAt() > expiryMillis) {
                confirmations.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("已清理 {} 个过期确认点", removed);
        }
    }

    /**
     * 应用关闭时优雅停止定时清理任务。
     */
    @PreDestroy
    public void shutdown() {
        log.info("正在关闭确认点存储清理任务...");
        cleaner.shutdown();
        try {
            if (!cleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                cleaner.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleaner.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("确认点存储清理任务已关闭");
    }
}
