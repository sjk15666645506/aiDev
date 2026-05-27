package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import com.deepseek.demo.util.StringUtils;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);

    private static final int MAX_ITERATIONS = 3;

    private final ILlmService deepSeekService;
    private final IToolRegistry toolRegistry;

    public SubAgent(ILlmService deepSeekService, IToolRegistry toolRegistry) {
        this.deepSeekService = deepSeekService;
        this.toolRegistry = toolRegistry;
    }

    public String execute(String taskDescription, String domainName) {
        log.info("子 Agent 启动: domain={}, task={}",
                domainName, StringUtils.truncate(taskDescription, 100));

        ToolDomain domain;
        try {
            domain = ToolDomain.valueOf(domainName.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知领域: {}, 使用 SEARCH", domainName);
            domain = ToolDomain.SEARCH;
        }

        List<ToolMeta> domainTools = toolRegistry.getByDomain(domain);
        List<ToolSpecification> toolSchemas = toolRegistry.toToolSpecifications(domainTools);
        log.info("子 Agent 可用的工具: count={}, tools={}",
                domainTools.size(),
                domainTools.stream().map(ToolMeta::getName).collect(Collectors.toList()));

        if (domainTools.isEmpty()) {
            log.warn("领域 {} 无可用工具", domain);
            return "领域 " + domain.getDisplayName() + " 没有可用的工具，无法执行该任务。";
        }

        String systemPrompt = "你是一个领域专家 Agent，专注于 " + domain.getDisplayName() + " 领域。"
                + "请使用可用的工具完成以下子任务。"
                + "调用工具后根据返回结果继续工作，直到任务完成。"
                + "完成后给出最终结论，不需要询问用户确认。";

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(taskDescription));

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.debug("子 Agent 第 {} 轮调用 LLM", i + 1);

            Response<AiMessage> response;
            try {
                response = deepSeekService.chatWithTools(messages, toolSchemas);
            } catch (Exception e) {
                log.error("子 Agent LLM 调用失败", e);
                return "子任务执行失败: " + e.getMessage();
            }

            if (response == null || response.content() == null) {
                log.warn("子 Agent LLM 返回空响应");
                break;
            }

            AiMessage aiMessage = response.content();
            List<ToolExecutionRequest> toolCalls = aiMessage.toolExecutionRequests();
            messages.add(aiMessage);

            if (toolCalls == null || toolCalls.isEmpty()) {
                String content = aiMessage.text();
                log.info("子 Agent 完成: domain={}", domain);
                return content != null ? content : "";
            }

            for (ToolExecutionRequest tc : toolCalls) {
                try {
                    String result = toolRegistry.execute(tc);
                    messages.add(ToolExecutionResultMessage.from(tc, result));
                    log.debug("子 Agent 工具执行成功: tool={}", tc.name());
                } catch (Exception e) {
                    log.error("子 Agent 工具执行失败: tool={}", tc.name(), e);
                    messages.add(new ToolExecutionResultMessage(tc.id(), tc.name(),
                            "执行异常: " + e.getMessage()));
                }
            }
        }

        log.warn("子 Agent 达到最大迭代次数: domain={}", domain);
        return "子任务未完全执行，已达最大处理轮次。以下是当前进展:\n"
                + getLastAssistantContent(messages);
    }

    private String getLastAssistantContent(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage) {
                String c = ((AiMessage) messages.get(i)).text();
                if (c != null && !c.isEmpty()) return c;
            }
        }
        return "";
    }
}
