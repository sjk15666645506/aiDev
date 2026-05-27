package com.deepseek.demo.store;

import com.deepseek.demo.util.ChatMessageJsonUtil;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class ConversationStore implements IConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    private static final String KEY_PREFIX = "conversation:";
    private static final long EXPIRATION_MINUTES = 30;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private final LocalCache<String, ConversationState> localCache;
    private volatile boolean redisDegraded = false;

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    private Object lockFor(String conversationId) {
        return locks.computeIfAbsent(conversationId, k -> new Object());
    }

    public ConversationStore(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper,
                              @Value("${store.conversation.local-cache.max-capacity:1000}") int maxCapacity,
                              @Value("${store.conversation.local-cache.ttl-minutes:30}") int ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.localCache = new LocalCache<>(maxCapacity, ttlMinutes, TimeUnit.MINUTES);
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class ConversationState {
        /** 对话消息 JSON 字符串（使用标准 OpenAI 格式） */
        String messagesJson = "[]";
        int checkpoint = 0;
        boolean planConfirmed = false;
        Object approvedPlan = null;
        /** 当前会话选中的工具 schemas JSON */
        String selectedToolSchemasJson = "[]";
        String traceId = null;
        long lastAccessTime = System.currentTimeMillis();

        @JsonIgnore
        List<ChatMessage> getMessages(ObjectMapper om) {
            try {
                return ChatMessageJsonUtil.fromJson(messagesJson, om);
            } catch (Exception e) {
                log.warn("消息反序列化失败，返回空列表", e);
                return new ArrayList<>();
            }
        }

        @JsonIgnore
        void setMessages(List<ChatMessage> messages, ObjectMapper om) {
            this.messagesJson = ChatMessageJsonUtil.toJson(messages, om);
        }

        @JsonIgnore
        @SuppressWarnings("unchecked")
        List<ToolSpecification> getSelectedToolSpecs(ObjectMapper om) {
            try {
                return om.readValue(selectedToolSchemasJson,
                        om.getTypeFactory().constructCollectionType(List.class, ToolSpecification.class));
            } catch (Exception e) {
                log.warn("工具 schemas 反序列化失败，返回空列表", e);
                return new ArrayList<>();
            }
        }

        @JsonIgnore
        void setSelectedToolSpecs(List<ToolSpecification> specs, ObjectMapper om) {
            try {
                this.selectedToolSchemasJson = om.writeValueAsString(specs);
            } catch (JsonProcessingException e) {
                log.error("工具 schemas 序列化失败", e);
                this.selectedToolSchemasJson = "[]";
            }
        }
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    private ConversationState load(String conversationId) {
        try {
            String json = redisTemplate.opsForValue().get(key(conversationId));
            if (redisDegraded) {
                redisDegraded = false;
                log.warn("Redis 已恢复，退出本地缓存降级模式");
            }
            if (json == null) {
                return new ConversationState();
            }
            return objectMapper.readValue(json, ConversationState.class);
        } catch (RedisConnectionFailureException e) {
            if (!redisDegraded) {
                redisDegraded = true;
                log.warn("Redis 连接失败，切换到本地缓存降级模式", e);
            }
            ConversationState local = localCache.get(conversationId);
            if (local != null) {
                return local;
            }
            ConversationState fresh = new ConversationState();
            localCache.put(conversationId, fresh);
            return fresh;
        } catch (JsonProcessingException e) {
            log.warn("会话反序列化失败: conversationId={}", conversationId, e);
            return new ConversationState();
        }
    }

    private void save(String conversationId, ConversationState state) {
        state.lastAccessTime = System.currentTimeMillis();
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key(conversationId), json,
                    EXPIRATION_MINUTES, TimeUnit.MINUTES);
            if (redisDegraded) {
                redisDegraded = false;
                log.warn("Redis 已恢复，退出本地缓存降级模式");
            }
        } catch (JsonProcessingException e) {
            log.error("会话序列化失败: conversationId={}", conversationId, e);
        } catch (RedisConnectionFailureException e) {
            if (!redisDegraded) {
                redisDegraded = true;
                log.warn("Redis 连接失败，切换到本地缓存降级模式", e);
            }
            localCache.put(conversationId, state);
        }
    }

    public void removeLocal(String conversationId) {
        localCache.remove(conversationId);
    }

    @Override
    public List<ChatMessage> getMessages(String conversationId) {
        synchronized (lockFor(conversationId)) {
            ConversationState state = load(conversationId);
            List<ChatMessage> messages = state.getMessages(objectMapper);
            if (messages.isEmpty()) {
                if (!redisDegraded) {
                    try {
                        redisTemplate.expire(key(conversationId), EXPIRATION_MINUTES, TimeUnit.MINUTES);
                    } catch (RedisConnectionFailureException e) {
                        redisDegraded = true;
                        log.warn("Redis 连接失败，切换到本地缓存降级模式", e);
                    }
                }
            } else {
                save(conversationId, state);
            }
            return messages;
        }
    }

    @Override
    public void saveMessages(String conversationId, List<ChatMessage> messages) {
        synchronized (lockFor(conversationId)) {
            ConversationState state = load(conversationId);
            state.setMessages(messages, objectMapper);
            save(conversationId, state);
        }
    }

    @Override
    public void saveCheckpoint(String conversationId, List<ChatMessage> messages, int iteration) {
        synchronized (lockFor(conversationId)) {
            ConversationState state = load(conversationId);
            state.setMessages(messages, objectMapper);
            state.checkpoint = iteration;
            save(conversationId, state);
            log.debug("会话 {} 检查点已保存，迭代次数={}", conversationId, iteration);
        }
    }

    @Override
    public void clearCheckpoint(String conversationId) {
        synchronized (lockFor(conversationId)) {
            ConversationState state = load(conversationId);
            state.checkpoint = 0;
            save(conversationId, state);
            log.debug("会话 {} 检查点已清除", conversationId);
        }
    }

    @Override
    public boolean getPlanConfirmed(String conversationId) {
        ConversationState state = load(conversationId);
        return state.planConfirmed;
    }

    @Override
    public boolean setPlanConfirmed(String conversationId, boolean confirmed) {
        synchronized (lockFor(conversationId)) {
            ConversationState state = load(conversationId);
            boolean previous = state.planConfirmed;
            state.planConfirmed = confirmed;
            save(conversationId, state);
            log.debug("会话 {} 计划确认状态已更新：{} -> {}", conversationId, previous, confirmed);
            return previous;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getApprovedPlan(String conversationId) {
        ConversationState state = load(conversationId);
        return (List<Map<String, Object>>) state.approvedPlan;
    }

    @Override
    public void setApprovedPlan(String conversationId, List<Map<String, Object>> plan) {
        synchronized (lockFor(conversationId)) {
            ConversationState state = load(conversationId);
            state.approvedPlan = plan;
            save(conversationId, state);
            log.debug("会话 {} 审批计划已保存", conversationId);
        }
    }

    @Override
    public List<ToolSpecification> getSelectedToolSpecifications(String conversationId) {
        ConversationState state = load(conversationId);
        return state.getSelectedToolSpecs(objectMapper);
    }

    @Override
    public void setSelectedToolSpecifications(String conversationId, List<ToolSpecification> schemas) {
        synchronized (lockFor(conversationId)) {
            ConversationState state = load(conversationId);
            state.setSelectedToolSpecs(schemas, objectMapper);
            save(conversationId, state);
        }
    }

    @Override
    public String getTraceId(String conversationId) {
        ConversationState state = load(conversationId);
        return state.traceId;
    }

    @Override
    public void setTraceId(String conversationId, String traceId) {
        synchronized (lockFor(conversationId)) {
            ConversationState state = load(conversationId);
            state.traceId = traceId;
            save(conversationId, state);
        }
    }
}
