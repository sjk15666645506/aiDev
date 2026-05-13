package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.dto.*;
import com.deepseek.demo.store.ConfirmationStore;
import com.deepseek.demo.store.ConversationStore;
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

    private final DeepSeekService deepSeekService;
    private final ToolRegistry toolRegistry;
    private final ConversationStore conversationStore;
    private final ConfirmationStore confirmationStore;
    private final VectorService vectorService;
    private final ObjectMapper objectMapper;

    /**
     * 构造 AgentService。
     */
    public AgentService(DeepSeekService deepSeekService,
                        ToolRegistry toolRegistry,
                        ConversationStore conversationStore,
                        ConfirmationStore confirmationStore,
                        VectorService vectorService,
                        ObjectMapper objectMapper) {
        this.deepSeekService = deepSeekService;
        this.toolRegistry = toolRegistry;
        this.conversationStore = conversationStore;
        this.confirmationStore = confirmationStore;
        this.vectorService = vectorService;
        this.objectMapper = objectMapper;
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

            // 2. 获取或创建消息历史
            List<Message> messages = conversationStore.getMessages(conversationId);
            if (messages.isEmpty()) {
                messages.add(new Message("system", buildSystemPrompt(knowledgeContext)));
                messages.add(new Message("user", userMessage));
            }

            // 3. 进入 ReAct 循环
            return agentLoop(conversationId, messages);

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
                // 直接批准 → 注入已批准计划，要求 LLM 严格按计划执行
                messages.add(new Message("system",
                        "用户已确认操作计划。请严格按以下计划逐项执行，不得增删改操作。\n" +
                        "已批准的计划:\n" + formatPlan(cp.getPlanToolCalls())));
            }
            return agentLoop(conversationId, messages);

        } else if (feedback != null && !feedback.isEmpty()) {
            // 有反馈但未确认 → LLM 调整方案
            removeLastAssistantMessage(messages);
            messages.add(new Message("user",
                    "请根据以下意见调整方案: " + feedback));
            return agentLoop(conversationId, messages);

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
                return agentLoop(conversationId, messages);
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
            return agentLoop(conversationId, messages);

        } catch (Exception e) {
            log.error("工具执行失败: tool={}", cp.getToolName(), e);
            // 将异常信息以 tool role 返回，让 LLM 决定如何处理
            messages.add(new Message("tool",
                    "工具执行异常: " + e.getMessage(), cp.getToolCallId()));
            return agentLoop(conversationId, messages);
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
    private AgentResponse agentLoop(String conversationId, List<Message> messages) {
        boolean planConfirmed = conversationStore.getPlanConfirmed(conversationId);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            // 保存 checkpoint
            conversationStore.saveCheckpoint(conversationId, messages, i);

            // 调用 DeepSeek API（带 tools）
            DeepSeekChatResponse response;
            try {
                response = deepSeekService.chatWithTools(messages, toolRegistry.toJsonSchema());
            } catch (Exception e) {
                log.error("DeepSeek API 调用失败(第{}轮)", i, e);
                return AgentResponse.error("服务暂时不可用，请稍后再试");
            }

            // 解析响应
            if (response == null || response.getChoices() == null
                    || response.getChoices().isEmpty()) {
                log.error("DeepSeek API 返回空响应(第{}轮)", i);
                return AgentResponse.error("模型返回异常，请重试");
            }

            Message responseMessage = response.getChoices().get(0).getMessage();
            List<ToolCall> toolCalls = response.getChoices().get(0).getToolCalls();
            messages.add(responseMessage);

            if (toolCalls == null || toolCalls.isEmpty()) {
                // 无 tool_calls → 最终回答
                conversationStore.clearCheckpoint(conversationId);
                String content = responseMessage.getContent();
                log.info("Agent 返回最终回答(第{}轮): length={}", i,
                        content != null ? content.length() : 0);
                return AgentResponse.done(content != null ? content : "");
            }

            if (!planConfirmed) {
                // 首次 tool_calls → 生成操作计划确认点
                log.info("生成操作计划确认点(第{}轮): toolCalls={}",
                        i, toolCalls.size());
                return createPlanConfirmation(conversationId, messages, toolCalls);
            }

            // 已确认计划 → 逐个执行
            List<Map<String, Object>> approvedPlan = conversationStore.getApprovedPlan(conversationId);

            for (int t = 0; t < toolCalls.size(); t++) {
                ToolCall tc = toolCalls.get(t);
                String toolName = tc.getFunction().getName();

                // 校验是否在已批准计划中
                if (approvedPlan != null && !isInApprovedPlan(toolName, approvedPlan)) {
                    log.warn("工具不在已批准计划中，跳过: tool={}", toolName);
                    messages.add(new Message("system",
                            "操作 \"" + toolName + "\" 不在已批准计划中，已跳过。"));
                    continue;
                }

                ToolMeta meta = toolRegistry.getTool(toolName);
                if (meta == null) {
                    log.warn("工具不存在，跳过: tool={}", toolName);
                    messages.add(new Message("system",
                            "工具 \"" + toolName + "\" 不存在，已跳过。"));
                    continue;
                }

                if (meta.getAction() == ActionType.WRITE
                        && !toolRegistry.isAutoConfirm(meta.getName())) {
                    // 非白名单写操作 → 二次确认
                    List<ToolCall> remaining = toolCalls.subList(t + 1, toolCalls.size());
                    log.info("二次确认: tool={}, remaining={}", toolName, remaining.size());
                    return createExecConfirmation(conversationId, messages, tc, remaining);
                }

                // 直接执行（READ 或白名单 WRITE）
                try {
                    String result = toolRegistry.execute(tc);
                    messages.add(new Message("tool", result, tc.getId()));
                    log.debug("工具执行成功: tool={}", toolName);
                } catch (Exception e) {
                    log.error("工具执行失败: tool={}", toolName, e);
                    messages.add(new Message("tool",
                            "执行异常: " + e.getMessage(), tc.getId()));
                }
            }
        }

        // 达到最大迭代次数
        log.warn("ReAct 循环达到最大迭代次数: conversationId={}", conversationId);
        conversationStore.clearCheckpoint(conversationId);
        return AgentResponse.done("任务未完全执行，已达最大处理轮次。请尝试简化操作需求。");
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
            return "你是一个智能助手，请根据你的知识回答用户的问题。"
                    + "你可以调用可用的工具来帮助用户完成操作。"
                    + "如果需要执行多个操作，请一次性列出所有操作。"
                    + "请用中文回答。";
        }
        return "你是一个智能知识库助手。请基于以下参考内容回答用户的问题。"
                + "你可以调用可用的工具来帮助用户完成操作。"
                + "如果需要执行多个操作，请一次性列出所有操作。"
                + "请用中文回答。\n\n参考内容：\n" + context;
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

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
