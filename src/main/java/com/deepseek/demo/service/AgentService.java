package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import com.deepseek.demo.service.AgentFallback;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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
    private final ObjectMapper objectMapper;

    public AgentService(IToolRegistry toolRegistry,
                        IConversationStore conversationStore,
                        ConfirmationStore confirmationStore,
                        IVectorSearchService vectorService,
                        DomainRouter domainRouter,
                        ToolRetriever toolRetriever,
                        ReActEngine reActEngine,
                        ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.conversationStore = conversationStore;
        this.confirmationStore = confirmationStore;
        this.vectorService = vectorService;
        this.domainRouter = domainRouter;
        this.toolRetriever = toolRetriever;
        this.reActEngine = reActEngine;
        this.objectMapper = objectMapper;
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

            List<ChatMessage> messages = conversationStore.getMessages(conversationId);
            if (messages.isEmpty()) {
                messages.add(SystemMessage.from(buildSystemPrompt(knowledgeContext)));
            }

            messages.add(UserMessage.from(userMessage));

            List<ToolSpecification> toolSchemas = toolRegistry.toToolSpecifications(selectedTools);
            toolSchemas.addAll(toolRegistry.toToolSpecifications(
                    toolRegistry.getByDomain(ToolDomain.SYSTEM)));
            conversationStore.setSelectedToolSpecifications(conversationId, toolSchemas);

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

        List<ChatMessage> messages = conversationStore.getMessages(conversationId);

        if ("plan".equals(cp.getType())) {
            return handlePlanConfirm(conversationId, messages, cp, confirm, feedback);
        } else if ("exec".equals(cp.getType())) {
            return handleExecConfirm(conversationId, messages, cp, confirm, feedback);
        } else {
            return AgentResponse.error("未知的确认点类型: " + cp.getType());
        }
    }

    private AgentResponse handlePlanConfirm(String conversationId,
                                             List<ChatMessage> messages,
                                             ConfirmationStore.ConfirmationState cp,
                                             boolean confirm, String feedback) {
        if (confirm) {
            conversationStore.setPlanConfirmed(conversationId, true);
            conversationStore.setApprovedPlan(conversationId, cp.getPlanToolCalls());

            if (feedback != null && !feedback.isEmpty()) {
                removeLastAssistantMessage(messages);
                messages.add(UserMessage.from(
                        "操作计划已确认，但请按以下调整后执行: " + feedback));
            } else {
                removeLastAssistantMessage(messages);
                messages.add(SystemMessage.from(
                        "用户已确认操作计划。请严格按以下计划逐项执行，不得增删改操作。\n" +
                        "已批准的计划:\n" + reActEngine.formatPlan(cp.getPlanToolCalls())));
            }
            return reActEngine.agentLoop(conversationId, messages);

        } else if (feedback != null && !feedback.isEmpty()) {
            removeLastAssistantMessage(messages);
            messages.add(UserMessage.from(
                    "请根据以下意见调整方案: " + feedback));
            return reActEngine.agentLoop(conversationId, messages);

        } else {
            conversationStore.clearCheckpoint(conversationId);
            log.info("用户取消操作计划: conversationId={}", conversationId);
            return AgentResponse.done("操作计划已被用户取消");
        }
    }

    private AgentResponse handleExecConfirm(String conversationId,
                                             List<ChatMessage> messages,
                                             ConfirmationStore.ConfirmationState cp,
                                             boolean confirm, String feedback) {
        if (!confirm) {
            if (feedback != null && !feedback.isEmpty()) {
                removeLastAssistantMessage(messages);
                messages.add(UserMessage.from(
                        "请按以下调整后重新执行: " + feedback));
                appendPendingHint(messages, cp.getPendingRequests(objectMapper));
                return reActEngine.agentLoop(conversationId, messages);
            }
            conversationStore.clearCheckpoint(conversationId);
            log.info("用户取消操作: conversationId={}, tool={}",
                    conversationId, cp.getToolName());
            return AgentResponse.done("操作已被用户取消");
        }

        if (feedback != null && !feedback.isEmpty()) {
            removeLastAssistantMessage(messages);
            messages.add(UserMessage.from(
                    "确认执行，但请按以下调整: " + feedback));
            appendPendingHint(messages, cp.getPendingRequests(objectMapper));
            return reActEngine.agentLoop(conversationId, messages);
        }

        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id(cp.getToolCallId() != null ? cp.getToolCallId() : "")
                    .name(cp.getToolName() != null ? cp.getToolName() : "")
                    .arguments(cp.getToolArguments() != null ? cp.getToolArguments() : "{}")
                    .build();

            String result = toolRegistry.execute(request);
            messages.add(new ToolExecutionResultMessage(
                    cp.getToolCallId(), cp.getToolName(), result));
            log.info("工具执行完成: tool={}, resultLength={}",
                    cp.getToolName(), result.length());

            appendPendingHint(messages, cp.getPendingRequests(objectMapper));
            return reActEngine.agentLoop(conversationId, messages);

        } catch (Exception e) {
            log.error("工具执行失败: tool={}", cp.getToolName(), e);
            messages.add(new ToolExecutionResultMessage(cp.getToolCallId(), cp.getToolName(),
                    AgentFallback.toolExecutionFailed(cp.getToolName(), e.getMessage())));
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

    private void removeLastAssistantMessage(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage) {
                messages.remove(i);
                return;
            }
        }
    }

    private void appendPendingHint(List<ChatMessage> messages,
                                    List<ToolExecutionRequest> pendingRequests) {
        if (pendingRequests != null && !pendingRequests.isEmpty()) {
            StringBuilder sb = new StringBuilder("上一步已完成。你还需继续执行以下操作:\n");
            for (int i = 0; i < pendingRequests.size(); i++) {
                sb.append(i + 1).append(". ").append(pendingRequests.get(i).name()).append("\n");
            }
            messages.add(SystemMessage.from(sb.toString()));
        }
    }
}
