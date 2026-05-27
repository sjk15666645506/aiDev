package com.deepseek.demo.store;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Map;

public interface IConversationStore {
    List<ChatMessage> getMessages(String conversationId);
    void saveMessages(String conversationId, List<ChatMessage> messages);
    void saveCheckpoint(String conversationId, List<ChatMessage> messages, int iteration);
    void clearCheckpoint(String conversationId);
    boolean getPlanConfirmed(String conversationId);
    boolean setPlanConfirmed(String conversationId, boolean confirmed);
    List<Map<String, Object>> getApprovedPlan(String conversationId);
    void setApprovedPlan(String conversationId, List<Map<String, Object>> plan);
    List<ToolSpecification> getSelectedToolSpecifications(String conversationId);
    void setSelectedToolSpecifications(String conversationId, List<ToolSpecification> schemas);
    String getTraceId(String conversationId);
    void setTraceId(String conversationId, String traceId);
    void removeLocal(String conversationId);
}
