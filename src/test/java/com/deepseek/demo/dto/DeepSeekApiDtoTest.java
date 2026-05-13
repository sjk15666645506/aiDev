package com.deepseek.demo.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 DeepSeekChatRequest 和 DeepSeekChatResponse DTO 的序列化与反序列化。
 */
class DeepSeekApiDtoTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    // ==================== DeepSeekChatRequest 测试 ====================

    @Test
    void shouldSerializeRequestWithTools() throws JsonProcessingException {
        DeepSeekChatRequest request = new DeepSeekChatRequest();
        request.setModel("deepseek-chat");
        request.setMessages(Arrays.asList(new Message("user", "北京的天气如何？")));

        Map<String, Object> tool = new HashMap<>();
        Map<String, Object> function = new HashMap<>();
        function.put("name", "get_weather");
        function.put("description", "获取指定城市的天气信息");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> city = new HashMap<>();
        city.put("type", "string");
        city.put("description", "城市名称，如北京");
        properties.put("city", city);
        parameters.put("properties", properties);
        parameters.put("required", Arrays.asList("city"));
        function.put("parameters", parameters);
        tool.put("type", "function");
        tool.put("function", function);

        request.setTools(Arrays.asList(tool));

        String json = objectMapper.writeValueAsString(request);
        assertTrue(json.contains("\"tools\""));
        assertTrue(json.contains("\"get_weather\""));
        assertTrue(json.contains("\"tool_choice\""));
        assertTrue(json.contains("\"auto\""));
    }

    @Test
    void shouldSerializeRequestWithToolChoice() throws JsonProcessingException {
        DeepSeekChatRequest request = new DeepSeekChatRequest();
        request.setToolChoice("none");

        String json = objectMapper.writeValueAsString(request);
        assertTrue(json.contains("\"tool_choice\":\"none\""));
    }

    @Test
    void shouldSerializeRequestWithSpecificToolChoice() throws JsonProcessingException {
        DeepSeekChatRequest request = new DeepSeekChatRequest();

        Map<String, Object> specificChoice = new HashMap<>();
        specificChoice.put("type", "function");
        Map<String, Object> function = new HashMap<>();
        function.put("name", "get_weather");
        specificChoice.put("function", function);

        request.setToolChoice(specificChoice);

        String json = objectMapper.writeValueAsString(request);
        assertTrue(json.contains("\"type\":\"function\""));
        assertTrue(json.contains("\"name\":\"get_weather\""));
    }

    @Test
    void shouldDeserializeRequestWithTools() throws JsonProcessingException {
        String json = "{"
                + "\"model\":\"deepseek-chat\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],"
                + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"test_func\",\"description\":\"A test\",\"parameters\":{\"type\":\"object\"}}}],"
                + "\"tool_choice\":\"auto\""
                + "}";

        DeepSeekChatRequest request = objectMapper.readValue(json, DeepSeekChatRequest.class);
        assertEquals("deepseek-chat", request.getModel());
        assertNotNull(request.getTools());
        assertEquals(1, request.getTools().size());
        assertEquals("auto", request.getToolChoice());
    }

    @Test
    void shouldHandleNullTools() throws JsonProcessingException {
        DeepSeekChatRequest request = new DeepSeekChatRequest();
        request.setTools(null);

        String json = objectMapper.writeValueAsString(request);
        // Jackson 默认包含 null 字段，序列化为 "tools":null
        assertTrue(json.contains("\"tools\""));
        assertTrue(json.contains("null"));

        DeepSeekChatRequest deserialized = objectMapper.readValue(json, DeepSeekChatRequest.class);
        assertNull(deserialized.getTools());
    }

    @Test
    void shouldHandleDefaultToolChoice() throws JsonProcessingException {
        DeepSeekChatRequest request = new DeepSeekChatRequest();

        String json = objectMapper.writeValueAsString(request);
        assertTrue(json.contains("\"tool_choice\":\"auto\""));
    }

    // ==================== DeepSeekChatResponse 测试 ====================

    @Test
    void shouldDeserializeResponseWithToolCalls() throws JsonProcessingException {
        String json = "{"
                + "\"id\":\"chatcmpl-123\","
                + "\"object\":\"chat.completion\","
                + "\"created\":1234567890,"
                + "\"model\":\"deepseek-chat\","
                + "\"choices\":[{"
                + "  \"index\":0,"
                + "  \"message\":{\"role\":\"assistant\",\"content\":null},"
                + "  \"finish_reason\":\"tool_calls\","
                + "  \"tool_calls\":[{"
                + "    \"id\":\"call_123\","
                + "    \"type\":\"function\","
                + "    \"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}"
                + "  }]"
                + "}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}"
                + "}";

        DeepSeekChatResponse response = objectMapper.readValue(json, DeepSeekChatResponse.class);
        assertNotNull(response);
        assertEquals("chatcmpl-123", response.getId());
        assertNotNull(response.getChoices());
        assertEquals(1, response.getChoices().size());

        DeepSeekChatResponse.Choice choice = response.getChoices().get(0);
        assertEquals(0, choice.getIndex());
        assertEquals("tool_calls", choice.getFinishReason());
        assertNotNull(choice.getToolCalls());
        assertEquals(1, choice.getToolCalls().size());

        ToolCall toolCall = choice.getToolCalls().get(0);
        assertEquals("call_123", toolCall.getId());
        assertEquals("function", toolCall.getType());
        assertNotNull(toolCall.getFunction());
        assertEquals("get_weather", toolCall.getFunction().getName());
        assertEquals("{\"city\":\"北京\"}", toolCall.getFunction().getArguments());

        // message.content 为 null（工具调用时）
        assertNull(choice.getMessage().getContent());
    }

    @Test
    void shouldDeserializeResponseWithoutToolCalls() throws JsonProcessingException {
        String json = "{"
                + "\"id\":\"chatcmpl-456\","
                + "\"object\":\"chat.completion\","
                + "\"created\":1234567891,"
                + "\"model\":\"deepseek-chat\","
                + "\"choices\":[{"
                + "  \"index\":0,"
                + "  \"message\":{\"role\":\"assistant\",\"content\":\"你好！\"},"
                + "  \"finish_reason\":\"stop\""
                + "}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":10,\"total_tokens\":15}"
                + "}";

        DeepSeekChatResponse response = objectMapper.readValue(json, DeepSeekChatResponse.class);
        assertNotNull(response.getChoices());
        DeepSeekChatResponse.Choice choice = response.getChoices().get(0);
        assertNull(choice.getToolCalls());
        assertEquals("你好！", choice.getMessage().getContent());
        assertEquals("stop", choice.getFinishReason());
    }

    @Test
    void shouldHandleMultipleToolCallsInResponse() throws JsonProcessingException {
        String json = "{"
                + "\"choices\":[{"
                + "  \"index\":0,"
                + "  \"message\":{\"role\":\"assistant\",\"content\":null},"
                + "  \"finish_reason\":\"tool_calls\","
                + "  \"tool_calls\":["
                + "    {\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"funcA\",\"arguments\":\"{}\"}},"
                + "    {\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"funcB\",\"arguments\":\"{}\"}}"
                + "  ]"
                + "}]"
                + "}";

        DeepSeekChatResponse response = objectMapper.readValue(json, DeepSeekChatResponse.class);
        List<ToolCall> toolCalls = response.getChoices().get(0).getToolCalls();
        assertEquals(2, toolCalls.size());
        assertEquals("call_1", toolCalls.get(0).getId());
        assertEquals("funcA", toolCalls.get(0).getFunction().getName());
        assertEquals("call_2", toolCalls.get(1).getId());
        assertEquals("funcB", toolCalls.get(1).getFunction().getName());
    }

    @Test
    void shouldHandleChoiceWithNullToolCalls() throws JsonProcessingException {
        DeepSeekChatResponse response = new DeepSeekChatResponse();
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setIndex(0);
        choice.setToolCalls(null);
        response.setChoices(Arrays.asList(choice));

        String json = objectMapper.writeValueAsString(response);
        // Jackson 默认包含 null 字段，序列化为 "tool_calls":null
        assertTrue(json.contains("\"tool_calls\""));

        DeepSeekChatResponse deserialized = objectMapper.readValue(json, DeepSeekChatResponse.class);
        assertNull(deserialized.getChoices().get(0).getToolCalls());
    }

    @Test
    void shouldRoundTripRequestWithCompleteStructure() throws JsonProcessingException {
        DeepSeekChatRequest request = new DeepSeekChatRequest();
        request.setModel("deepseek-chat");
        request.setMessages(Arrays.asList(
                new Message("system", "你是一个助手"),
                new Message("user", "天气如何？")
        ));
        request.setMaxTokens(1024);
        request.setTemperature(0.7);
        request.setStream(false);

        Map<String, Object> tool = new HashMap<>();
        Map<String, Object> func = new HashMap<>();
        func.put("name", "get_weather");
        func.put("description", "天气查询");
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> loc = new HashMap<>();
        loc.put("type", "string");
        props.put("location", loc);
        params.put("properties", props);
        func.put("parameters", params);
        tool.put("type", "function");
        tool.put("function", func);
        request.setTools(Arrays.asList(tool));
        request.setToolChoice("auto");

        String json = objectMapper.writeValueAsString(request);
        DeepSeekChatRequest deserialized = objectMapper.readValue(json, DeepSeekChatRequest.class);

        assertEquals("deepseek-chat", deserialized.getModel());
        assertEquals(2, deserialized.getMessages().size());
        assertEquals(1024, deserialized.getMaxTokens());
        assertEquals(0.7, deserialized.getTemperature());
        assertFalse(deserialized.isStream());
        assertNotNull(deserialized.getTools());
        assertEquals(1, deserialized.getTools().size());
        assertEquals("auto", deserialized.getToolChoice());
    }

    @Test
    void shouldRoundTripResponseWithCompleteStructure() throws JsonProcessingException {
        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setId("chatcmpl-789");
        response.setObject("chat.completion");
        response.setCreated(1234567892L);
        response.setModel("deepseek-chat");

        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setIndex(0);
        choice.setMessage(new Message("assistant", null));

        FunctionCall fc = new FunctionCall("search", "{\"q\":\"test\"}");
        ToolCall tc = new ToolCall("call_999", "function", fc);
        choice.setToolCalls(Arrays.asList(tc));
        choice.setFinishReason("tool_calls");

        response.setChoices(Arrays.asList(choice));

        DeepSeekChatResponse.Usage usage = new DeepSeekChatResponse.Usage();
        usage.setPromptTokens(10);
        usage.setCompletionTokens(20);
        usage.setTotalTokens(30);
        response.setUsage(usage);

        String json = objectMapper.writeValueAsString(response);
        DeepSeekChatResponse deserialized = objectMapper.readValue(json, DeepSeekChatResponse.class);

        assertEquals("chatcmpl-789", deserialized.getId());
        assertNotNull(deserialized.getChoices());
        assertEquals(1, deserialized.getChoices().size());
        assertEquals("tool_calls", deserialized.getChoices().get(0).getFinishReason());
        assertNotNull(deserialized.getChoices().get(0).getToolCalls());
        assertEquals(1, deserialized.getChoices().get(0).getToolCalls().size());
        assertEquals("call_999", deserialized.getChoices().get(0).getToolCalls().get(0).getId());
        assertEquals(10, deserialized.getUsage().getPromptTokens());
        assertEquals(20, deserialized.getUsage().getCompletionTokens());
        assertEquals(30, deserialized.getUsage().getTotalTokens());
    }
}
