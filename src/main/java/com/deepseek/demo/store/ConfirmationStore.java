package com.deepseek.demo.store;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class ConfirmationStore {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationStore.class);

    private static final String KEY_PREFIX = "confirmation:";
    private static final long EXPIRATION_MINUTES = 5;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private final LocalCache<String, ConfirmationState> localCache;
    private volatile boolean redisDegraded = false;

    public ConfirmationStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                              @Value("${store.confirmation.local-cache.max-capacity:500}") int maxCapacity,
                              @Value("${store.confirmation.local-cache.ttl-minutes:5}") int ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.localCache = new LocalCache<>(maxCapacity, ttlMinutes, TimeUnit.MINUTES);
    }

    public static class ConfirmationState {
        private String conversationId;
        private String type;
        private List<Map<String, Object>> planToolCalls;
        private String toolName;
        private String toolCallId;
        private String toolArguments;
        /** JSON string of pending ToolExecutionRequest list (Map-based for Jackson compat) */
        private String pendingRequestsJson = "[]";
        private long createdAt;
        private boolean consumed;

        public ConfirmationState() {
            this.createdAt = System.currentTimeMillis();
        }

        @JsonIgnore
        public List<ToolExecutionRequest> getPendingRequests(ObjectMapper om) {
            if (pendingRequestsJson == null || pendingRequestsJson.isEmpty()) return Collections.emptyList();
            try {
                List<Map<String, Object>> maps = om.readValue(pendingRequestsJson,
                        new TypeReference<List<Map<String, Object>>>() {});
                List<ToolExecutionRequest> requests = new ArrayList<>();
                for (Map<String, Object> m : maps) {
                    requests.add(ToolExecutionRequest.builder()
                            .id((String) m.get("id"))
                            .name((String) m.get("name"))
                            .arguments((String) m.getOrDefault("arguments", "{}"))
                            .build());
                }
                return requests;
            } catch (Exception e) {
                log.warn("反序列化 pending requests 失败", e);
                return Collections.emptyList();
            }
        }

        @JsonIgnore
        public void setPendingRequests(List<ToolExecutionRequest> requests, ObjectMapper om) {
            try {
                List<Map<String, String>> maps = new ArrayList<>();
                for (ToolExecutionRequest req : requests) {
                    Map<String, String> m = new java.util.LinkedHashMap<>();
                    m.put("id", req.id());
                    m.put("name", req.name());
                    m.put("arguments", req.arguments());
                    maps.add(m);
                }
                this.pendingRequestsJson = om.writeValueAsString(maps);
            } catch (JsonProcessingException e) {
                log.error("序列化 pending requests 失败", e);
                this.pendingRequestsJson = "[]";
            }
        }

        // Getters and setters
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
        public String getPendingRequestsJson() { return pendingRequestsJson; }
        public void setPendingRequestsJson(String pendingRequestsJson) { this.pendingRequestsJson = pendingRequestsJson; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
        public boolean isConsumed() { return consumed; }
        public void setConsumed(boolean consumed) { this.consumed = consumed; }
    }

    private String key(String confirmationId) {
        return KEY_PREFIX + confirmationId;
    }

    public String createPlanConfirmation(String conversationId, List<Map<String, Object>> plan) {
        ConfirmationState state = new ConfirmationState();
        state.setConversationId(conversationId);
        state.setType("plan");
        state.setPlanToolCalls(plan);
        return saveConfirmation(state);
    }

    public String createExecConfirmation(String conversationId, ToolExecutionRequest toolCall,
                                          List<ToolExecutionRequest> pendingToolCalls) {
        ConfirmationState state = new ConfirmationState();
        state.setConversationId(conversationId);
        state.setType("exec");
        if (toolCall != null) {
            state.setToolName(toolCall.name());
            state.setToolCallId(toolCall.id());
            state.setToolArguments(toolCall.arguments());
        }
        state.setPendingRequests(pendingToolCalls, objectMapper);
        return saveConfirmation(state);
    }

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

    public boolean isConsumed(String confirmationId) {
        ConfirmationState state = get(confirmationId);
        return state != null && state.isConsumed();
    }
}
