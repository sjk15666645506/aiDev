package com.deepseek.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class GeneralRagService {

    private static final Logger log = LoggerFactory.getLogger(GeneralRagService.class);

    private final VectorService vectorService;
    private final DeepSeekService deepSeekService;

    /**
     * 构造 GeneralRagService
     *
     * @param vectorService   向量检索服务（Qdrant + Meilisearch 混合检索）
     * @param deepSeekService DeepSeek LLM 调用服务
     */
    public GeneralRagService(VectorService vectorService, DeepSeekService deepSeekService) {
        this.vectorService = vectorService;
        this.deepSeekService = deepSeekService;
    }

    /**
     * 通用知识库问答（默认返回 5 条）
     *
     * @param question 用户问题
     * @return LLM 回答文本
     */
    public String ragChat(String question) {
        return ragChat(question, 5);
    }

    /**
     * 通用知识库问答（非流式）
     *
     * @param question 用户问题
     * @param limit    检索返回的最大结果数
     * @return LLM 回答文本
     */
    public String ragChat(String question, int limit) {
        log.info("通用知识库请求: question={}, limit={}", truncate(question, 50), limit);

        List<Map<String, Object>> contexts = vectorService.searchDocsWithFullContent(question, limit);
        contexts = filterAndTruncate(contexts);
        String systemPrompt = buildSystemPrompt(contexts);

        log.info("通用知识库发送给DeepSeek: contextCount={}", contexts.size());
        return deepSeekService.chatWithSystem(systemPrompt, question);
    }

    /**
     * 通用知识库流式问答（默认返回 5 条）
     *
     * @param question  用户问题
     * @param onContent 逐字符回调接收 LLM 输出
     */
    public void ragChatStream(String question, Consumer<String> onContent) {
        ragChatStream(question, 5, onContent);
    }

    /**
     * 通用知识库流式问答
     *
     * @param question  用户问题
     * @param limit     检索返回的最大结果数
     * @param onContent 逐字符回调接收 LLM 输出
     */
    public void ragChatStream(String question, int limit, Consumer<String> onContent) {
        log.info("通用知识库流式请求: question={}, limit={}", truncate(question, 50), limit);

        List<Map<String, Object>> contexts = vectorService.searchDocsWithFullContent(question, limit);
        contexts = filterAndTruncate(contexts);
        String systemPrompt = buildSystemPrompt(contexts);

        log.info("通用知识库流式发送给DeepSeek: contextCount={}", contexts.size());
        deepSeekService.chatStream(systemPrompt + "\n\n用户问题: " + question, onContent);
    }

    /**
     * 过滤低分结果并截断上下文
     */
    private List<Map<String, Object>> filterAndTruncate(List<Map<String, Object>> contexts) {
        if (contexts == null || contexts.isEmpty()) return contexts;
        // RRF 得分范围约 0.006~0.033（见 rrfMerge k=60）
        double SCORE_THRESHOLD = 0.01;
        List<Map<String, Object>> filtered = contexts.stream()
                .filter(ctx -> ((Number) ctx.getOrDefault("score", 0.0)).doubleValue() >= SCORE_THRESHOLD)
                .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            log.warn("所有结果均低于 score 阈值({})，降级为纯 LLM 回答", SCORE_THRESHOLD);
        }
        return VectorService.truncateContexts(filtered, VectorService.MAX_CONTEXT_CHARS);
    }

    /**
     * 构建 RAG 系统提示词，将检索结果拼入上下文
     *
     * @param contexts 检索到的知识片段列表
     * @return 组装后的系统提示词，空上下文时降级为纯 LLM 回答
     */
    private String buildSystemPrompt(List<Map<String, Object>> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "你是一个智能助手，请根据你的知识回答用户的问题。如果不知道，请如实告知。";
        }

        String contextText = contexts.stream()
                .map(ctx -> {
                    String text = (String) ctx.get("text");
                    String source = (String) ctx.get("file_name");
                    if (source != null) {
                        return "[来源: " + source + "]\n" + text;
                    }
                    return text;
                })
                .collect(Collectors.joining("\n\n---\n\n"));

        return "你是一个智能知识库助手。请基于以下参考内容回答用户的问题。" +
                "如果参考内容不足以回答问题，请如实告知。" +
                "请用中文回答。\n\n参考内容：\n" + contextText;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
