package com.deepseek.demo.store;

import com.deepseek.demo.dto.ToolCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 确认点存储器（Redis 实现），按 confirmationId 存储用户确认点状态。
 * <p>
 * 使用 Redis String 存储序列化后的 ConfirmationState JSON，
 * 利用 Redis TTL（5 分钟）自动清理过期确认点，无需定时任务。
 * key 格式：{@code confirmation:{confirmationId}}
 * <p>
 * plan 类型存储 LLM 提议的完整操作计划，exec 类型存储单个写工具调用及其参数。
 */
@Component
public class ConfirmationStore {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationStore.class);

    /** Redis key 前缀 */
    private static final String KEY_PREFIX = "confirmation:";

    /** 确认点过期时间：5 分钟无操作即视为过期 */
    private static final long EXPIRATION_MINUTES = 5;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** Redis 不可用时的本地缓存降级（TTL + 容量上限） */
    private final LocalCache<String, ConfirmationState> localCache;

    /** Redis 是否处于降级模式 */
    private volatile boolean redisDegraded = false;

    public ConfirmationStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                              @Value("${store.confirmation.local-cache.max-capacity:500}") int maxCapacity,
                              @Value("${store.confirmation.local-cache.ttl-minutes:5}") int ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.localCache = new LocalCache<>(maxCapacity, ttlMinutes, TimeUnit.MINUTES);
    }

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

    /** 构建 Redis key */
    private String key(String confirmationId) {
        return KEY_PREFIX + confirmationId;
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
        return saveConfirmation(state);
    }

    /**
     * 创建 exec 类型确认点。
     *
     * @param conversationId  关联的对话会话 ID
     * @param toolCall        待确认的单个工具调用
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
        return saveConfirmation(state);
    }

    /**
     * 序列化确认点并写入 Redis，设置 TTL。
     *
     * @return 确认点唯一标识
     */
    private String saveConfirmation(ConfirmationState state) {
        String confirmationId = UUID.randomUUID().toString();
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key(confirmationId), json,
                    EXPIRATION_MINUTES, TimeUnit.MINUTES);
            if (redisDegraded) {
                redisDegraded = false;
                log.warn("Redis 已恢复，退出本地缓存降级模式");
            }
            log.info("已创建 {} 确认点: id={}, conversationId={}",
                    state.getType(), confirmationId, state.getConversationId());
            return confirmationId;
        } catch (JsonProcessingException e) {
            log.error("确认点序列化失败", e);
            throw new RuntimeException("确认点创建失败", e);
        } catch (RedisConnectionFailureException e) {
            if (!redisDegraded) {
                redisDegraded = true;
                log.warn("Redis 连接失败，切换到本地缓存降级模式", e);
            }
            localCache.put(confirmationId, state);
            log.info("已创建 {} 确认点(本地缓存): id={}, conversationId={}",
                    state.getType(), confirmationId, state.getConversationId());
            return confirmationId;
        }
    }

    /**
     * 获取指定确认点的状态。
     *
     * @param confirmationId 确认点 ID
     * @return ConfirmationState 对象，已过期或不存在则返回 null
     */
    public ConfirmationState get(String confirmationId) {
        try {
            String json = redisTemplate.opsForValue().get(key(confirmationId));
            if (redisDegraded) {
                redisDegraded = false;
                log.warn("Redis 已恢复，退出本地缓存降级模式");
            }
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, ConfirmationState.class);
        } catch (RedisConnectionFailureException e) {
            if (!redisDegraded) {
                redisDegraded = true;
                log.warn("Redis 连接失败，切换到本地缓存降级模式", e);
            }
            return localCache.get(confirmationId);
        } catch (JsonProcessingException e) {
            log.warn("确认点反序列化失败: confirmationId={}", confirmationId, e);
            return null;
        }
    }

    /**
     * 消费指定确认点，将其标记为已消费并写回 Redis。
     *
     * @param confirmationId 确认点 ID
     */
    public void consume(String confirmationId) {
        ConfirmationState state = get(confirmationId);
        if (state != null) {
            state.setConsumed(true);
            try {
                String json = objectMapper.writeValueAsString(state);
                redisTemplate.opsForValue().set(key(confirmationId), json,
                        EXPIRATION_MINUTES, TimeUnit.MINUTES);
                if (redisDegraded) {
                    redisDegraded = false;
                    log.warn("Redis 已恢复，退出本地缓存降级模式");
                }
            } catch (JsonProcessingException e) {
                log.error("确认点序列化失败", e);
            } catch (RedisConnectionFailureException e) {
                if (!redisDegraded) {
                    redisDegraded = true;
                    log.warn("Redis 连接失败，切换到本地缓存降级模式", e);
                }
                localCache.put(confirmationId, state);
            }
        }
    }

    /**
     * 检查指定确认点是否已被消费。
     *
     * @param confirmationId 确认点 ID
     * @return 已消费返回 true，不存在或未消费返回 false
     */
    public boolean isConsumed(String confirmationId) {
        ConfirmationState state = get(confirmationId);
        return state != null && state.isConsumed();
    }
}
