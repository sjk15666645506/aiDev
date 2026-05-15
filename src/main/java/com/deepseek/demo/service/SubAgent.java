package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import com.deepseek.demo.dto.DeepSeekChatResponse;
import com.deepseek.demo.dto.Message;
import com.deepseek.demo.dto.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 子 Agent，在指定领域内独立执行任务。
 * <p>
 * 被 {@link MultiAgentTools#delegateTask} 调用，运行自己的 mini ReAct 循环：
 * <ol>
 *   <li>使用限定于指定领域的工具列表</li>
 *   <li>最多迭代 3 轮，自动执行所有工具调用（无确认流程）</li>
 *   <li>返回最终结果字符串给主 Agent</li>
 * </ol>
 * <p>
 * 无 Redis 持久化、无确认流程——子 Agent 只管执行并返回结果。
 */
@Component
public class SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);

    /** 子 Agent 最大迭代轮数 */
    private static final int MAX_ITERATIONS = 3;

    private final DeepSeekService deepSeekService;
    private final ToolRegistry toolRegistry;

    public SubAgent(DeepSeekService deepSeekService, ToolRegistry toolRegistry) {
        this.deepSeekService = deepSeekService;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 在指定领域内执行任务，返回结果字符串。
     *
     * @param taskDescription 子任务描述（由主 Agent 的 LLM 生成）
     * @param domainName      目标领域名称（大写，如 "FINANCE"）
     * @return 子 Agent 执行结果
     */
    public String execute(String taskDescription, String domainName) {
        log.info("子 Agent 启动: domain={}, task={}",
                domainName, truncate(taskDescription, 100));

        // 1. 解析领域
        ToolDomain domain;
        try {
            domain = ToolDomain.valueOf(domainName.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知领域: {}, 使用 SEARCH", domainName);
            domain = ToolDomain.SEARCH;
        }

        // 2. 获取该领域的工具
        List<ToolMeta> domainTools = toolRegistry.getByDomain(domain);
        List<Map<String, Object>> toolSchemas = toolRegistry.toJsonSchema(domainTools);
        log.info("子 Agent 可用的工具: count={}, tools={}",
                domainTools.size(),
                domainTools.stream().map(ToolMeta::getName).collect(Collectors.toList()));

        if (domainTools.isEmpty()) {
            log.warn("领域 {} 无可用工具", domain);
            return "领域 " + domain.getDisplayName() + " 没有可用的工具，无法执行该任务。";
        }

        // 3. 构建消息
        String systemPrompt = "你是一个领域专家 Agent，专注于 " + domain.getDisplayName() + " 领域。"
                + "请使用可用的工具完成以下子任务。"
                + "调用工具后根据返回结果继续工作，直到任务完成。"
                + "完成后给出最终结论，不需要询问用户确认。";

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        messages.add(new Message("user", taskDescription));

        // 4. mini ReAct 循环
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.debug("子 Agent 第 {} 轮调用 LLM", i + 1);

            DeepSeekChatResponse response;
            try {
                response = deepSeekService.chatWithTools(messages, toolSchemas);
            } catch (Exception e) {
                log.error("子 Agent LLM 调用失败", e);
                return "子任务执行失败: " + e.getMessage();
            }

            if (response == null || response.getChoices() == null
                    || response.getChoices().isEmpty()) {
                log.warn("子 Agent LLM 返回空响应");
                break;
            }

            Message responseMessage = response.getChoices().get(0).getMessage();
            List<ToolCall> toolCalls = response.getChoices().get(0).getToolCalls();
            messages.add(responseMessage);

            // 无 tool_calls → 返回最终回答
            if (toolCalls == null || toolCalls.isEmpty()) {
                String content = responseMessage.getContent();
                log.info("子 Agent 完成: domain={}", domain);
                return content != null ? content : "";
            }

            // 执行所有 tool_calls
            for (ToolCall tc : toolCalls) {
                try {
                    String result = toolRegistry.execute(tc);
                    messages.add(new Message("tool", result, tc.getId()));
                    log.debug("子 Agent 工具执行成功: tool={}", tc.getFunction().getName());
                } catch (Exception e) {
                    log.error("子 Agent 工具执行失败: tool={}", tc.getFunction().getName(), e);
                    messages.add(new Message("tool",
                            "执行异常: " + e.getMessage(), tc.getId()));
                }
            }
        }

        log.warn("子 Agent 达到最大迭代次数: domain={}", domain);
        return "子任务未完全执行，已达最大处理轮次。以下是当前进展:\n"
                + getLastAssistantContent(messages);
    }

    /** 获取最后一条 assistant 消息的内容 */
    private String getLastAssistantContent(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(messages.get(i).getRole())) {
                String c = messages.get(i).getContent();
                if (c != null && !c.isEmpty()) return c;
            }
        }
        return "";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
