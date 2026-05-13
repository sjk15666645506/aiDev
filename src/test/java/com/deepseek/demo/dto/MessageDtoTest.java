package com.deepseek.demo.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 Message、ToolCall、FunctionCall DTO 的序列化与反序列化。
 */
class MessageDtoTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldSerializeAndDeserializeSimpleMessage() throws JsonProcessingException {
        Message msg = new Message("user", "Hello");

        String json = objectMapper.writeValueAsString(msg);
        assertTrue(json.contains("\"role\":\"user\""));
        assertTrue(json.contains("\"content\":\"Hello\""));

        Message deserialized = objectMapper.readValue(json, Message.class);
        assertEquals("user", deserialized.getRole());
        assertEquals("Hello", deserialized.getContent());
        assertNull(deserialized.getToolCalls());
        assertNull(deserialized.getToolCallId());
    }

    @Test
    void shouldSerializeAndDeserializeAssistantMessageWithToolCalls() throws JsonProcessingException {
        FunctionCall functionCall = new FunctionCall("createTask", "{\"title\":\"测试\",\"assignee\":\"张三\"}");
        ToolCall toolCall = new ToolCall("call_123", "function", functionCall);
        List<ToolCall> toolCalls = Arrays.asList(toolCall);

        Message msg = new Message("assistant", null);
        msg.setToolCalls(toolCalls);

        String json = objectMapper.writeValueAsString(msg);
        assertTrue(json.contains("\"role\":\"assistant\""));
        assertTrue(json.contains("\"tool_calls\""));
        assertTrue(json.contains("\"call_123\""));
        assertTrue(json.contains("\"createTask\""));
        assertTrue(json.contains("\"arguments\""));

        Message deserialized = objectMapper.readValue(json, Message.class);
        assertEquals("assistant", deserialized.getRole());
        assertNull(deserialized.getContent());
        assertNotNull(deserialized.getToolCalls());
        assertEquals(1, deserialized.getToolCalls().size());
        assertEquals("call_123", deserialized.getToolCalls().get(0).getId());
        assertEquals("function", deserialized.getToolCalls().get(0).getType());
        assertNotNull(deserialized.getToolCalls().get(0).getFunction());
        assertEquals("createTask", deserialized.getToolCalls().get(0).getFunction().getName());
        assertEquals("{\"title\":\"测试\",\"assignee\":\"张三\"}", deserialized.getToolCalls().get(0).getFunction().getArguments());
    }

    @Test
    void shouldSerializeAndDeserializeToolRoleMessage() throws JsonProcessingException {
        Message msg = new Message("tool", "任务已创建", "call_123");
        msg.setName("createTask");

        String json = objectMapper.writeValueAsString(msg);
        assertTrue(json.contains("\"tool_call_id\":\"call_123\""));
        assertTrue(json.contains("\"name\":\"createTask\""));
        assertTrue(json.contains("\"role\":\"tool\""));

        Message deserialized = objectMapper.readValue(json, Message.class);
        assertEquals("tool", deserialized.getRole());
        assertEquals("任务已创建", deserialized.getContent());
        assertEquals("call_123", deserialized.getToolCallId());
        assertEquals("createTask", deserialized.getName());
    }

    @Test
    void shouldHandleToolCallWithNullFunction() throws JsonProcessingException {
        ToolCall toolCall = new ToolCall("call_456", "function", null);

        String json = objectMapper.writeValueAsString(toolCall);
        assertTrue(json.contains("\"id\":\"call_456\""));
        assertTrue(json.contains("\"type\":\"function\""));

        ToolCall deserialized = objectMapper.readValue(json, ToolCall.class);
        assertEquals("call_456", deserialized.getId());
        assertEquals("function", deserialized.getType());
        assertNull(deserialized.getFunction());
    }

    @Test
    void shouldHandleFunctionCallWithNullArguments() throws JsonProcessingException {
        FunctionCall fc = new FunctionCall("getWeather", null);

        String json = objectMapper.writeValueAsString(fc);
        assertTrue(json.contains("\"name\":\"getWeather\""));

        FunctionCall deserialized = objectMapper.readValue(json, FunctionCall.class);
        assertEquals("getWeather", deserialized.getName());
        assertNull(deserialized.getArguments());
    }

    @Test
    void shouldHandleEmptyToolCallsList() throws JsonProcessingException {
        Message msg = new Message("assistant", "思考中...");
        msg.setToolCalls(Arrays.asList());

        String json = objectMapper.writeValueAsString(msg);
        assertTrue(json.contains("\"tool_calls\""));

        Message deserialized = objectMapper.readValue(json, Message.class);
        assertNotNull(deserialized.getToolCalls());
        assertTrue(deserialized.getToolCalls().isEmpty());
    }

    @Test
    void shouldHandleMultipleToolCalls() throws JsonProcessingException {
        FunctionCall fc1 = new FunctionCall("funcA", "{\"x\":1}");
        FunctionCall fc2 = new FunctionCall("funcB", "{\"y\":2}");
        ToolCall tc1 = new ToolCall("call_1", "function", fc1);
        ToolCall tc2 = new ToolCall("call_2", "function", fc2);

        Message msg = new Message("assistant", null);
        msg.setToolCalls(Arrays.asList(tc1, tc2));

        String json = objectMapper.writeValueAsString(msg);
        Message deserialized = objectMapper.readValue(json, Message.class);

        assertEquals(2, deserialized.getToolCalls().size());
        assertEquals("call_1", deserialized.getToolCalls().get(0).getId());
        assertEquals("funcA", deserialized.getToolCalls().get(0).getFunction().getName());
        assertEquals("call_2", deserialized.getToolCalls().get(1).getId());
        assertEquals("funcB", deserialized.getToolCalls().get(1).getFunction().getName());
    }
}
