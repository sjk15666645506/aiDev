package com.deepseek.demo.store;

import com.deepseek.demo.dto.Message;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 会话上下文存储器（Redis 实现），按 conversationId 存储对话状态。
 * <p>
 * 使用 Redis String 存储序列化后的 ConversationState JSON，
 * 利用 Redis TTL（30 分钟）自动清理过期会话，无需定时任务。
 * key 格式：{@code conversation:{conversationId}}
 */
@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    /** Redis key 前缀 */
    private static final String KEY_PREFIX = "conversation:";

    /** 会话过期时间：30 分钟无访问即视为过期 */
    private static final long EXPIRATION_MINUTES = 30;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public ConversationStore(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 会话状态内部类，存储单个会话的所有上下文信息。
     */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class ConversationState {
        /** 对话消息列表 */
        List<Message> messages = new ArrayList<>();
        /** 检查点迭代次数，用于支持回滚到指定轮次 */
        int checkpoint = 0;
        /** 计划是否已获得用户确认 */
        boolean planConfirmed = false;
        /** 用户已审批的操作计划 */
        Object approvedPlan = null;
        /** 当前会话选中的工具 schemas（用于确认回调时恢复） */
        Object selectedToolSchemas = null;
        /** 最后访问时间戳（毫秒） */
        long lastAccessTime = System.currentTimeMillis();
    }

    /** 构建 Redis key */
    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    /**
     * 从 Redis 读取并反序列化会话状态。
     *
     * @param conversationId 会话 ID
     * @return ConversationState 对象，不存在时返回空状态
     */
    private ConversationState load(String conversationId) {
        String json = redisTemplate.opsForValue().get(key(conversationId));
        if (json == null) {
            return new ConversationState();
        }
        try {
            return objectMapper.readValue(json, ConversationState.class);
        } catch (JsonProcessingException e) {
            log.warn("会话反序列化失败: conversationId={}", conversationId, e);
            return new ConversationState();
        }
    }

    /**
     * 将会话状态序列化并写入 Redis，同时设置 TTL。
     */
    private void save(String conversationId, ConversationState state) {
        state.lastAccessTime = System.currentTimeMillis();
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key(conversationId), json,
                    EXPIRATION_MINUTES, TimeUnit.MINUTES);
        } catch (JsonProcessingException e) {
            log.error("会话序列化失败: conversationId={}", conversationId, e);
        }
    }

    /**
     * 获取指定会话的消息列表。
     *
     * @param conversationId 会话 ID
     * @return 消息列表，会话不存在时返回空列表
     */
    public List<Message> getMessages(String conversationId) {
        ConversationState state = load(conversationId);
        if (state.messages.isEmpty()) {
            // 首次访问不触发 save（无内容可写），
            // 仅 touch Redis 以重置 TTL
            redisTemplate.expire(key(conversationId), EXPIRATION_MINUTES, TimeUnit.MINUTES);
        } else {
            save(conversationId, state);
        }
        return state.messages;
    }

    /**
     * 保存指定会话的消息列表。
     *
     * @param conversationId 会话 ID
     * @param messages       要保存的消息列表
     */
    public void saveMessages(String conversationId, List<Message> messages) {
        ConversationState state = load(conversationId);
        state.messages = messages;
        save(conversationId, state);
    }

    /**
     * 保存检查点：同时保存消息列表和检查点迭代次数。
     *
     * @param conversationId 会话 ID
     * @param messages       要保存的消息列表
     * @param iteration      当前迭代次数（检查点标识）
     */
    public void saveCheckpoint(String conversationId, List<Message> messages, int iteration) {
        ConversationState state = load(conversationId);
        state.messages = messages;
        state.checkpoint = iteration;
        save(conversationId, state);
        log.debug("会话 {} 检查点已保存，迭代次数={}", conversationId, iteration);
    }

    /**
     * 清除指定会话的检查点，将迭代次数重置为 0。
     *
     * @param conversationId 会话 ID
     */
    public void clearCheckpoint(String conversationId) {
        ConversationState state = load(conversationId);
        state.checkpoint = 0;
        save(conversationId, state);
        log.debug("会话 {} 检查点已清除", conversationId);
    }

    /**
     * 获取指定会话的计划确认状态。
     *
     * @param conversationId 会话 ID
     * @return 如果计划已确认则返回 true，否则返回 false
     */
    public boolean getPlanConfirmed(String conversationId) {
        ConversationState state = load(conversationId);
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
        ConversationState state = load(conversationId);
        boolean previous = state.planConfirmed;
        state.planConfirmed = confirmed;
        save(conversationId, state);
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
        ConversationState state = load(conversationId);
        return (List<Map<String, Object>>) state.approvedPlan;
    }

    /**
     * 设置指定会话已审批的操作计划。
     *
     * @param conversationId 会话 ID
     * @param plan           审批的计划对象
     */
    public void setApprovedPlan(String conversationId, List<Map<String, Object>> plan) {
        ConversationState state = load(conversationId);
        state.approvedPlan = plan;
        save(conversationId, state);
        log.debug("会话 {} 审批计划已保存", conversationId);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getSelectedToolSchemas(String conversationId) {
        ConversationState state = load(conversationId);
        return (List<Map<String, Object>>) state.selectedToolSchemas;
    }

    public void setSelectedToolSchemas(String conversationId, List<Map<String, Object>> schemas) {
        ConversationState state = load(conversationId);
        state.selectedToolSchemas = schemas;
        save(conversationId, state);
    }
}
