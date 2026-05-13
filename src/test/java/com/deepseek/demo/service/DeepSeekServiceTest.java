package com.deepseek.demo.service;

import com.deepseek.demo.dto.DeepSeekChatResponse;
import com.deepseek.demo.dto.DeepSeekChatResponse.Choice;
import com.deepseek.demo.dto.Message;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

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
}
