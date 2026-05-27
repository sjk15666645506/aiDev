package com.deepseek.demo.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Jackson-based ChatMessage JSON 序列化/反序列化工具。
 * 使用标准 OpenAI message 格式，与旧 Message DTO 的 JSON 格式兼容。
 */
public final class ChatMessageJsonUtil {

    private ChatMessageJsonUtil() {}

    public static String toJson(List<ChatMessage> messages, ObjectMapper objectMapper) {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (ChatMessage msg : messages) {
                list.add(toMap(msg));
            }
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new RuntimeException("ChatMessage 序列化失败", e);
        }
    }

    public static List<ChatMessage> fromJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            List<Map<String, Object>> list = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<ChatMessage> messages = new ArrayList<>();
            for (Map<String, Object> map : list) {
                messages.add(fromMap(map));
            }
            return messages;
        } catch (Exception e) {
            throw new RuntimeException("ChatMessage 反序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(ChatMessage msg) {
        if (msg instanceof SystemMessage) {
            return Map.of("role", "system", "content", nullToEmpty(((SystemMessage) msg).text()));
        } else if (msg instanceof UserMessage) {
            UserMessage um = (UserMessage) msg;
            String text = um.singleText();
            if (um.name() != null) {
                return Map.of("role", "user", "content", nullToEmpty(text), "name", um.name());
            }
            return Map.of("role", "user", "content", nullToEmpty(text));
        } else if (msg instanceof AiMessage) {
            AiMessage am = (AiMessage) msg;
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("role", "assistant");
            map.put("content", am.text());
            if (am.hasToolExecutionRequests()) {
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                    toolCalls.add(Map.of(
                            "id", nullToEmpty(req.id()),
                            "type", "function",
                            "function", Map.of("name", nullToEmpty(req.name()), "arguments", nullToEmpty(req.arguments()))
                    ));
                }
                map.put("tool_calls", toolCalls);
            }
            return map;
        } else if (msg instanceof ToolExecutionResultMessage) {
            ToolExecutionResultMessage trm = (ToolExecutionResultMessage) msg;
            return Map.of("role", "tool", "content", nullToEmpty(trm.text()),
                    "tool_call_id", nullToEmpty(trm.id()),
                    "name", nullToEmpty(trm.toolName()));
        }
        throw new IllegalArgumentException("Unknown ChatMessage type: " + msg.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    private static ChatMessage fromMap(Map<String, Object> map) {
        String role = (String) map.get("role");
        String content = (String) map.get("content");

        if ("system".equals(role)) {
            return SystemMessage.from(content != null ? content : "");
        } else if ("user".equals(role)) {
            String name = (String) map.get("name");
            if (name != null) {
                return UserMessage.from(name, content != null ? content : "");
            }
            return UserMessage.from(content != null ? content : "");
        } else if ("assistant".equals(role)) {
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) map.get("tool_calls");
            if (toolCalls != null && !toolCalls.isEmpty()) {
                List<ToolExecutionRequest> requests = new ArrayList<>();
                for (Map<String, Object> tc : toolCalls) {
                    Map<String, Object> func = (Map<String, Object>) tc.get("function");
                    requests.add(ToolExecutionRequest.builder()
                            .id((String) tc.get("id"))
                            .name(func != null ? (String) func.get("name") : "")
                            .arguments(func != null ? (String) func.get("arguments") : "{}")
                            .build());
                }
                return new AiMessage(content != null ? content : "", requests);
            }
            return AiMessage.from(content != null ? content : "");
        } else if ("tool".equals(role)) {
            String toolCallId = (String) map.get("tool_call_id");
            String name = (String) map.get("name");
            return new ToolExecutionResultMessage(
                    toolCallId != null ? toolCallId : "",
                    name != null ? name : "",
                    content != null ? content : "");
        }
        throw new IllegalArgumentException("Unknown message role: " + role);
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
