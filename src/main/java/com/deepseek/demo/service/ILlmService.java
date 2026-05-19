package com.deepseek.demo.service;

import com.deepseek.demo.dto.DeepSeekChatRequest;
import com.deepseek.demo.dto.DeepSeekChatResponse;
import com.deepseek.demo.dto.Message;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface ILlmService {
    String chat(String userMessage);
    String chatWithSystem(String systemPrompt, String userMessage);
    DeepSeekChatResponse chat(DeepSeekChatRequest request);
    DeepSeekChatResponse chatWithTools(List<Message> messages, List<Map<String, Object>> tools);
    DeepSeekChatResponse chatWithTools(List<Message> messages, List<Map<String, Object>> tools, String toolChoice);
    void chatStream(String userMessage, Consumer<String> onContent);
}
