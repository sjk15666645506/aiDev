package com.deepseek.demo.store;

import com.deepseek.demo.dto.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话上下文存储器，按 conversationId 存储对话状态。
 * <p>
 * 使用 ConcurrentHashMap 作为线程安全的存储后端，并附带定时清理过期会话的机制。
 * 每个会话包含消息列表、检查点、计划确认状态和最后访问时间。
 */
@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    /** 会话过期时间：30 分钟无访问即视为过期 */
    private static final long EXPIRATION_MINUTES = 30;

    /** 清理任务执行间隔：5 分钟 */
    private static final long CLEANUP_INTERVAL_MINUTES = 5;

    /** 线程安全的会话存储映射 */
    private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();

    /** 定时清理过期会话的调度器 */
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "conversation-store-cleaner");
        t.setDaemon(true);
        return t;
    });

    /**
     * 会话状态内部类，存储单个会话的所有上下文信息。
     */
    private static class ConversationState {
        /** 对话消息列表 */
        List<Message> messages = new ArrayList<>();
        /** 检查点迭代次数，用于支持回滚到指定轮次 */
        int checkpoint = 0;
        /** 计划是否已获得用户确认 */
        boolean planConfirmed = false;
        /** 用户已审批的操作计划 */
        Object approvedPlan = null;
        /** 最后访问时间戳（毫秒） */
        long lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 构造器，启动定时清理任务。
     * 每隔 CLEANUP_INTERVAL_MINUTES 分钟清理一次过期会话。
     */
    public ConversationStore() {
        cleaner.scheduleWithFixedDelay(this::cleanupExpired,
                CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
        log.info("会话存储定时清理任务已启动，过期时间={}分钟，清理间隔={}分钟",
                EXPIRATION_MINUTES, CLEANUP_INTERVAL_MINUTES);
    }

    /**
     * 获取指定会话的消息列表。
     *
     * @param conversationId 会话 ID
     * @return 消息列表，会话不存在时返回空列表
     */
    public List<Message> getMessages(String conversationId) {
        ConversationState state = conversations.get(conversationId);
        if (state == null) {
            return new ArrayList<>();
        }
        state.lastAccessTime = System.currentTimeMillis();
        return state.messages;
    }

    /**
     * 保存指定会话的消息列表。
     *
     * @param conversationId 会话 ID
     * @param messages       要保存的消息列表
     */
    public void saveMessages(String conversationId, List<Message> messages) {
        ConversationState state = conversations.computeIfAbsent(conversationId,
                k -> new ConversationState());
        state.messages = messages;
        state.lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 保存检查点：同时保存消息列表和检查点迭代次数。
     * <p>
     * 用于在对话流程的关键节点记录状态，支持后续回滚操作。
     *
     * @param conversationId 会话 ID
     * @param messages       要保存的消息列表
     * @param iteration      当前迭代次数（检查点标识）
     */
    public void saveCheckpoint(String conversationId, List<Message> messages, int iteration) {
        ConversationState state = conversations.computeIfAbsent(conversationId,
                k -> new ConversationState());
        state.messages = messages;
        state.checkpoint = iteration;
        state.lastAccessTime = System.currentTimeMillis();
        log.debug("会话 {} 检查点已保存，迭代次数={}", conversationId, iteration);
    }

    /**
     * 清除指定会话的检查点，将迭代次数重置为 0。
     *
     * @param conversationId 会话 ID
     */
    public void clearCheckpoint(String conversationId) {
        ConversationState state = conversations.get(conversationId);
        if (state != null) {
            state.checkpoint = 0;
            state.lastAccessTime = System.currentTimeMillis();
            log.debug("会话 {} 检查点已清除", conversationId);
        }
    }

    /**
     * 获取指定会话的计划确认状态。
     *
     * @param conversationId 会话 ID
     * @return 如果计划已确认则返回 true，否则返回 false
     */
    public boolean getPlanConfirmed(String conversationId) {
        ConversationState state = conversations.get(conversationId);
        if (state == null) {
            return false;
        }
        state.lastAccessTime = System.currentTimeMillis();
        return state.planConfirmed;
    }

    /**
     * 设置指定会话的计划确认状态。
     *
     * @param conversationId 会话 ID
     * @param confirmed      是否已确认
     * @return 设置前的确认状态
     */
    public boolean setPlanConfirmed(String conversationId, boolean confirmed) {
        ConversationState state = conversations.computeIfAbsent(conversationId,
                k -> new ConversationState());
        boolean previous = state.planConfirmed;
        state.planConfirmed = confirmed;
        state.lastAccessTime = System.currentTimeMillis();
        log.debug("会话 {} 计划确认状态已更新：{} -> {}", conversationId, previous, confirmed);
        return previous;
    }

    /**
     * 获取指定会话已审批的操作计划。
     *
     * @param conversationId 会话 ID
     * @return 审批的计划对象，不存在则返回 null
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getApprovedPlan(String conversationId) {
        ConversationState state = conversations.get(conversationId);
        if (state == null) {
            return null;
        }
        state.lastAccessTime = System.currentTimeMillis();
        return (List<Map<String, Object>>) state.approvedPlan;
    }

    /**
     * 设置指定会话已审批的操作计划。
     *
     * @param conversationId 会话 ID
     * @param plan           审批的计划对象
     */
    public void setApprovedPlan(String conversationId, List<Map<String, Object>> plan) {
        ConversationState state = conversations.computeIfAbsent(conversationId,
                k -> new ConversationState());
        state.approvedPlan = plan;
        state.lastAccessTime = System.currentTimeMillis();
        log.debug("会话 {} 审批计划已保存", conversationId);
    }

    // ==================== 内部方法 ====================

    /**
     * 清理过期会话：遍历所有会话，移除最后访问时间超过 EXPIRATION_MINUTES 的会话。
     */
    void cleanupExpired() {
        long now = System.currentTimeMillis();
        long expiryMillis = TimeUnit.MINUTES.toMillis(EXPIRATION_MINUTES);
        int removed = 0;
        for (Map.Entry<String, ConversationState> entry : conversations.entrySet()) {
            if (now - entry.getValue().lastAccessTime > expiryMillis) {
                conversations.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("已清理 {} 个过期会话", removed);
        }
    }

    /**
     * 应用关闭时优雅停止定时清理任务。
     */
    @PreDestroy
    public void shutdown() {
        log.info("正在关闭会话存储清理任务...");
        cleaner.shutdown();
        try {
            if (!cleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                cleaner.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleaner.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("会话存储清理任务已关闭");
    }
}
