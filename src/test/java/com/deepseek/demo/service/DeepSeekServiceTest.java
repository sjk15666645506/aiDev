package com.deepseek.demo.service;

import com.deepseek.demo.dto.DeepSeekChatResponse;
import com.deepseek.demo.dto.DeepSeekChatResponse.Choice;
import com.deepseek.demo.dto.Message;
import com.deepseek.demo.dto.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeepSeekServiceTest {

    @MockBean
    private RestTemplate restTemplate;

    @Autowired
    private DeepSeekService deepSeekService;

    @Test
    void chat_ShouldReturnAIReply_WhenApiReturnsValidResponse() {
        // Arrange
        Message message = new Message("assistant", "Hello! How can I help you?");
        Choice choice = new Choice();
        choice.setMessage(message);

        DeepSeekChatResponse mockResponse = new DeepSeekChatResponse();
        mockResponse.setChoices(List.of(choice));

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenReturn(ResponseEntity.ok(mockResponse));

        // Act
        String result = deepSeekService.chat("Hello");

        // Assert
        assertEquals("Hello! How can I help you?", result);
        verify(restTemplate, times(1)).postForEntity(
                anyString(), any(HttpEntity.class), eq(DeepSeekChatResponse.class));
    }

    @Test
    void chatWithSystem_ShouldReturnAIReply_WhenApiReturnsValidResponse() {
        // Arrange
        Message message = new Message("assistant", "Java is a versatile language.");
        Choice choice = new Choice();
        choice.setMessage(message);

        DeepSeekChatResponse mockResponse = new DeepSeekChatResponse();
        mockResponse.setChoices(List.of(choice));

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenReturn(ResponseEntity.ok(mockResponse));

        // Act
        String result = deepSeekService.chatWithSystem("Be concise", "Tell me about Java");

        // Assert
        assertEquals("Java is a versatile language.", result);
    }

    @Test
    void chat_ShouldReturnNull_WhenResponseIsNull() {
        // Arrange
        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenReturn(ResponseEntity.ok(null));

        // Act
        String result = deepSeekService.chat("Hello");

        // Assert
        assertNull(result);
    }

    @Test
    void chat_ShouldReturnNull_WhenChoicesIsNull() {
        // Arrange
        DeepSeekChatResponse mockResponse = new DeepSeekChatResponse();
        mockResponse.setChoices(null);

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenReturn(ResponseEntity.ok(mockResponse));

        // Act
        String result = deepSeekService.chat("Hello");

        // Assert
        assertNull(result);
    }

    @Test
    void chat_ShouldReturnNull_WhenChoicesIsEmpty() {
        // Arrange
        DeepSeekChatResponse mockResponse = new DeepSeekChatResponse();
        mockResponse.setChoices(Collections.emptyList());

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenReturn(ResponseEntity.ok(mockResponse));

        // Act
        String result = deepSeekService.chat("Hello");

        // Assert
        assertNull(result);
    }

    @Test
    void chat_ShouldReturnNull_WhenChoiceMessageIsNull() {
        // Arrange
        Choice choice = new Choice();
        choice.setMessage(null);

        DeepSeekChatResponse mockResponse = new DeepSeekChatResponse();
        mockResponse.setChoices(List.of(choice));

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenReturn(ResponseEntity.ok(mockResponse));

        // Act
        String result = deepSeekService.chat("Hello");

        // Assert
        assertNull(result);
    }

    @Test
    void chat_ShouldIncludeBearerTokenAndCorrectUrlAndContentType() {
        // Arrange
        Message message = new Message("assistant", "Reply");
        Choice choice = new Choice();
        choice.setMessage(message);

        DeepSeekChatResponse mockResponse = new DeepSeekChatResponse();
        mockResponse.setChoices(List.of(choice));

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenReturn(ResponseEntity.ok(mockResponse));

        // Act
        deepSeekService.chat("Hi");

        // Assert
        verify(restTemplate).postForEntity(
                eq("https://api.deepseek.com/v1/chat/completions"),
                argThat((HttpEntity<?> entity) -> {
                    HttpHeaders headers = entity.getHeaders();
                    String auth = headers.getFirst("Authorization");
                    String contentType = headers.getFirst("Content-Type");
                    return auth != null
                            && auth.startsWith("Bearer ")
                            && MediaType.APPLICATION_JSON.isCompatibleWith(
                            MediaType.parseMediaType(contentType));
                }),
                eq(DeepSeekChatResponse.class));
    }

    @Test
    void chatWithTools_ShouldReturnResponseWithToolCalls_WhenApiReturnsToolCalls() {
        // Arrange
        Choice choice = new Choice();
        choice.setToolCalls(List.of(new ToolCall("call_1", "function", null)));

        DeepSeekChatResponse mockResponse = new DeepSeekChatResponse();
        mockResponse.setChoices(List.of(choice));

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenReturn(ResponseEntity.ok(mockResponse));

        List<Message> messages = List.of(new Message("user", "What's the weather?"));
        List<Map<String, Object>> tools = List.of(new HashMap<>());

        // Act
        DeepSeekChatResponse result = deepSeekService.chatWithTools(messages, tools);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getChoices());
        assertFalse(result.getChoices().isEmpty());
        assertNotNull(result.getChoices().get(0).getToolCalls());
        assertEquals("call_1", result.getChoices().get(0).getToolCalls().get(0).getId());
        verify(restTemplate, times(1)).postForEntity(
                anyString(), any(HttpEntity.class), eq(DeepSeekChatResponse.class));
    }

    @Test
    void chatWithTools_ShouldReturnResponseWithText_WhenApiReturnsTextWithoutToolCalls() {
        // Arrange
        Message message = new Message("assistant", "I don't need to call any function.");
        Choice choice = new Choice();
        choice.setMessage(message);
        choice.setToolCalls(null);

        DeepSeekChatResponse mockResponse = new DeepSeekChatResponse();
        mockResponse.setChoices(List.of(choice));

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenReturn(ResponseEntity.ok(mockResponse));

        List<Message> messages = List.of(new Message("user", "Hello"));
        List<Map<String, Object>> tools = List.of(new HashMap<>());

        // Act
        DeepSeekChatResponse result = deepSeekService.chatWithTools(messages, tools);

        // Assert
        assertNotNull(result);
        assertNull(result.getChoices().get(0).getToolCalls());
        assertEquals("I don't need to call any function.",
                result.getChoices().get(0).getMessage().getContent());
    }

    @Test
    void chatWithTools_ShouldRetryOnHttp429AndSucceed() {
        // Arrange
        Message message = new Message("assistant", "Success on retry");
        Choice choice = new Choice();
        choice.setMessage(message);

        DeepSeekChatResponse mockResponse = new DeepSeekChatResponse();
        mockResponse.setChoices(List.of(choice));

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))
                .thenReturn(ResponseEntity.ok(mockResponse));

        List<Message> messages = List.of(new Message("user", "Hi"));
        List<Map<String, Object>> tools = List.of(new HashMap<>());

        // Act
        DeepSeekChatResponse result = deepSeekService.chatWithTools(messages, tools);

        // Assert
        assertNotNull(result);
        assertEquals("Success on retry",
                result.getChoices().get(0).getMessage().getContent());
        verify(restTemplate, times(2)).postForEntity(
                anyString(), any(HttpEntity.class), eq(DeepSeekChatResponse.class));
    }

    @Test
    void chatWithTools_ShouldThrowOnHttp401() {
        // Arrange
        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        List<Message> messages = List.of(new Message("user", "Hi"));
        List<Map<String, Object>> tools = List.of(new HashMap<>());

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> deepSeekService.chatWithTools(messages, tools));
        assertEquals("API 认证失败", exception.getMessage());
        verify(restTemplate, times(1)).postForEntity(
                anyString(), any(HttpEntity.class), eq(DeepSeekChatResponse.class));
    }

    @Test
    void chatWithTools_ShouldRetryOnResourceAccessExceptionAndSucceed() {
        // Arrange
        Message message = new Message("assistant", "Recovered from network error");
        Choice choice = new Choice();
        choice.setMessage(message);

        DeepSeekChatResponse mockResponse = new DeepSeekChatResponse();
        mockResponse.setChoices(List.of(choice));

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenThrow(new ResourceAccessException("Connection timeout"))
                .thenReturn(ResponseEntity.ok(mockResponse));

        List<Message> messages = List.of(new Message("user", "Hi"));
        List<Map<String, Object>> tools = List.of(new HashMap<>());

        // Act
        DeepSeekChatResponse result = deepSeekService.chatWithTools(messages, tools);

        // Assert
        assertNotNull(result);
        assertEquals("Recovered from network error",
                result.getChoices().get(0).getMessage().getContent());
        verify(restTemplate, times(2)).postForEntity(
                anyString(), any(HttpEntity.class), eq(DeepSeekChatResponse.class));
    }

    @Test
    void chatWithTools_ShouldThrowWhenAllRetriesExhaustedOn429() {
        // Arrange
        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));

        List<Message> messages = List.of(new Message("user", "Hi"));
        List<Map<String, Object>> tools = List.of(new HashMap<>());

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> deepSeekService.chatWithTools(messages, tools));
        assertEquals("请求过于频繁，请稍后再试", exception.getMessage());
        verify(restTemplate, times(2)).postForEntity(
                anyString(), any(HttpEntity.class), eq(DeepSeekChatResponse.class));
    }

    @Test
    void chatWithTools_ShouldThrowWhenAllRetriesExhaustedOnNetworkError() {
        // Arrange
        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        List<Message> messages = List.of(new Message("user", "Hi"));
        List<Map<String, Object>> tools = List.of(new HashMap<>());

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> deepSeekService.chatWithTools(messages, tools));
        assertEquals("服务暂时不可用，请稍后再试", exception.getMessage());
        verify(restTemplate, times(2)).postForEntity(
                anyString(), any(HttpEntity.class), eq(DeepSeekChatResponse.class));
    }

    @Test
    void chatWithTools_ShouldThrowOnOtherHttpErrorWhenRetriesExhausted() {
        // Arrange
        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(DeepSeekChatResponse.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        List<Message> messages = List.of(new Message("user", "Hi"));
        List<Map<String, Object>> tools = List.of(new HashMap<>());

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> deepSeekService.chatWithTools(messages, tools));
        assertEquals("服务异常", exception.getMessage());
        verify(restTemplate, times(2)).postForEntity(
                anyString(), any(HttpEntity.class), eq(DeepSeekChatResponse.class));
    }
}
