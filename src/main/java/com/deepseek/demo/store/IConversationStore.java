package com.deepseek.demo.store;

import com.deepseek.demo.dto.Message;

import java.util.List;
import java.util.Map;

public interface IConversationStore {
    List<Message> getMessages(String conversationId);
    void saveMessages(String conversationId, List<Message> messages);
    void saveCheckpoint(String conversationId, List<Message> messages, int iteration);
    void clearCheckpoint(String conversationId);
    boolean getPlanConfirmed(String conversationId);
    boolean setPlanConfirmed(String conversationId, boolean confirmed);
    List<Map<String, Object>> getApprovedPlan(String conversationId);
    void setApprovedPlan(String conversationId, List<Map<String, Object>> plan);
    List<Map<String, Object>> getSelectedToolSchemas(String conversationId);
    void setSelectedToolSchemas(String conversationId, List<Map<String, Object>> schemas);
    String getTraceId(String conversationId);
    void setTraceId(String conversationId, String traceId);
    void removeLocal(String conversationId);
}
