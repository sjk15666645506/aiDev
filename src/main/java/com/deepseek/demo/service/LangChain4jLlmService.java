package com.deepseek.demo.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Service
@Primary
public class LangChain4jLlmService implements ILlmService {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jLlmService.class);

    private final OpenAiChatModel chatModel;
    private final OpenAiStreamingChatModel streamingChatModel;

    public LangChain4jLlmService(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.base-url}") String baseUrl) {
        this.chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl + "/v1")
                .modelName("deepseek-v4-flash")
                .timeout(Duration.ofSeconds(30))
                .maxRetries(2)
                .logRequests(true)
                .logResponses(true)
                .build();
        this.streamingChatModel = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl + "/v1")
                .modelName("deepseek-v4-flash")
                .timeout(Duration.ofSeconds(60))
                .build();
        log.info("LangChain4jLlmService initialized: baseUrl={}/v1", baseUrl);
    }

    @Override
    public String chat(String userMessage) {
        log.info("非流式请求: message={}", truncate(userMessage, 50));
        Response<AiMessage> response = chatModel.generate(
                Collections.singletonList(UserMessage.from(userMessage)));
        String reply = response.content().text();
        log.info("非流式响应完成: replyLength={}", reply != null ? reply.length() : 0);
        return reply;
    }

    @Override
    public String chatWithSystem(String systemPrompt, String userMessage) {
        log.info("非流式请求(带系统提示): system={}, message={}",
                truncate(systemPrompt, 30), truncate(userMessage, 50));
        Response<AiMessage> response = chatModel.generate(
                List.of(SystemMessage.from(systemPrompt), UserMessage.from(userMessage)));
        String reply = response.content().text();
        log.info("非流式响应完成: replyLength={}", reply != null ? reply.length() : 0);
        return reply;
    }

    @Override
    public Response<AiMessage> chat(List<ChatMessage> messages) {
        log.info("对话请求: messagesCount={}", messages.size());
        long start = System.currentTimeMillis();
        Response<AiMessage> response = chatModel.generate(messages);
        long elapsed = System.currentTimeMillis() - start;
        log.info("对话响应: 耗时={}ms, finishReason={}",
                elapsed, response.finishReason());
        return response;
    }

    @Override
    public Response<AiMessage> chatWithTools(List<ChatMessage> messages,
                                              List<ToolSpecification> tools) {
        log.info("调用 LLM(带 tools): toolsCount={}, messagesCount={}",
                tools != null ? tools.size() : 0, messages.size());

        long start = System.currentTimeMillis();
        Response<AiMessage> response;
        try {
            response = chatModel.generate(messages, tools != null ? tools : Collections.emptyList());
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            throw new RuntimeException("服务暂时不可用，请稍后再试", e);
        }
        long elapsed = System.currentTimeMillis() - start;

        AiMessage aiMsg = response.content();
        boolean hasToolCalls = aiMsg.hasToolExecutionRequests();
        log.info("LLM 响应: 耗时={}ms, hasToolCalls={}", elapsed, hasToolCalls);
        return response;
    }

    @Override
    public void chatStream(String userMessage, Consumer<String> onContent) {
        log.info("流式请求开始: message={}", truncate(userMessage, 50));
        long start = System.currentTimeMillis();

        CompletableFuture<Void> future = new CompletableFuture<>();
        streamingChatModel.generate(
                Collections.singletonList(UserMessage.from(userMessage)),
                new StreamingResponseHandler<AiMessage>() {
                    @Override
                    public void onNext(String token) {
                        onContent.accept(token);
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("流式请求失败", error);
                        future.completeExceptionally(error);
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        long elapsed = System.currentTimeMillis() - start;
                        log.info("流式请求完成: 耗时={}ms", elapsed);
                        future.complete(null);
                    }
                });

        try {
            future.join();
        } catch (Exception e) {
            log.error("流式请求异常", e);
            throw new RuntimeException("流式请求失败", e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
