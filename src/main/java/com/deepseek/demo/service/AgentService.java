package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.dto.*;
import com.deepseek.demo.store.ConfirmationStore;
import com.deepseek.demo.store.ConversationStore;
import com.deepseek.demo.annotation.ToolDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 服务，实现基于 ReAct 循环的自动操作引擎。
 * <p>
 * 核心流程：
 * <ol>
 *   <li>接收用户问题 → RAG 检索知识库 → 拼入 system prompt</li>
 *   <li>调用 DeepSeek API（带 tools）→ LLM 返回 tool_calls 或文本</li>
 *   <li>首次 tool_calls → 生成操作计划确认点，等待用户确认</li>
 *   <li>用户确认后 → 逐个执行 tool（读自动，写按白名单判断）</li>
 *   <li>非白名单写操作 → 二次确认 → 确认后执行 → 结果回 LLM</li>
 *   <li>所有操作完成后 → LLM 汇总最终回答</li>
 * </ol>
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** ReAct 循环最大迭代次数，防止无限循环 */
    private static final int MAX_ITERATIONS = 10;

    /** 领域关键词 → 工具名称 映射（LLM 不调用工具时自动匹配） */
    private static final Map<String[], String> TOOL_KEYWORDS = new HashMap<>();
    static {
        TOOL_KEYWORDS.put(new String[]{"FINANCE", "热门", "行情", "涨跌", "推荐", "好股票", "牛股"}, "query_hot_stocks");
        TOOL_KEYWORDS.put(new String[]{"FINANCE", "持仓", "持有", "市值", "我的股票", "账户"}, "query_portfolio");
        TOOL_KEYWORDS.put(new String[]{"FINANCE", "交易记录", "历史", "买卖记录"}, "query_trade_history");
        TOOL_KEYWORDS.put(new String[]{"FINANCE", "净值", "估值", "价格", "代码"}, "query_stock_nav");
        TOOL_KEYWORDS.put(new String[]{"FINANCE", "买入", "购买", "建仓"}, "buy_stock");
        TOOL_KEYWORDS.put(new String[]{"FINANCE", "卖出", "清仓"}, "sell_stock");
    }

    private final DeepSeekService deepSeekService;
    private final ToolRegistry toolRegistry;
    private final ConversationStore conversationStore;
    private final ConfirmationStore confirmationStore;
    private final VectorService vectorService;
    private final ObjectMapper objectMapper;
    private final DomainRouter domainRouter;
    private final ToolRetriever toolRetriever;
    private final CapabilityGuard capabilityGuard;
    private final FrequencyTracker frequencyTracker;

    /**
     * 构造 AgentService。
     */
    public AgentService(DeepSeekService deepSeekService,
                        ToolRegistry toolRegistry,
                        ConversationStore conversationStore,
                        ConfirmationStore confirmationStore,
                        VectorService vectorService,
                        ObjectMapper objectMapper,
                        DomainRouter domainRouter,
                        ToolRetriever toolRetriever,
                        CapabilityGuard capabilityGuard,
                        FrequencyTracker frequencyTracker) {
        this.deepSeekService = deepSeekService;
        this.toolRegistry = toolRegistry;
        this.conversationStore = conversationStore;
        this.confirmationStore = confirmationStore;
        this.vectorService = vectorService;
        this.objectMapper = objectMapper;
        this.domainRouter = domainRouter;
        this.toolRetriever = toolRetriever;
        this.capabilityGuard = capabilityGuard;
        this.frequencyTracker = frequencyTracker;
    }

    /**
     * Agent 对话入口。
     * <p>
     * 接收用户消息，检索知识库，进入 ReAct 循环。
     *
     * @param conversationId 会话 ID
     * @param userMessage 用户消息
     * @return AgentResponse（done / confirmation / error）
     */
    public AgentResponse chat(String conversationId, String userMessage) {
        log.info("Agent 对话开始: conversationId={}, message={}",
                conversationId, truncate(userMessage, 50));

        try {
            // 1. 检索知识库
            String knowledgeContext = retrieveKnowledge(userMessage);

            // 2. Layer 1: 意图分类 → 确定领域
            ToolDomain domain = domainRouter.classify(userMessage);
            log.info("意图分类结果: domain={}", domain);

            // 3. Layer 2: 工具召回 → 只携带相关工具
            List<ToolMeta> selectedTools = toolRetriever.retrieve(userMessage, domain, 20);
            log.info("工具召回结果: count={}, tools={}",
                    selectedTools.size(),
                    selectedTools.stream().map(ToolMeta::getName).collect(Collectors.toList()));

            // 4. 获取或创建消息历史
            List<Message> messages = conversationStore.getMessages(conversationId);
            if (messages.isEmpty()) {
                messages.add(new Message("system", buildSystemPrompt(knowledgeContext)));
            }

            messages.add(new Message("user", userMessage));

            // 5. 生成 filtered tool schemas 并保存到会话存储
            List<Map<String, Object>> toolSchemas = toolRegistry.toJsonSchema(selectedTools);
            // 始终追加系统级工具（如 delegate_task），使主 Agent 具备委派能力
            toolSchemas.addAll(toolRegistry.toJsonSchema(
                    toolRegistry.getByDomain(ToolDomain.SYSTEM)));
            conversationStore.setSelectedToolSchemas(conversationId, toolSchemas);

            // 6. 进入 ReAct 循环（传入选定的工具 schemas）
            return agentLoop(conversationId, messages, toolSchemas);

        } catch (Exception e) {
            log.error("Agent 对话异常", e);
            return AgentResponse.error("处理失败: " + e.getMessage());
        }
    }

    /**
     * 确认回调，处理用户的确认/拒绝/反馈操作。
     * <p>
     * 三种场景：
     * <ul>
     *   <li>plan + confirm + feedback → 调整后执行</li>
     *   <li>plan + confirm → 批准，开始执行</li>
     *   <li>plan + !confirm + feedback → 有反馈但未确认，LLM 调整方案</li>
     *   <li>plan + !confirm → 明确拒绝，终止流程</li>
     *   <li>exec 类型同理</li>
     * </ul>
     *
     * @param conversationId 会话 ID
     * @param confirmationId 确认点 ID
     * @param confirm 是否确认执行
     * @param feedback 可选的修改意见
     * @return AgentResponse（done / confirmation / error）
     */
    public AgentResponse confirm(String conversationId, String confirmationId,
                                  boolean confirm, String feedback) {
        log.info("确认回调: confirmationId={}, confirm={}, feedback={}",
                confirmationId, confirm, feedback);

        // 1. 校验确认点
        ConfirmationStore.ConfirmationState cp = confirmationStore.get(confirmationId);
        if (cp == null) {
            return AgentResponse.error("确认点无效或已过期，请重新提问");
        }
        if (cp.isConsumed()) {
            return AgentResponse.error("该操作已处理，请勿重复确认");
        }
        confirmationStore.consume(confirmationId);

        // 2. 恢复消息历史
        List<Message> messages = conversationStore.getMessages(conversationId);

        // 3. 根据确认点类型处理
        if ("plan".equals(cp.getType())) {
            return handlePlanConfirm(conversationId, messages, cp, confirm, feedback);
        } else if ("exec".equals(cp.getType())) {
            return handleExecConfirm(conversationId, messages, cp, confirm, feedback);
        } else {
            return AgentResponse.error("未知的确认点类型: " + cp.getType());
        }
    }

    /**
     * 处理 plan 类型确认。
     * <p>
     * confirm=true: 批准操作计划，开始执行
     * confirm=false + feedback: LLM 调整方案
     * confirm=false: 终止流程
     */
    private AgentResponse handlePlanConfirm(String conversationId,
                                             List<Message> messages,
                                             ConfirmationStore.ConfirmationState cp,
                                             boolean confirm, String feedback) {
        if (confirm) {
            // 批准计划
            conversationStore.setPlanConfirmed(conversationId, true);
            conversationStore.setApprovedPlan(conversationId, cp.getPlanToolCalls());

            if (feedback != null && !feedback.isEmpty()) {
                // 批准但有修改意见 → 移除上一条 assistant tool_calls，让 LLM 调整
                removeLastAssistantMessage(messages);
                messages.add(new Message("user",
                        "操作计划已确认，但请按以下调整后执行: " + feedback));
            } else {
                // 直接批准 → 先移除 orphaned assistant(tool_calls) 避免违反
                // "assistant(tool_calls) 后必须跟 tool 响应" 的 API 约束
                removeLastAssistantMessage(messages);
                // 注入已批准计划，要求 LLM 严格按计划执行
                messages.add(new Message("system",
                        "用户已确认操作计划。请严格按以下计划逐项执行，不得增删改操作。\n" +
                        "已批准的计划:\n" + formatPlan(cp.getPlanToolCalls())));
            }
            return agentLoop(conversationId, messages, restoreToolSchemas(conversationId));

        } else if (feedback != null && !feedback.isEmpty()) {
            // 有反馈但未确认 → LLM 调整方案
            removeLastAssistantMessage(messages);
            messages.add(new Message("user",
                    "请根据以下意见调整方案: " + feedback));
            return agentLoop(conversationId, messages, restoreToolSchemas(conversationId));

        } else {
            // 明确拒绝 → 终止
            conversationStore.clearCheckpoint(conversationId);
            log.info("用户取消操作计划: conversationId={}", conversationId);
            return AgentResponse.done("操作计划已被用户取消");
        }
    }

    /**
     * 处理 exec 类型确认。
     * <p>
     * confirm=true: 执行工具
     * confirm=true + feedback: 调整后执行
     * confirm=false + feedback: LLM 调整
     * confirm=false: 终止流程
     */
    private AgentResponse handleExecConfirm(String conversationId,
                                             List<Message> messages,
                                             ConfirmationStore.ConfirmationState cp,
                                             boolean confirm, String feedback) {
        if (!confirm) {
            if (feedback != null && !feedback.isEmpty()) {
                // 有反馈但未确认 → LLM 调整 tool_call
                removeLastAssistantMessage(messages);
                messages.add(new Message("user",
                        "请按以下调整后重新执行: " + feedback));
                appendPendingHint(messages, cp.getPendingToolCalls());
                return agentLoop(conversationId, messages, restoreToolSchemas(conversationId));
            }
            // 明确拒绝 → 终止
            conversationStore.clearCheckpoint(conversationId);
            log.info("用户取消操作: conversationId={}, tool={}",
                    conversationId, cp.getToolName());
            return AgentResponse.done("操作已被用户取消");
        }

        if (feedback != null && !feedback.isEmpty()) {
            // 确认但有修改意见 → LLM 调整后执行
            removeLastAssistantMessage(messages);
            messages.add(new Message("user",
                    "确认执行，但请按以下调整: " + feedback));
            appendPendingHint(messages, cp.getPendingToolCalls());
            return agentLoop(conversationId, messages);
        }

        // 确认 + 无修改 → 执行工具
        try {
            // 重建 ToolCall 以执行（从 ConfirmationState 中恢复参数字符串）
            String toolArgs = cp.getToolArguments() != null
                    ? cp.getToolArguments() : "{}";
            com.deepseek.demo.dto.ToolCall toolCall = new com.deepseek.demo.dto.ToolCall();
            toolCall.setId(cp.getToolCallId());
            FunctionCall func = new FunctionCall(cp.getToolName(), toolArgs);
            toolCall.setFunction(func);

            String result = toolRegistry.execute(toolCall);
            messages.add(new Message("tool", result, cp.getToolCallId()));
            log.info("工具执行完成: tool={}, resultLength={}",
                    cp.getToolName(), result.length());

            // 提示剩余操作
            appendPendingHint(messages, cp.getPendingToolCalls());
            return agentLoop(conversationId, messages, restoreToolSchemas(conversationId));

        } catch (Exception e) {
            log.error("工具执行失败: tool={}", cp.getToolName(), e);
            messages.add(new Message("tool",
                    AgentFallback.toolExecutionFailed(cp.getToolName(), e.getMessage()), cp.getToolCallId()));
            return agentLoop(conversationId, messages, restoreToolSchemas(conversationId));
        }
    }

    /**
     * ReAct 循环核心。
     * <p>
     * 循环调用 DeepSeek API（带 tools），根据返回结果决定下一步：
     * <ul>
     *   <li>无 tool_calls → 返回最终回答</li>
     *   <li>有 tool_calls + 未确认计划 → 创建计划确认点</li>
     *   <li>有 tool_calls + 已确认 → 逐个执行，写操作需二次确认</li>
     * </ul>
     *
     * @param conversationId 会话 ID
     * @param messages 当前消息列表
     * @return AgentResponse
     */
    /** 保留原始签名，使用全量工具 schemas */
    private AgentResponse agentLoop(String conversationId, List<Message> messages) {
        return agentLoop(conversationId, messages, restoreToolSchemas(conversationId));
    }

    /**
     * ReAct 循环核心（携带工具 schemas）。
     * <p>
     * 循环调用 DeepSeek API（带 tools），根据返回结果决定下一步：
     * <ul>
     *   <li>无 tool_calls → 自动匹配工具或返回最终回答</li>
     *   <li>有 tool_calls + 未确认计划 → 创建计划确认点</li>
     *   <li>有 tool_calls + 已确认 → 逐个执行（含 CapabilityGuard 校验）</li>
     * </ul>
     *
     * @param conversationId 会话 ID
     * @param messages 当前消息列表
     * @param toolSchemas 当前会话选中的工具 schemas
     * @return AgentResponse
     */
    private AgentResponse agentLoop(String conversationId, List<Message> messages,
                                     List<Map<String, Object>> toolSchemas) {
        boolean planConfirmed = conversationStore.getPlanConfirmed(conversationId);

        // 记录本轮携带的工具
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
            // DeepSeek V4 将 tool_calls 放在 message 内部，choice 级别可能为空
            List<ToolCall> toolCalls = choice.getToolCalls();
            if ((toolCalls == null || toolCalls.isEmpty()) && responseMessage.getToolCalls() != null) {
                toolCalls = responseMessage.getToolCalls();
            }
            // DeepSeek V4: reasoning_content 可能在 choice 级别或 message 内部
            if (choice.getReasoningContent() != null) {
                responseMessage.setReasoningContent(choice.getReasoningContent());
            }
            // V4-Pro: reasoning_content 也可能在 message.reasoning_content 中
            // Jackson 已通过 @JsonProperty 自动反序列化到此字段
            messages.add(responseMessage);

            if (toolCalls == null || toolCalls.isEmpty()) {
                // 首轮 LLM 未调用工具 → 尝试自动匹配工具（绕过 DeepSeek 不可靠的 tool_choice）
                if (i == 0 && toolSchemas != null && !toolSchemas.isEmpty()) {
                    ToolCall matched = autoMatchTool(messages, toolSchemas);
                    if (matched != null) {
                        log.info("LLM 未调用工具，自动匹配执行: {}", matched.getFunction().getName());
                        // 移除空 assistant 消息，注入 tool_calls 继续流程
                        messages.remove(messages.size() - 1);
                        if (!planConfirmed) {
                            return createPlanConfirmation(conversationId, messages, List.of(matched));
                        }
                        // plan 已确认 → 直接执行
                        // 注入 assistant(tool_calls) 消息，使后续 tool 角色消息有合法前驱
                        List<ToolCall> matchedCalls = List.of(matched);
                        Message assistantWithToolCalls = new Message("assistant", null);
                        assistantWithToolCalls.setToolCalls(matchedCalls);
                        messages.add(assistantWithToolCalls);
                        toolCalls = matchedCalls;
                        // fall through to 工具执行逻辑
                    } else {
                        conversationStore.clearCheckpoint(conversationId);
                        String content = responseMessage.getContent();
                        log.info("LLM 未调用工具且无自动匹配: {}", content != null ? truncate(content, 80) : "空");
                        return AgentResponse.done(content != null ? content : "");
                    }
                } else {
                    conversationStore.clearCheckpoint(conversationId);
                    String content = responseMessage.getContent();
                    log.info("LLM 未调用工具，直接返回文本: {}", content != null ? truncate(content, 80) : "空");
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

                // 校验是否在已批准计划中
                if (approvedPlan != null && !isInApprovedPlan(toolName, approvedPlan)) {
                    log.warn("工具不在已批准计划中，跳过: tool={}", toolName);
                    skippedCount++;
                    messages.add(new Message("system",
                            "操作 \"" + toolName + "\" 不在已批准计划中，已跳过。"));
                    continue;
                }

                ToolMeta meta = toolRegistry.getTool(toolName);
                if (meta == null) {
                    log.warn("工具不存在，跳过: tool={}", toolName);
                    skippedCount++;
                    messages.add(new Message("system",
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
                    messages.add(new Message("system",
                            "你选择的工具 [" + toolName + "] 可能不适用于当前请求。请选择其他工具。"));
                    continue;
                }

                // 记录调用频率
                frequencyTracker.recordCall(toolName);

                if (meta.getAction() == ActionType.WRITE
                        && !toolRegistry.isAutoConfirm(meta.getName())) {
                    List<ToolCall> remaining = toolCalls.subList(t + 1, toolCalls.size());
                    log.info("二次确认: tool={}, remaining={}", toolName, remaining.size());
                    return createExecConfirmation(conversationId, messages, tc, remaining);
                }

                // 直接执行
                try {
                    String result = toolRegistry.execute(tc);
                    messages.add(new Message("tool", result, tc.getId()));
                    log.debug("工具执行成功: tool={}", toolName);
                } catch (Exception e) {
                    log.error("工具执行失败: tool={}", toolName, e);
                    messages.add(new Message("tool",
                            AgentFallback.toolExecutionFailed(toolName, e.getMessage()), tc.getId()));
                    skippedCount++;
                }
            }

            // 本轮所有工具均被跳过，无有效操作可执行
            if (skippedCount == toolCalls.size() && skippedCount > 0) {
                conversationStore.clearCheckpoint(conversationId);
                return AgentResponse.done(AgentFallback.noSuitableTool());
            }
        }

        log.warn("ReAct 循环达到最大迭代次数: conversationId={}", conversationId);
        conversationStore.clearCheckpoint(conversationId);
        return AgentResponse.done(AgentFallback.maxIterationsReached());
    }

    /**
     * 创建 plan 类型确认点。
     */
    private AgentResponse createPlanConfirmation(String conversationId,
                                                  List<Message> messages,
                                                  List<ToolCall> toolCalls) {
        // 保存消息历史
        conversationStore.saveMessages(conversationId, messages);

        // 提取操作计划摘要
        List<Map<String, Object>> plan = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", tc.getFunction().getName());
            item.put("action", "READ"); // 默认 READ，后续由 ToolRegistry 覆盖

            ToolMeta meta = toolRegistry.getTool(tc.getFunction().getName());
            if (meta != null) {
                item.put("action", meta.getAction() == ActionType.WRITE ? "WRITE" : "READ");
            }

            // 解析参数（可能失败，不影响整体）
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

        // 生成确认 ID
        String confirmationId = confirmationStore.createPlanConfirmation(
                conversationId, plan);

        // 构建确认点响应
        long readCount = plan.stream().filter(p -> "READ".equals(p.get("action"))).count();
        long writeCount = plan.stream().filter(p -> "WRITE".equals(p.get("action"))).count();

        ConfirmationPoint cp = new ConfirmationPoint();
        cp.setConfirmationId(confirmationId);
        cp.setConversationId(conversationId);
        cp.setType("plan");
        cp.setPlan(plan);
        cp.setSummary("共 " + plan.size() + " 项操作：" + readCount + " 项查询 + " + writeCount + " 项写入");
        cp.setCreatedAt(System.currentTimeMillis());

        // 尝试获取 reasoning：由于 DeepSeek 文本与 tool_calls 互斥，
        // 当前不额外调用 LLM 生成推理文本，由客户端根据 plan 渲染
        cp.setReasoning("");

        log.info("创建 plan 确认点: id={}, planSize={}", confirmationId, plan.size());
        return AgentResponse.waitConfirm(cp);
    }

    /**
     * 创建 exec 类型确认点。
     */
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

        // 解析参数供客户端展示
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

    /** 从会话存储恢复工具 schemas */
    private List<Map<String, Object>> restoreToolSchemas(String conversationId) {
        List<Map<String, Object>> schemas = conversationStore.getSelectedToolSchemas(conversationId);
        return schemas != null ? schemas : toolRegistry.toJsonSchema();
    }

    /**
     * 检索知识库。
     */
    private String retrieveKnowledge(String question) {
        try {
            List<Map<String, Object>> contexts = vectorService.searchDocsWithFullContent(question, 5);
            if (contexts == null || contexts.isEmpty()) {
                return "";
            }
            return contexts.stream()
                    .map(ctx -> {
                        String text = (String) ctx.get("text");
                        String source = (String) ctx.get("file_name");
                        if (source != null) {
                            return "[来源: " + source + "]\n" + text;
                        }
                        return text;
                    })
                    .collect(Collectors.joining("\n\n---\n\n"));
        } catch (Exception e) {
            log.warn("知识库检索失败，降级为空上下文", e);
            return "";
        }
    }

    /**
     * 构建系统提示词。
     */
    private String buildSystemPrompt(String context) {
        if (context == null || context.isEmpty()) {
            return "你是一个智能助手，必须遵循以下原则：\n"
                    + "1. 当有可用工具时，必须调用工具来获取实时数据或执行操作，不能仅凭知识回答\n"
                    + "2. 如果需要执行多个操作，请一次性列出所有操作\n"
                    + "3. 工具执行完成后，根据结果回复用户并询问是否需要进一步操作\n"
                    + "4. 请用中文回答";
        }
        return "你是一个智能知识库助手。必须遵循以下原则：\n"
                + "1. 当有可用工具时，必须调用工具来获取实时数据或执行操作，不能仅凭知识回答\n"
                + "2. 如果需要执行多个操作，请一次性列出所有操作\n"
                + "3. 工具执行完成后，根据结果回复用户并询问是否需要进一步操作\n"
                + "4. 请用中文回答\n\n参考内容：\n" + context;
    }

    /**
     * 格式化操作计划为可读文本。
     */
    private String formatPlan(List<Map<String, Object>> plan) {
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

    /**
     * 判断工具是否在已批准的计划中。
     */
    private boolean isInApprovedPlan(String toolName, List<Map<String, Object>> plan) {
        return plan.stream().anyMatch(item -> toolName.equals(item.get("tool")));
    }

    /**
     * 移除消息列表中最后一条 assistant 角色的消息（通常是带 tool_calls 的）。
     * 在需要让 LLM 重新生成 tool_calls 时使用。
     */
    private void removeLastAssistantMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(messages.get(i).getRole())) {
                messages.remove(i);
                return;
            }
        }
    }

    /**
     * 追加剩余操作提示。
     */
    private void appendPendingHint(List<Message> messages,
                                    List<com.deepseek.demo.dto.ToolCall> pendingToolCalls) {
        if (pendingToolCalls != null && !pendingToolCalls.isEmpty()) {
            StringBuilder sb = new StringBuilder("上一步已完成。你还需继续执行以下操作:\n");
            // 从 tool_calls 中提取名称
            for (int i = 0; i < pendingToolCalls.size(); i++) {
                String name = pendingToolCalls.get(i).getFunction().getName();
                sb.append(i + 1).append(". ").append(name).append("\n");
            }
            messages.add(new Message("system", sb.toString()));
        }
    }

    /**
     * 自动匹配工具：当 LLM 未调用任何工具时，根据用户消息关键词匹配最合适的工具。
     * 仅在首轮且存在可用工具时使用，作为 tool_choice 不可靠的降级方案。
     */
    private ToolCall autoMatchTool(List<Message> messages, List<Map<String, Object>> toolSchemas) {
        String userMsg = null;
        for (Message msg : messages) {
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                userMsg = msg.getContent();
                break;
            }
        }
        if (userMsg == null || userMsg.isBlank()) return null;

        for (Map.Entry<String[], String> entry : TOOL_KEYWORDS.entrySet()) {
            String[] keywords = entry.getKey();
            if (keywords.length == 0) continue;
            String toolName = entry.getValue();

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
                log.info("自动匹配工具: tool={}, userMsg={}", toolName, truncate(userMsg, 50));
                return new ToolCall(UUID.randomUUID().toString(), "function",
                        new FunctionCall(toolName, "{}"));
            }
        }
        return null;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
