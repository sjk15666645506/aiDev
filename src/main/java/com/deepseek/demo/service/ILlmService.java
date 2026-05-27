package com.deepseek.demo.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.function.Consumer;

public interface ILlmService {
    String chat(String userMessage);
    String chatWithSystem(String systemPrompt, String userMessage);
    Response<AiMessage> chat(List<ChatMessage> messages);
    Response<AiMessage> chatWithTools(List<ChatMessage> messages, List<ToolSpecification> tools);
    void chatStream(String userMessage, Consumer<String> onContent);
}
