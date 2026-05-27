package com.deepseek.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
@Primary
public class LangChain4jLlmService implements ILlmService {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jLlmService.class);

    private final OpenAiChatModel chatModel;
    private final OpenAiStreamingChatModel streamingChatModel;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final Map<String, List<String>> reasoningContentCache = new ConcurrentHashMap<>();

    public LangChain4jLlmService(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.base-url}") String baseUrl,
            @Value("${deepseek.model}") String modelName,
            RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl + "/v1")
                .modelName(modelName)
                .timeout(Duration.ofSeconds(30))
                .maxRetries(2)
                .logRequests(true)
                .logResponses(true)
                .build();
        this.streamingChatModel = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl + "/v1")
                .modelName(modelName)
                .timeout(Duration.ofSeconds(60))
                .build();
        log.info("LangChain4jLlmService initialized: baseUrl={}/v1, model={}", baseUrl, modelName);
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
        String conversationId = LlmContext.getConversationId();
        log.info("调用 LLM(带 tools): toolsCount={}, messagesCount={}, conversationId={}",
                tools != null ? tools.size() : 0, messages.size(), conversationId);

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> requestBody = buildRequestBody(messages, tools);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String url = baseUrl + "/v1/chat/completions";
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("LLM API 返回空 choices");
            }
            Map<String, Object> choice = choices.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choice.get("message");

            // Store reasoning_content per conversation
            String reasoningContent = (String) message.get("reasoning_content");
            if (conversationId != null) {
                reasoningContentCache.computeIfAbsent(conversationId, k -> new ArrayList<>())
                        .add(reasoningContent != null ? reasoningContent : "");
            }

            AiMessage aiMessage = parseResponseMessage(message);
            String finishReason = (String) choice.get("finish_reason");

            long elapsed = System.currentTimeMillis() - start;
            boolean hasToolCalls = aiMessage.hasToolExecutionRequests();
            log.info("LLM 响应: 耗时={}ms, hasToolCalls={}, finishReason={}",
                    elapsed, hasToolCalls, finishReason);

            return Response.from(aiMessage, null, parseFinishReason(finishReason));
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            throw new RuntimeException("服务暂时不可用，请稍后再试", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildRequestBody(List<ChatMessage> messages,
                                                  List<ToolSpecification> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);

        // 转换消息列表，并向 AiMessage 注入 reasoning_content
        List<Map<String, Object>> messageList = new ArrayList<>();
        int aiIndex = 0;
        for (ChatMessage msg : messages) {
            Map<String, Object> map = chatMessageToMap(msg);
            if (msg instanceof AiMessage) {
                String conversationId = LlmContext.getConversationId();
                if (conversationId != null) {
                    List<String> reasoningList = reasoningContentCache.get(conversationId);
                    if (reasoningList != null && aiIndex < reasoningList.size()) {
                        String rc = reasoningList.get(aiIndex);
                        if (rc != null && !rc.isEmpty()) {
                            map.put("reasoning_content", rc);
                        }
                    }
                }
                aiIndex++;
            }
            messageList.add(map);
        }
        body.put("messages", messageList);

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", toolsToOpenAiTools(tools));
        }

        return body;
    }

    private Map<String, Object> chatMessageToMap(ChatMessage msg) {
        if (msg instanceof SystemMessage) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", "system");
            map.put("content", ((SystemMessage) msg).text());
            return map;
        }
        if (msg instanceof UserMessage) {
            UserMessage um = (UserMessage) msg;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", "user");
            map.put("content", um.singleText() != null ? um.singleText() : "");
            if (um.name() != null) {
                map.put("name", um.name());
            }
            return map;
        }
        if (msg instanceof AiMessage) {
            AiMessage am = (AiMessage) msg;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", "assistant");
            map.put("content", am.text() != null ? am.text() : "");
            if (am.hasToolExecutionRequests()) {
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                    Map<String, Object> tc = new LinkedHashMap<>();
                    tc.put("id", req.id() != null ? req.id() : "");
                    tc.put("type", "function");
                    Map<String, Object> func = new LinkedHashMap<>();
                    func.put("name", req.name() != null ? req.name() : "");
                    func.put("arguments", req.arguments() != null ? req.arguments() : "{}");
                    tc.put("function", func);
                    toolCalls.add(tc);
                }
                map.put("tool_calls", toolCalls);
            }
            return map;
        }
        if (msg instanceof ToolExecutionResultMessage) {
            ToolExecutionResultMessage trm = (ToolExecutionResultMessage) msg;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", "tool");
            map.put("content", trm.text() != null ? trm.text() : "");
            map.put("tool_call_id", trm.id() != null ? trm.id() : "");
            if (trm.toolName() != null) {
                map.put("name", trm.toolName());
            }
            return map;
        }
        throw new IllegalArgumentException("Unknown ChatMessage type: " + msg.getClass().getName());
    }

    private List<Map<String, Object>> toolsToOpenAiTools(List<ToolSpecification> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolSpecification spec : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", spec.name());
            if (spec.description() != null && !spec.description().isEmpty()) {
                function.put("description", spec.description());
            }
            ToolParameters params = spec.parameters();
            if (params != null && params.properties() != null && !params.properties().isEmpty()) {
                Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("type", params.type() != null ? params.type() : "object");
                parameters.put("properties", params.properties());
                parameters.put("required", params.required() != null
                        ? params.required() : Collections.emptyList());
                function.put("parameters", parameters);
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            result.add(tool);
        }
        return result;
    }

    private static AiMessage parseResponseMessage(Map<String, Object> message) {
        String content = (String) message.get("content");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<ToolExecutionRequest> requests = new ArrayList<>();
            for (Map<String, Object> tc : toolCalls) {
                @SuppressWarnings("unchecked")
                Map<String, Object> func = (Map<String, Object>) tc.get("function");
                String id = (String) tc.get("id");
                String name = func != null ? (String) func.get("name") : "";
                String arguments = func != null ? (String) func.get("arguments") : "{}";
                requests.add(ToolExecutionRequest.builder()
                        .id(id != null ? id : "")
                        .name(name != null ? name : "")
                        .arguments(arguments != null ? arguments : "{}")
                        .build());
            }
            if (content != null && !content.isBlank()) {
                return new AiMessage(content, requests);
            }
            return new AiMessage(requests);
        }

        return AiMessage.from(content != null ? content : "");
    }

    private static FinishReason parseFinishReason(String reason) {
        if (reason == null) return FinishReason.STOP;
        switch (reason) {
            case "stop": return FinishReason.STOP;
            case "tool_calls": return FinishReason.TOOL_EXECUTION;
            case "length": return FinishReason.LENGTH;
            default: return FinishReason.STOP;
        }
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
