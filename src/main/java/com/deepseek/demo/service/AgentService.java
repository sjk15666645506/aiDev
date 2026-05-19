package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import com.deepseek.demo.dto.*;
import com.deepseek.demo.store.ConfirmationStore;
import com.deepseek.demo.store.IConversationStore;
import com.deepseek.demo.util.StringUtils;
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

    private final IToolRegistry toolRegistry;
    private final IConversationStore conversationStore;
    private final ConfirmationStore confirmationStore;
    private final IVectorSearchService vectorService;
    private final DomainRouter domainRouter;
    private final ToolRetriever toolRetriever;
    private final ReActEngine reActEngine;

    public AgentService(IToolRegistry toolRegistry,
                        IConversationStore conversationStore,
                        ConfirmationStore confirmationStore,
                        IVectorSearchService vectorService,
                        DomainRouter domainRouter,
                        ToolRetriever toolRetriever,
                        ReActEngine reActEngine) {
        this.toolRegistry = toolRegistry;
        this.conversationStore = conversationStore;
        this.confirmationStore = confirmationStore;
        this.vectorService = vectorService;
        this.domainRouter = domainRouter;
        this.toolRetriever = toolRetriever;
        this.reActEngine = reActEngine;
    }

    // ═══════════════════════════════════════════
    //  Chat Entry
    // ═══════════════════════════════════════════

    public AgentResponse chat(String conversationId, String userMessage) {
        log.info("Agent 对话开始: conversationId={}, message={}",
                conversationId, StringUtils.truncate(userMessage, 50));

        try {
            String knowledgeContext = retrieveKnowledge(userMessage);

            ToolDomain domain = domainRouter.classify(userMessage);
            log.info("意图分类结果: domain={}", domain);

            List<ToolMeta> selectedTools = toolRetriever.retrieve(userMessage, domain, 20);
            log.info("工具召回结果: count={}, tools={}",
                    selectedTools.size(),
                    selectedTools.stream().map(ToolMeta::getName).collect(Collectors.toList()));

            List<Message> messages = conversationStore.getMessages(conversationId);
            if (messages.isEmpty()) {
                messages.add(new Message(Message.ROLE_SYSTEM, buildSystemPrompt(knowledgeContext)));
            }

            messages.add(new Message(Message.ROLE_USER, userMessage));

            List<Map<String, Object>> toolSchemas = toolRegistry.toJsonSchema(selectedTools);
            toolSchemas.addAll(toolRegistry.toJsonSchema(
                    toolRegistry.getByDomain(ToolDomain.SYSTEM)));
            conversationStore.setSelectedToolSchemas(conversationId, toolSchemas);

            return reActEngine.agentLoop(conversationId, messages, toolSchemas);

        } catch (Exception e) {
            log.error("Agent 对话异常", e);
            return AgentResponse.error("处理失败: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════
    //  Confirmation Callback
    // ═══════════════════════════════════════════

    public AgentResponse confirm(String conversationId, String confirmationId,
                                  boolean confirm, String feedback) {
        log.info("确认回调: confirmationId={}, confirm={}, feedback={}",
                confirmationId, confirm, feedback);

        ConfirmationStore.ConfirmationState cp = confirmationStore.get(confirmationId);
        if (cp == null) {
            return AgentResponse.error("确认点无效或已过期，请重新提问");
        }
        if (cp.isConsumed()) {
            return AgentResponse.error("该操作已处理，请勿重复确认");
        }
        confirmationStore.consume(confirmationId);

        List<Message> messages = conversationStore.getMessages(conversationId);

        if ("plan".equals(cp.getType())) {
            return handlePlanConfirm(conversationId, messages, cp, confirm, feedback);
        } else if ("exec".equals(cp.getType())) {
            return handleExecConfirm(conversationId, messages, cp, confirm, feedback);
        } else {
            return AgentResponse.error("未知的确认点类型: " + cp.getType());
        }
    }

    private AgentResponse handlePlanConfirm(String conversationId,
                                             List<Message> messages,
                                             ConfirmationStore.ConfirmationState cp,
                                             boolean confirm, String feedback) {
        if (confirm) {
            conversationStore.setPlanConfirmed(conversationId, true);
            conversationStore.setApprovedPlan(conversationId, cp.getPlanToolCalls());

            if (feedback != null && !feedback.isEmpty()) {
                removeLastAssistantMessage(messages);
                messages.add(new Message(Message.ROLE_USER,
                        "操作计划已确认，但请按以下调整后执行: " + feedback));
            } else {
                removeLastAssistantMessage(messages);
                messages.add(new Message(Message.ROLE_SYSTEM,
                        "用户已确认操作计划。请严格按以下计划逐项执行，不得增删改操作。\n" +
                        "已批准的计划:\n" + reActEngine.formatPlan(cp.getPlanToolCalls())));
            }
            return reActEngine.agentLoop(conversationId, messages);

        } else if (feedback != null && !feedback.isEmpty()) {
            removeLastAssistantMessage(messages);
            messages.add(new Message(Message.ROLE_USER,
                    "请根据以下意见调整方案: " + feedback));
            return reActEngine.agentLoop(conversationId, messages);

        } else {
            conversationStore.clearCheckpoint(conversationId);
            log.info("用户取消操作计划: conversationId={}", conversationId);
            return AgentResponse.done("操作计划已被用户取消");
        }
    }

    private AgentResponse handleExecConfirm(String conversationId,
                                             List<Message> messages,
                                             ConfirmationStore.ConfirmationState cp,
                                             boolean confirm, String feedback) {
        if (!confirm) {
            if (feedback != null && !feedback.isEmpty()) {
                removeLastAssistantMessage(messages);
                messages.add(new Message(Message.ROLE_USER,
                        "请按以下调整后重新执行: " + feedback));
                appendPendingHint(messages, cp.getPendingToolCalls());
                return reActEngine.agentLoop(conversationId, messages);
            }
            conversationStore.clearCheckpoint(conversationId);
            log.info("用户取消操作: conversationId={}, tool={}",
                    conversationId, cp.getToolName());
            return AgentResponse.done("操作已被用户取消");
        }

        if (feedback != null && !feedback.isEmpty()) {
            removeLastAssistantMessage(messages);
            messages.add(new Message(Message.ROLE_USER,
                    "确认执行，但请按以下调整: " + feedback));
            appendPendingHint(messages, cp.getPendingToolCalls());
            return reActEngine.agentLoop(conversationId, messages);
        }

        try {
            String toolArgs = cp.getToolArguments() != null
                    ? cp.getToolArguments() : "{}";
            com.deepseek.demo.dto.ToolCall toolCall = new com.deepseek.demo.dto.ToolCall();
            toolCall.setId(cp.getToolCallId());
            FunctionCall func = new FunctionCall(cp.getToolName(), toolArgs);
            toolCall.setFunction(func);

            String result = toolRegistry.execute(toolCall);
            messages.add(new Message(Message.ROLE_TOOL, result, cp.getToolCallId()));
            log.info("工具执行完成: tool={}, resultLength={}",
                    cp.getToolName(), result.length());

            appendPendingHint(messages, cp.getPendingToolCalls());
            return reActEngine.agentLoop(conversationId, messages);

        } catch (Exception e) {
            log.error("工具执行失败: tool={}", cp.getToolName(), e);
            messages.add(new Message(Message.ROLE_TOOL,
                    AgentFallback.toolExecutionFailed(cp.getToolName(), e.getMessage()), cp.getToolCallId()));
            return reActEngine.agentLoop(conversationId, messages);
        }
    }

    // ═══════════════════════════════════════════
    //  Knowledge & Prompt
    // ═══════════════════════════════════════════

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

    // ═══════════════════════════════════════════
    //  Message Helpers
    // ═══════════════════════════════════════════

    private void removeLastAssistantMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (Message.ROLE_ASSISTANT.equals(messages.get(i).getRole())) {
                messages.remove(i);
                return;
            }
        }
    }

    private void appendPendingHint(List<Message> messages,
                                    List<com.deepseek.demo.dto.ToolCall> pendingToolCalls) {
        if (pendingToolCalls != null && !pendingToolCalls.isEmpty()) {
            StringBuilder sb = new StringBuilder("上一步已完成。你还需继续执行以下操作:\n");
            for (int i = 0; i < pendingToolCalls.size(); i++) {
                String name = pendingToolCalls.get(i).getFunction().getName();
                sb.append(i + 1).append(". ").append(name).append("\n");
            }
            messages.add(new Message(Message.ROLE_SYSTEM, sb.toString()));
        }
    }
}
