package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.dto.AgentResponse;
import com.deepseek.demo.dto.ConfirmationPoint;
import com.deepseek.demo.store.ConfirmationStore;
import com.deepseek.demo.store.IConversationStore;
import com.deepseek.demo.util.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.*;
import java.util.stream.Collectors;

/**
 * ReAct 循环引擎，负责 agentLoop 核心逻辑、工具自动匹配、确认点创建。
 */
@Component
public class ReActEngine {

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);

    private static final int MAX_ITERATIONS = 10;

    private static final class KeywordRule {
        final String domain;
        final String[] keywords;
        final String toolName;
        KeywordRule(String domain, String[] keywords, String toolName) {
            this.domain = domain;
            this.keywords = keywords;
            this.toolName = toolName;
        }
    }
    private static final List<KeywordRule> TOOL_KEYWORD_RULES = List.of(
            new KeywordRule("FINANCE", new String[]{"FINANCE", "热门", "行情", "涨跌", "推荐", "好股票", "牛股"}, "query_hot_stocks"),
            new KeywordRule("FINANCE", new String[]{"FINANCE", "持仓", "持有", "市值", "我的股票", "账户"}, "query_portfolio"),
            new KeywordRule("FINANCE", new String[]{"FINANCE", "交易记录", "历史", "买卖记录"}, "query_trade_history"),
            new KeywordRule("FINANCE", new String[]{"FINANCE", "净值", "估值", "价格", "代码"}, "query_stock_nav"),
            new KeywordRule("FINANCE", new String[]{"FINANCE", "买入", "购买", "建仓"}, "buy_stock"),
            new KeywordRule("FINANCE", new String[]{"FINANCE", "卖出", "清仓"}, "sell_stock")
    );

    private final ILlmService deepSeekService;
    private final IToolRegistry toolRegistry;
    private final IConversationStore conversationStore;
    private final ConfirmationStore confirmationStore;
    private final ObjectMapper objectMapper;
    private final CapabilityGuard capabilityGuard;
    private final FrequencyTracker frequencyTracker;

    public ReActEngine(ILlmService deepSeekService,
                       IToolRegistry toolRegistry,
                       IConversationStore conversationStore,
                       ConfirmationStore confirmationStore,
                       ObjectMapper objectMapper,
                       CapabilityGuard capabilityGuard,
                       FrequencyTracker frequencyTracker) {
        this.deepSeekService = deepSeekService;
        this.toolRegistry = toolRegistry;
        this.conversationStore = conversationStore;
        this.confirmationStore = confirmationStore;
        this.objectMapper = objectMapper;
        this.capabilityGuard = capabilityGuard;
        this.frequencyTracker = frequencyTracker;
    }

    // ═══════════════════════════════════════════
    //  ReAct Loop
    // ═══════════════════════════════════════════

    public AgentResponse agentLoop(String conversationId, List<ChatMessage> messages) {
        List<ToolSpecification> schemas = conversationStore.getSelectedToolSpecifications(conversationId);
        return agentLoop(conversationId, messages, schemas != null ? schemas : toolRegistry.toToolSpecifications());
    }

    public AgentResponse agentLoop(String conversationId, List<ChatMessage> messages,
                                    List<ToolSpecification> toolSchemas) {
        boolean planConfirmed = conversationStore.getPlanConfirmed(conversationId);

        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            List<String> toolNames = toolSchemas.stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toList());
            log.info("ReAct 循环启动，携带工具: {}", toolNames);
        }

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            conversationStore.saveCheckpoint(conversationId, messages, i);

            Response<AiMessage> response;
            LlmContext.setConversationId(conversationId);
            try {
                response = deepSeekService.chatWithTools(messages, toolSchemas);
            } catch (Exception e) {
                log.error("LLM API 调用失败(第{}轮)", i, e);
                return AgentResponse.error(AgentFallback.apiUnavailable());
            } finally {
                LlmContext.clear();
            }

            if (response == null || response.content() == null) {
                log.error("LLM API 返回空响应(第{}轮)", i);
                return AgentResponse.error(AgentFallback.modelResponseInvalid());
            }

            AiMessage aiMessage = response.content();
            List<ToolExecutionRequest> toolCalls = aiMessage.toolExecutionRequests();
            messages.add(aiMessage);

            if (toolCalls == null || toolCalls.isEmpty()) {
                if (i == 0 && toolSchemas != null && !toolSchemas.isEmpty()) {
                    ToolExecutionRequest matched = autoMatchTool(messages, toolSchemas);
                    if (matched != null) {
                        // 如果该工具刚执行过（结果已在消息中），跳过自动匹配
                        boolean alreadyExecuted = messages.stream()
                                .anyMatch(m -> m instanceof ToolExecutionResultMessage
                                        && ((ToolExecutionResultMessage) m).toolName().equals(matched.name()));
                        if (alreadyExecuted) {
                            log.info("工具 {} 已执行过，跳过自动匹配", matched.name());
                            conversationStore.clearCheckpoint(conversationId);
                            String content = aiMessage.text();
                            return AgentResponse.done(content != null ? content : "");
                        }

                        log.info("LLM 未调用工具，自动匹配执行: {}", matched.name());
                        messages.remove(messages.size() - 1);
                        if (!planConfirmed) {
                            return createPlanConfirmation(conversationId, messages, List.of(matched));
                        }
                        List<ToolExecutionRequest> matchedCalls = List.of(matched);
                        AiMessage assistantWithToolCalls = AiMessage.from(matchedCalls);
                        messages.add(assistantWithToolCalls);
                        toolCalls = matchedCalls;
                    } else {
                        conversationStore.clearCheckpoint(conversationId);
                        String content = aiMessage.text();
                        log.info("LLM 未调用工具且无自动匹配: {}", content != null ? StringUtils.truncate(content, 80) : "空");
                        return AgentResponse.done(content != null ? content : "");
                    }
                } else {
                    conversationStore.clearCheckpoint(conversationId);
                    String content = aiMessage.text();
                    log.info("LLM 未调用工具，直接返回文本: {}", content != null ? StringUtils.truncate(content, 80) : "空");
                    return AgentResponse.done(content != null ? content : "");
                }
            }

            if (!planConfirmed) {
                return createPlanConfirmation(conversationId, messages, toolCalls);
            }

            // ——已确认计划，逐个执行——
            List<Map<String, Object>> approvedPlan = conversationStore.getApprovedPlan(conversationId);
            int skippedCount = 0;

            for (int t = 0; t < toolCalls.size(); t++) {
                ToolExecutionRequest tc = toolCalls.get(t);
                String toolName = tc.name();

                if (approvedPlan != null && !isInApprovedPlan(toolName, approvedPlan)) {
                    log.warn("工具不在已批准计划中，跳过: tool={}", toolName);
                    skippedCount++;
                    messages.add(SystemMessage.from(
                            "操作 \"" + toolName + "\" 不在已批准计划中，已跳过。"));
                    continue;
                }

                ToolMeta meta;
                try {
                    meta = toolRegistry.getTool(toolName);
                } catch (IllegalArgumentException e) {
                    log.warn("工具不存在，跳过: tool={}", toolName);
                    skippedCount++;
                    messages.add(SystemMessage.from(
                            "工具 \"" + toolName + "\" 不存在，已跳过。"));
                    continue;
                }

                // CapabilityGuard 校验
                String lastUserMessage = messages.stream()
                        .filter(m -> m instanceof UserMessage)
                        .map(m -> ((UserMessage) m).singleText())
                        .reduce((first, second) -> second)
                        .orElse("");
                CapabilityGuard.Result guardResult = capabilityGuard.validate(lastUserMessage, meta);
                if (!guardResult.isPassed()) {
                    log.warn("能力校验不通过: tool={}, reason={}", toolName, guardResult.getReason());
                    skippedCount++;
                    messages.add(SystemMessage.from(
                            "你选择的工具 [" + toolName + "] 可能不适用于当前请求。请选择其他工具。"));
                    continue;
                }

                frequencyTracker.recordCall(toolName);

                if (meta.getAction() == ActionType.WRITE
                        && !toolRegistry.isAutoConfirm(meta.getName())) {
                    List<ToolExecutionRequest> remaining = toolCalls.subList(t + 1, toolCalls.size());
                    log.info("二次确认: tool={}, remaining={}", toolName, remaining.size());
                    return createExecConfirmation(conversationId, messages, tc, remaining);
                }

                try {
                    String result = toolRegistry.execute(tc);
                    messages.add(ToolExecutionResultMessage.from(tc, result));
                    log.debug("工具执行成功: tool={}", toolName);
                } catch (Exception e) {
                    log.error("工具执行失败: tool={}", toolName, e);
                    messages.add(new ToolExecutionResultMessage(tc.id(), toolName,
                            AgentFallback.toolExecutionFailed(toolName, e.getMessage())));
                    skippedCount++;
                }
            }

            if (skippedCount == toolCalls.size() && skippedCount > 0) {
                conversationStore.clearCheckpoint(conversationId);
                return AgentResponse.done(AgentFallback.noSuitableTool());
            }
        }

        log.warn("ReAct 循环达到最大迭代次数: conversationId={}", conversationId);
        conversationStore.clearCheckpoint(conversationId);
        return AgentResponse.done(AgentFallback.maxIterationsReached());
    }

    // ═══════════════════════════════════════════
    //  Tool Auto-Match
    // ═══════════════════════════════════════════

    private ToolExecutionRequest autoMatchTool(List<ChatMessage> messages, List<ToolSpecification> toolSchemas) {
        String userMsg = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof UserMessage) {
                userMsg = ((UserMessage) msg).singleText();
                if (userMsg != null && !userMsg.isBlank()) break;
            }
        }
        if (userMsg == null || userMsg.isBlank()) return null;

        for (KeywordRule rule : TOOL_KEYWORD_RULES) {
            String toolName = rule.toolName;
            boolean toolAvailable = toolSchemas.stream()
                    .anyMatch(s -> toolName.equals(s.name()));
            if (!toolAvailable) continue;

            boolean anyMatch = false;
            for (int k = 1; k < rule.keywords.length; k++) {
                if (userMsg.contains(rule.keywords[k])) {
                    anyMatch = true;
                    break;
                }
            }
            if (anyMatch) {
                log.info("自动匹配工具: tool={}, userMsg={}", toolName, StringUtils.truncate(userMsg, 50));
                return ToolExecutionRequest.builder()
                        .id(UUID.randomUUID().toString())
                        .name(toolName)
                        .arguments("{}")
                        .build();
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════
    //  Confirmation Points
    // ═══════════════════════════════════════════

    private AgentResponse createPlanConfirmation(String conversationId,
                                                  List<ChatMessage> messages,
                                                  List<ToolExecutionRequest> toolCalls) {
        conversationStore.saveMessages(conversationId, messages);

        List<Map<String, Object>> plan = new ArrayList<>();
        for (ToolExecutionRequest tc : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", tc.name());
            item.put("action", "READ");

            try {
                ToolMeta meta = toolRegistry.getTool(tc.name());
                item.put("action", meta.getAction() == ActionType.WRITE ? "WRITE" : "READ");
            } catch (IllegalArgumentException ignored) {
                // tool not found, keep READ
            }

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = objectMapper.readValue(tc.arguments(), Map.class);
                item.put("args", args);
            } catch (Exception e) {
                item.put("args", Collections.singletonMap("raw", tc.arguments()));
            }
            plan.add(item);
        }

        String confirmationId = confirmationStore.createPlanConfirmation(
                conversationId, plan);

        long readCount = plan.stream().filter(p -> "READ".equals(p.get("action"))).count();
        long writeCount = plan.stream().filter(p -> "WRITE".equals(p.get("action"))).count();

        ConfirmationPoint cp = new ConfirmationPoint();
        cp.setConfirmationId(confirmationId);
        cp.setConversationId(conversationId);
        cp.setType("plan");
        cp.setPlan(plan);
        cp.setSummary("共 " + plan.size() + " 项操作：" + readCount + " 项查询 + " + writeCount + " 项写入");
        cp.setCreatedAt(System.currentTimeMillis());
        cp.setReasoning("");

        log.info("创建 plan 确认点: id={}, planSize={}", confirmationId, plan.size());
        return AgentResponse.waitConfirm(cp);
    }

    private AgentResponse createExecConfirmation(String conversationId,
                                                  List<ChatMessage> messages,
                                                  ToolExecutionRequest toolCall,
                                                  List<ToolExecutionRequest> pendingToolCalls) {
        conversationStore.saveMessages(conversationId, messages);

        String confirmationId = confirmationStore.createExecConfirmation(
                conversationId, toolCall, pendingToolCalls);

        ConfirmationPoint cp = new ConfirmationPoint();
        cp.setConfirmationId(confirmationId);
        cp.setConversationId(conversationId);
        cp.setType("exec");
        cp.setTool(toolCall.name());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(toolCall.arguments(), Map.class);
            cp.setArgs(args);
        } catch (Exception e) {
            Map<String, Object> rawArgs = new LinkedHashMap<>();
            rawArgs.put("raw", toolCall.arguments());
            cp.setArgs(rawArgs);
        }

        cp.setReasoning("即将执行写操作，请确认");
        cp.setCreatedAt(System.currentTimeMillis());

        log.info("创建 exec 确认点: id={}, tool={}, pending={}",
                confirmationId, toolCall.name(),
                pendingToolCalls != null ? pendingToolCalls.size() : 0);
        return AgentResponse.waitConfirm(cp);
    }

    // ═══════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════

    private boolean isInApprovedPlan(String toolName, List<Map<String, Object>> plan) {
        return plan.stream().anyMatch(item -> toolName.equals(item.get("tool")));
    }

    public String formatPlan(List<Map<String, Object>> plan) {
        if (plan == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plan.size(); i++) {
            Map<String, Object> item = plan.get(i);
            sb.append(i + 1).append(". ");
            sb.append("[").append(item.get("action")).append("] ");
            sb.append(item.get("tool"));
            if (item.get("args") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) item.get("args");
                if (!args.isEmpty()) {
                    sb.append(" ").append(args);
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
