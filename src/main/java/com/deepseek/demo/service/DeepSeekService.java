package com.deepseek.demo.service;

import com.deepseek.demo.dto.DeepSeekChatRequest;
import com.deepseek.demo.dto.DeepSeekChatResponse;
import com.deepseek.demo.dto.Message;
import com.deepseek.demo.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class DeepSeekService implements ILlmService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekService.class);

    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    private final String apiKey;

    private final String baseUrl;

    /**
     * 构造 DeepSeekService
     *
     * @param restTemplate 带连接池的 HTTP 客户端
     * @param objectMapper JSON 序列化/反序列化
     * @param apiKey       DeepSeek API 密钥（${deepseek.api-key}）
     * @param baseUrl      DeepSeek API 基础地址（${deepseek.base-url}）
     */
    public DeepSeekService(RestTemplate restTemplate,
                           ObjectMapper objectMapper,
                           @Value("${deepseek.api-key}") String apiKey,
                           @Value("${deepseek.base-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    /**
     * 非流式对话
     *
     * @param userMessage 用户输入文本
     * @return LLM 回复文本
     */
    public String chat(String userMessage) {
        log.info("非流式请求: message={}", StringUtils.truncate(userMessage, 50));
        DeepSeekChatRequest request = new DeepSeekChatRequest();
        request.setMessages(Arrays.asList(new Message(Message.ROLE_USER, userMessage)));
        DeepSeekChatResponse response = chat(request);
        String reply = extractContent(response);
        log.info("非流式响应完成: replyLength={}", reply != null ? reply.length() : 0);
        return reply;
    }

    /**
     * 带系统提示词的非流式对话
     *
     * @param systemPrompt 系统角色设定
     * @param userMessage  用户输入文本
     * @return LLM 回复文本
     */
    public String chatWithSystem(String systemPrompt, String userMessage) {
        log.info("非流式请求(带系统提示): system={}, message={}",
                StringUtils.truncate(systemPrompt, 30), StringUtils.truncate(userMessage, 50));
        List<Message> messages = Arrays.asList(
                new Message(Message.ROLE_SYSTEM, systemPrompt),
                new Message(Message.ROLE_USER, userMessage)
        );
        DeepSeekChatRequest request = new DeepSeekChatRequest();
        request.setMessages(messages);
        DeepSeekChatResponse response = chat(request);
        String reply = extractContent(response);
        log.info("非流式响应完成: replyLength={}", reply != null ? reply.length() : 0);
        return reply;
    }

    /**
     * 底层 HTTP 调用 DeepSeek API
     *
     * @param request 完整请求体（含 model/messages/stream 等）
     * @return DeepSeek API 原始响应
     */
    public DeepSeekChatResponse chat(DeepSeekChatRequest request) {
        String url = baseUrl + "/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<DeepSeekChatRequest> entity = new HttpEntity<>(request, headers);

        log.debug("调用DeepSeek API: url={}, model={}", url, request.getModel());
        long start = System.currentTimeMillis();

        ResponseEntity<DeepSeekChatResponse> response = restTemplate.postForEntity(
                url, entity, DeepSeekChatResponse.class);

        long elapsed = System.currentTimeMillis() - start;
        log.debug("DeepSeek API响应: status={},耗时={}ms", response.getStatusCode(), elapsed);
        return response.getBody();
    }

    /**
     * 带 function calling 工具的非流式对话。
     * <p>
     * 在原有对话基础上追加 tools 参数，使 LLM 可以在适当时机调用预定义的函数。
     * 内置重试和错误处理：
     * <ul>
     *   <li>HTTP 429 限流：等待 2s 后重试，最多 2 次</li>
     *   <li>HTTP 401 鉴权：不重试，直接返回错误</li>
     *   <li>网络超时：重试 1 次</li>
     * </ul>
     *
     * @param messages 消息列表（含 system/user/assistant/tool 角色）
     * @param tools    DeepSeek function calling 的工具定义 JSON Schema 列表
     * @return DeepSeek API 原始响应（含 tool_calls）
     * @throws RuntimeException 所有重试失败后抛出
     */
    public DeepSeekChatResponse chatWithTools(List<Message> messages,
                                               List<Map<String, Object>> tools) {
        return chatWithTools(messages, tools, "auto");
    }

    public DeepSeekChatResponse chatWithTools(List<Message> messages,
                                               List<Map<String, Object>> tools,
                                               String toolChoice) {
        String url = baseUrl + "/v1/chat/completions";

        DeepSeekChatRequest request = new DeepSeekChatRequest(messages);
        request.setTools(tools);
        request.setToolChoice(toolChoice != null ? toolChoice : "auto");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<DeepSeekChatRequest> entity = new HttpEntity<>(request, headers);

        int maxAttempts = 2;
        int attempt = 0;

        while (attempt < maxAttempts) {
            attempt++;
            try {
                log.info("调用 DeepSeek API(带 tools): url={}, toolsCount={}, toolChoice={}, attempt={}/{}",
                        url, tools != null ? tools.size() : 0, request.getToolChoice(), attempt, maxAttempts);

                if (log.isDebugEnabled()) {
                    try {
                        String requestJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
                        log.debug("DeepSeek API 请求体:\n{}", requestJson);
                    } catch (Exception je) {
                        log.debug("请求体序列化失败", je);
                    }
                }

                long start = System.currentTimeMillis();
                ResponseEntity<DeepSeekChatResponse> response = restTemplate.postForEntity(
                        url, entity, DeepSeekChatResponse.class);
                long elapsed = System.currentTimeMillis() - start;

                DeepSeekChatResponse body = response.getBody();
                boolean hasToolCalls = false;
                if (body != null && body.getChoices() != null && !body.getChoices().isEmpty()) {
                    DeepSeekChatResponse.Choice c = body.getChoices().get(0);
                    hasToolCalls = c.getToolCalls() != null
                            || (c.getMessage() != null && c.getMessage().getToolCalls() != null);
                }

                log.info("DeepSeek API(带 tools)响应: status={}, 耗时={}ms, hasToolCalls={}",
                        response.getStatusCode(), elapsed, hasToolCalls);

                if (log.isDebugEnabled() && body != null) {
                    try {
                        String responseJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
                        log.debug("DeepSeek API 响应体: model={}, finish_reason={}, body={}",
                                body.getModel(),
                                body.getChoices() != null && !body.getChoices().isEmpty()
                                        ? body.getChoices().get(0).getFinishReason() : "N/A",
                                responseJson);
                    } catch (Exception je) {
                        log.debug("DeepSeek API 响应体序列化失败", je);
                    }
                }

                return body;

            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getRawStatusCode() == 429) {
                    log.warn("DeepSeek API 限流(429), 等待重试: attempt={}/{}", attempt, maxAttempts);
                    if (attempt < maxAttempts) {
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        continue;
                    }
                    throw new RuntimeException("请求过于频繁，请稍后再试");
                } else if (e.getRawStatusCode() == 401) {
                    log.error("DeepSeek API 鉴权失败(401)");
                    throw new RuntimeException("API 认证失败");
                }
                String responseBody = e.getResponseBodyAsString();
                log.error("DeepSeek API HTTP 错误: status={}, body={}", e.getRawStatusCode(), responseBody);
                if (attempt < maxAttempts) continue;
                throw new RuntimeException("服务异常");

            } catch (org.springframework.web.client.ResourceAccessException e) {
                log.warn("DeepSeek API 网络错误, 重试: attempt={}/{}", attempt, maxAttempts);
                if (attempt < maxAttempts) continue;
                throw new RuntimeException("服务暂时不可用，请稍后再试");
            }
        }

        throw new RuntimeException("服务暂时不可用，请稍后再试");
    }

    /**
     * 流式对话（SSE）
     *
     * @param userMessage 用户输入文本
     * @param onContent   逐字符/逐块回调接收 LLM 输出
     */
    public void chatStream(String userMessage, Consumer<String> onContent) {
        String url = baseUrl + "/v1/chat/completions";

        DeepSeekChatRequest request = new DeepSeekChatRequest();
        request.setMessages(Arrays.asList(new Message(Message.ROLE_USER, userMessage)));
        request.setStream(true);

        log.info("流式请求开始: message={}", StringUtils.truncate(userMessage, 50));
        long start = System.currentTimeMillis();

        restTemplate.execute(url, HttpMethod.POST,
                httpRequest -> {
                    httpRequest.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    httpRequest.getHeaders().setBearerAuth(apiKey);
                    try (OutputStream os = httpRequest.getBody()) {
                        objectMapper.writeValue(os, request);
                    }
                },
                response -> {
                    int totalChars = 0;
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if ("[DONE]".equals(data) || data.isEmpty()) continue;
                                JsonNode root = objectMapper.readTree(data);
                                JsonNode content = root.path("choices").get(0).path("delta").path("content");
                                if (!content.isMissingNode() && content.isTextual()) {
                                    String text = content.asText();
                                    totalChars += text.length();
                                    log.debug("流式收到chunk: content={}", StringUtils.truncate(text, 30));
                                    onContent.accept(text);
                                }
                            }
                        }
                    }
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("流式请求完成: totalChars={}, 耗时={}ms", totalChars, elapsed);
                    return null;
                });
    }

    /**
     * 从 DeepSeek API 响应中提取首个 choice 的文本内容
     *
     * @param response API 原始响应
     * @return 回复文本，无结果时返回 null
     */
    private String extractContent(DeepSeekChatResponse response) {
        if (response != null
                && response.getChoices() != null
                && !response.getChoices().isEmpty()
                && response.getChoices().get(0).getMessage() != null) {
            return response.getChoices().get(0).getMessage().getContent();
        }
        return null;
    }

}
