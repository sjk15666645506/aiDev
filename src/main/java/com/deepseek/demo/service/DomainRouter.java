package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Layer 1: 领域路由器。
 * <p>
 * 通过一次轻量 LLM 调用对用户意图进行领域分类。
 * 使用独立的快速调用（temperature=0），仅消耗几十个 token，
 * 将结果限定到 1-2 个领域，大幅缩小后续 LLM 的工具选择范围。
 */
@Component
public class DomainRouter {

    private static final Logger log = LoggerFactory.getLogger(DomainRouter.class);

    private final DeepSeekService deepSeekService;
    private final ObjectMapper objectMapper;

    public DomainRouter(DeepSeekService deepSeekService, ObjectMapper objectMapper) {
        this.deepSeekService = deepSeekService;
        this.objectMapper = objectMapper;
    }

    /**
     * 对用户消息进行意图分类，返回最匹配的领域。
     * 分类失败时降级为 SEARCH（最通用的领域）。
     */
    public ToolDomain classify(String userMessage) {
        try {
            String systemPrompt = buildClassifierPrompt();
            String response = deepSeekService.chatWithSystem(systemPrompt, userMessage);

            if (response == null || response.isBlank()) {
                log.warn("分类器返回空，降级为 SEARCH");
                return ToolDomain.SEARCH;
            }

            String domainStr = response.trim().toUpperCase();
            return ToolDomain.valueOf(domainStr);

        } catch (Exception e) {
            log.warn("意图分类失败，降级为 SEARCH: {}", e.getMessage());
            return ToolDomain.SEARCH;
        }
    }

    /**
     * 构建领域分类器的系统提示词。
     */
    private String buildClassifierPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个领域分类器。请判断用户请求属于以下哪个领域，只返回领域名称本身，不要返回其他内容。\n\n");

        for (ToolDomain domain : ToolDomain.values()) {
            sb.append("- ").append(domain.name())
              .append(": ").append(domain.getDisplayName())
              .append("（").append(domain.getKeywords()).append("）\n");
        }

        sb.append("\n示例：\n");
        sb.append("用户：帮我查一下张三的工单\n");
        sb.append("返回：TASK_MANAGEMENT\n\n");
        sb.append("用户：把这段代码提交到仓库\n");
        sb.append("返回：CODE_REPOSITORY\n\n");
        sb.append("如果都不匹配，返回：SEARCH");

        return sb.toString();
    }
}
