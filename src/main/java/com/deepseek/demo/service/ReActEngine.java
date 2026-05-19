package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.dto.*;
import com.deepseek.demo.store.ConfirmationStore;
import com.deepseek.demo.store.IConversationStore;
import com.deepseek.demo.util.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public AgentResponse agentLoop(String conversationId, List<Message> messages) {
        List<Map<String, Object>> schemas = conversationStore.getSelectedToolSchemas(conversationId);
        return agentLoop(conversationId, messages, schemas != null ? schemas : toolRegistry.toJsonSchema());
    }

    public AgentResponse agentLoop(String conversationId, List<Message> messages,
                                    List<Map<String, Object>> toolSchemas) {
        boolean planConfirmed = conversationStore.getPlanConfirmed(conversationId);

        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            List<String> toolNames = toolSchemas.stream()
                    .map(s -> { Object fn = s.get("function"); return fn instanceof Map ? (String)((Map<?,?>)fn).get("name") : "?"; })
                    .collect(Collectors.toList());
            log.info("ReAct 循环启动，携带工具: {}", toolNames);
        }

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            conversationStore.saveCheckpoint(conversationId, messages, i);

            DeepSeekChatResponse response;
            try {
                response = deepSeekService.chatWithTools(messages, toolSchemas, "auto");
            } catch (Exception e) {
                log.error("DeepSeek API 调用失败(第{}轮)", i, e);
                return AgentResponse.error(AgentFallback.apiUnavailable());
            }

            if (response == null || response.getChoices() == null
                    || response.getChoices().isEmpty()) {
                log.error("DeepSeek API 返回空响应(第{}轮)", i);
                return AgentResponse.error(AgentFallback.modelResponseInvalid());
            }

            DeepSeekChatResponse.Choice choice = response.getChoices().get(0);
            Message responseMessage = choice.getMessage();
            List<ToolCall> toolCalls = DeepSeekResponseNormalizer.normalizeToolCalls(choice);
            messages.add(responseMessage);

            if (toolCalls == null || toolCalls.isEmpty()) {
                if (i == 0 && toolSchemas != null && !toolSchemas.isEmpty()) {
                    ToolCall matched = autoMatchTool(messages, toolSchemas);
                    if (matched != null) {
                        log.info("LLM 未调用工具，自动匹配执行: {}", matched.getFunction().getName());
                        messages.remove(messages.size() - 1);
                        if (!planConfirmed) {
                            return createPlanConfirmation(conversationId, messages, List.of(matched));
                        }
                        List<ToolCall> matchedCalls = List.of(matched);
                        Message assistantWithToolCalls = new Message(Message.ROLE_ASSISTANT, null);
                        assistantWithToolCalls.setToolCalls(matchedCalls);
                        messages.add(assistantWithToolCalls);
                        toolCalls = matchedCalls;
                    } else {
                        conversationStore.clearCheckpoint(conversationId);
                        String content = responseMessage.getContent();
                        log.info("LLM 未调用工具且无自动匹配: {}", content != null ? StringUtils.truncate(content, 80) : "空");
                        return AgentResponse.done(content != null ? content : "");
                    }
                } else {
                    conversationStore.clearCheckpoint(conversationId);
                    String content = responseMessage.getContent();
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
                ToolCall tc = toolCalls.get(t);
                String toolName = tc.getFunction().getName();

                if (approvedPlan != null && !isInApprovedPlan(toolName, approvedPlan)) {
                    log.warn("工具不在已批准计划中，跳过: tool={}", toolName);
                    skippedCount++;
                    messages.add(new Message(Message.ROLE_SYSTEM,
                            "操作 \"" + toolName + "\" 不在已批准计划中，已跳过。"));
                    continue;
                }

                ToolMeta meta = toolRegistry.getTool(toolName);
                if (meta == null) {
                    log.warn("工具不存在，跳过: tool={}", toolName);
                    skippedCount++;
                    messages.add(new Message(Message.ROLE_SYSTEM,
                            "工具 \"" + toolName + "\" 不存在，已跳过。"));
                    continue;
                }

                // Layer 3: CapabilityGuard 校验
                String lastUserMessage = messages.stream()
                        .filter(m -> "user".equals(m.getRole()))
                        .map(Message::getContent)
                        .reduce((first, second) -> second)
                        .orElse("");
                CapabilityGuard.Result guardResult = capabilityGuard.validate(lastUserMessage, meta);
                if (!guardResult.isPassed()) {
                    log.warn("能力校验不通过: tool={}, reason={}", toolName, guardResult.getReason());
                    skippedCount++;
                    messages.add(new Message(Message.ROLE_SYSTEM,
                            "你选择的工具 [" + toolName + "] 可能不适用于当前请求。请选择其他工具。"));
                    continue;
                }

                frequencyTracker.recordCall(toolName);

                if (meta.getAction() == ActionType.WRITE
                        && !toolRegistry.isAutoConfirm(meta.getName())) {
                    List<ToolCall> remaining = toolCalls.subList(t + 1, toolCalls.size());
                    log.info("二次确认: tool={}, remaining={}", toolName, remaining.size());
                    return createExecConfirmation(conversationId, messages, tc, remaining);
                }

                try {
                    String result = toolRegistry.execute(tc);
                    messages.add(new Message(Message.ROLE_TOOL, result, tc.getId()));
                    log.debug("工具执行成功: tool={}", toolName);
                } catch (Exception e) {
                    log.error("工具执行失败: tool={}", toolName, e);
                    messages.add(new Message(Message.ROLE_TOOL,
                            AgentFallback.toolExecutionFailed(toolName, e.getMessage()), tc.getId()));
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

    private ToolCall autoMatchTool(List<Message> messages, List<Map<String, Object>> toolSchemas) {
        String userMsg = null;
        for (Message msg : messages) {
            if (Message.ROLE_USER.equals(msg.getRole()) && msg.getContent() != null) {
                userMsg = msg.getContent();
                break;
            }
        }
        if (userMsg == null || userMsg.isBlank()) return null;

        for (KeywordRule rule : TOOL_KEYWORD_RULES) {
            String[] keywords = rule.keywords;
            String toolName = rule.toolName;

            boolean toolAvailable = toolSchemas.stream().anyMatch(s -> {
                Object fn = s.get("function");
                String name = fn instanceof Map ? (String) ((Map<?, ?>) fn).get("name") : null;
                return toolName.equals(name);
            });
            if (!toolAvailable) continue;

            boolean anyMatch = false;
            for (int k = 1; k < keywords.length; k++) {
                if (userMsg.contains(keywords[k])) {
                    anyMatch = true;
                    break;
                }
            }
            if (anyMatch) {
                log.info("自动匹配工具: tool={}, userMsg={}", toolName, StringUtils.truncate(userMsg, 50));
                return new ToolCall(UUID.randomUUID().toString(), "function",
                        new FunctionCall(toolName, "{}"));
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════
    //  Confirmation Points
    // ═══════════════════════════════════════════

    private AgentResponse createPlanConfirmation(String conversationId,
                                                  List<Message> messages,
                                                  List<ToolCall> toolCalls) {
        conversationStore.saveMessages(conversationId, messages);

        List<Map<String, Object>> plan = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", tc.getFunction().getName());
            item.put("action", "READ");

            ToolMeta meta = toolRegistry.getTool(tc.getFunction().getName());
            if (meta != null) {
                item.put("action", meta.getAction() == ActionType.WRITE ? "WRITE" : "READ");
            }

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = objectMapper.readValue(
                        tc.getFunction().getArguments(), Map.class);
                item.put("args", args);
            } catch (Exception e) {
                item.put("args", Collections.singletonMap("raw", tc.getFunction().getArguments()));
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
                                                  List<Message> messages,
                                                  ToolCall toolCall,
                                                  List<ToolCall> pendingToolCalls) {
        conversationStore.saveMessages(conversationId, messages);

        String confirmationId = confirmationStore.createExecConfirmation(
                conversationId, toolCall, pendingToolCalls);

        ConfirmationPoint cp = new ConfirmationPoint();
        cp.setConfirmationId(confirmationId);
        cp.setConversationId(conversationId);
        cp.setType("exec");
        cp.setTool(toolCall.getFunction().getName());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(
                    toolCall.getFunction().getArguments(), Map.class);
            cp.setArgs(args);
        } catch (Exception e) {
            Map<String, Object> rawArgs = new LinkedHashMap<>();
            rawArgs.put("raw", toolCall.getFunction().getArguments());
            cp.setArgs(rawArgs);
        }

        cp.setReasoning("即将执行写操作，请确认");
        cp.setCreatedAt(System.currentTimeMillis());

        log.info("创建 exec 确认点: id={}, tool={}, pending={}",
                confirmationId, toolCall.getFunction().getName(),
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
