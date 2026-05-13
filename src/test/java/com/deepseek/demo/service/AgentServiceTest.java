package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.dto.*;
import com.deepseek.demo.store.ConfirmationStore;
import com.deepseek.demo.store.ConversationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AgentService 的单元测试。
 * <p>
 * 使用 Mockito 模拟外部依赖（DeepSeekService、ToolRegistry、VectorService），
 * 使用真实实例（ConversationStore、ConfirmationStore、ObjectMapper）以便验证状态变更。
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private DeepSeekService deepSeekService;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private VectorService vectorService;

    private ConversationStore conversationStore;
    private ConfirmationStore confirmationStore;
    private ObjectMapper objectMapper;
    private AgentService agentService;

    @Captor
    private ArgumentCaptor<List<Message>> messagesCaptor;

    @BeforeEach
    void setUp() {
        conversationStore = new ConversationStore();
        confirmationStore = new ConfirmationStore();
        objectMapper = new ObjectMapper();
        agentService = new AgentService(deepSeekService, toolRegistry,
                conversationStore, confirmationStore, vectorService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        conversationStore.shutdown();
        confirmationStore.shutdown();
    }

    // ==================== chat() 方法测试 ====================

    /**
     * 测试：LLM 返回纯文本（无 tool_calls），应返回 done 响应。
     */
    @Test
    void chat_ShouldReturnDone_WhenNoToolCalls() {
        // 模拟 VectorService：返回空知识库
        when(vectorService.searchDocsWithFullContent(anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        // 模拟 DeepSeek 响应：纯文本，无 tool_calls
        Message responseMessage = new Message("assistant", "你好！有什么可以帮助你的吗？");
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(null);

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);

        // 执行
        AgentResponse result = agentService.chat("conv-1", "你好");

        // 验证
        assertEquals("done", result.getType());
        assertEquals("你好！有什么可以帮助你的吗？", result.getReply());
        assertNull(result.getConfirmationPoint());
    }

    /**
     * 测试：LLM 返回 tool_calls 且计划未确认，应创建 plan 类型确认点。
     */
    @Test
    void chat_ShouldCreatePlanConfirmation_WhenToolCallsAndPlanNotConfirmed() {
        // 模拟 VectorService
        when(vectorService.searchDocsWithFullContent(anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        // 模拟 DeepSeek 响应：返回 tool_calls
        Message responseMessage = new Message("assistant", null);
        FunctionCall function = new FunctionCall("query_task", "{\"assignee\":\"张三\"}");
        ToolCall toolCall = new ToolCall("call_1", "function", function);

        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(List.of(toolCall));

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);

        // 模拟 ToolRegistry：返回 READ 类型工具
        // 注意：createPlanConfirmation 只用到 meta.getAction()，不需要 meta.getName()
        ToolMeta queryMeta = mock(ToolMeta.class);
        when(queryMeta.getAction()).thenReturn(ActionType.READ);
        when(toolRegistry.getTool("query_task")).thenReturn(queryMeta);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        // 执行
        AgentResponse result = agentService.chat("conv-2", "查一下张三的工单");

        // 验证：返回 confirmation 类型，包含 plan 信息
        assertEquals("confirmation", result.getType());
        assertNotNull(result.getConfirmationPoint());
        assertEquals("plan", result.getConfirmationPoint().getType());
        assertNotNull(result.getConfirmationPoint().getConfirmationId());
        assertEquals("conv-2", result.getConfirmationPoint().getConversationId());
        assertNotNull(result.getConfirmationPoint().getPlan());
        assertFalse(result.getConfirmationPoint().getPlan().isEmpty());

        // 验证计划摘要
        assertEquals(1, result.getConfirmationPoint().getPlan().size());
        assertEquals("query_task", result.getConfirmationPoint().getPlan().get(0).get("tool"));
        assertEquals("READ", result.getConfirmationPoint().getPlan().get(0).get("action"));
    }

    /**
     * 测试：DeepSeek API 抛出异常，应返回 error 响应。
     */
    @Test
    void chat_ShouldReturnError_WhenDeepSeekThrowsException() {
        // 模拟 VectorService
        when(vectorService.searchDocsWithFullContent(anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        // 模拟 DeepSeek 抛出异常
        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenThrow(new RuntimeException("API 调用失败"));

        // 执行
        AgentResponse result = agentService.chat("conv-3", "查一下");

        // 验证：返回 error 类型
        assertEquals("error", result.getType());
        assertTrue(result.getReply().contains("服务暂时不可用"));
    }

    // ==================== confirm() 方法测试 ====================

    /**
     * 测试：确认点不存在，应返回 error。
     */
    @Test
    void confirm_ShouldReturnError_WhenConfirmationNotFound() {
        AgentResponse result = agentService.confirm("conv-1", "nonexistent-id", true, null);

        assertEquals("error", result.getType());
        assertTrue(result.getReply().contains("无效或已过期"));
    }

    /**
     * 测试：确认点已消费，应返回 error。
     */
    @Test
    void confirm_ShouldReturnError_WhenConfirmationAlreadyConsumed() throws Exception {
        // 创建确认点
        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "query_task");
        step.put("action", "READ");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        // 先消费一次
        confirmationStore.consume(confirmationId);

        // 再次确认应返回错误
        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, null);

        assertEquals("error", result.getType());
        assertTrue(result.getReply().contains("请勿重复确认"));
    }

    /**
     * 测试：用户拒绝 plan 且无反馈，应终止流程。
     */
    @Test
    void confirm_ShouldRejectPlan_WithoutFeedback() {
        // 创建 plan 确认点
        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "create_task");
        step.put("action", "WRITE");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        // 消息历史（确保 checkpointer 不会 NPE）
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "帮我创建一个任务"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(new ToolCall("call_1", "function",
                new FunctionCall("create_task", "{}"))));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        // 拒绝确认
        AgentResponse result = agentService.confirm("conv-1", confirmationId, false, null);

        assertEquals("done", result.getType());
        assertEquals("操作计划已被用户取消", result.getReply());

        // 验证 checkpointer 已清除
        assertNull(conversationStore.getApprovedPlan("conv-1"));
    }

    /**
     * 测试：用户拒绝 plan 但有反馈，应让 LLM 调整方案。
     */
    @Test
    void confirm_ShouldAdjustPlan_WhenPlanRejectedWithFeedback() {
        // confirm() 路径不经过 chat()，不需要 mock vectorService
        // DeepSeek 第二次调用返回纯文本（结束循环）
        Message responseMessage = new Message("assistant", "好的，已调整方案。");
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(null);

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        // 创建 plan 确认点
        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "create_task");
        step.put("action", "WRITE");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        // 消息历史：包含 assistant 消息
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "帮我创建一个任务"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(new ToolCall("call_1", "function",
                new FunctionCall("create_task", "{}"))));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        // 拒绝但有反馈
        AgentResponse result = agentService.confirm("conv-1", confirmationId, false, "改为创建两个任务");

        assertEquals("done", result.getType());
        assertNotNull(result.getReply());
    }

    /**
     * 测试：用户批准 plan 无反馈，应设置计划确认状态并继续循环。
     */
    @Test
    void confirm_ShouldApprovePlan_WithoutFeedback() {
        // confirm() 路径不经过 chat()，不需要 mock vectorService
        // DeepSeek 第二次调用：返回纯文本（结束循环）
        Message responseMessage = new Message("assistant", "开始执行计划。");
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(null);

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        // 创建 plan 确认点
        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "query_task");
        step.put("action", "READ");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        // 消息历史
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "查一下工单"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(new ToolCall("call_1", "function",
                new FunctionCall("query_task", "{}"))));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        // 批准 plan
        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, null);

        assertEquals("done", result.getType());

        // 验证 store 状态已更新
        assertTrue(conversationStore.getPlanConfirmed("conv-1"));
        assertNotNull(conversationStore.getApprovedPlan("conv-1"));
    }

    /**
     * 测试：用户批准 plan 且有反馈，应移除最后 assistant 消息并追加用户反馈。
     */
    @Test
    void confirm_ShouldApprovePlan_WithFeedback() {
        // confirm() 路径不经过 chat()，不需要 mock vectorService
        // DeepSeek 第二次调用：返回纯文本（结束循环）
        Message responseMessage = new Message("assistant", "好的，已调整。");
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(null);

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        // 创建 plan 确认点
        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "query_task");
        step.put("action", "READ");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        // 消息历史
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "查一下工单"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(new ToolCall("call_1", "function",
                new FunctionCall("query_task", "{}"))));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        // 批准且有反馈
        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, "只查张三的");

        assertEquals("done", result.getType());
    }

    /**
     * 测试：拒绝 exec 确认且无反馈，应终止流程。
     */
    @Test
    void confirm_ShouldRejectExec_WithoutFeedback() {
        // 创建 exec 确认点
        ToolCall toolCall = new ToolCall("call_1", "function",
                new FunctionCall("create_task", "{\"title\":\"测试\"}"));
        String confirmationId = confirmationStore.createExecConfirmation("conv-1", toolCall, new ArrayList<>());

        // 消息历史
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "创建任务"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(toolCall));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        // 拒绝
        AgentResponse result = agentService.confirm("conv-1", confirmationId, false, null);

        assertEquals("done", result.getType());
        assertEquals("操作已被用户取消", result.getReply());
    }

    /**
     * 测试：确认 exec 且无反馈，应执行工具并继续循环。
     */
    @Test
    void confirm_ShouldExecuteTool_WhenExecConfirmedWithoutFeedback() {
        // confirm() 路径不经过 chat()，不需要 mock vectorService
        // 模拟 DeepSeek 第二次调用返回文本（结束循环）
        Message responseMessage = new Message("assistant", "任务已创建。");
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(null);

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        // 模拟工具执行
        when(toolRegistry.execute(any(ToolCall.class)))
                .thenReturn("任务创建成功");

        // 创建 exec 确认点
        ToolCall toolCall = new ToolCall("call_1", "function",
                new FunctionCall("create_task", "{\"title\":\"测试任务\"}"));
        String confirmationId = confirmationStore.createExecConfirmation("conv-1", toolCall, new ArrayList<>());

        // 消息历史
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "创建任务"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(toolCall));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        // 确认执行
        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, null);

        assertEquals("done", result.getType());
        verify(toolRegistry, times(1)).execute(any(ToolCall.class));
    }

    /**
     * 测试：确认 exec 但有反馈，应移除最后 assistant 消息并追加反馈。
     */
    @Test
    void confirm_ShouldAdjustExec_WhenExecConfirmedWithFeedback() {
        // confirm() 路径不经过 chat()，不需要 mock vectorService
        // 模拟 DeepSeek 第二次调用返回文本（结束循环）
        Message responseMessage = new Message("assistant", "已调整参数。");
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(null);

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        // 创建 exec 确认点（带有 pendingToolCalls）
        ToolCall toolCall = new ToolCall("call_1", "function",
                new FunctionCall("create_task", "{\"title\":\"测试\"}"));
        ToolCall pendingCall = new ToolCall("call_2", "function",
                new FunctionCall("send_notification", "{\"msg\":\"done\"}"));
        String confirmationId = confirmationStore.createExecConfirmation(
                "conv-1", toolCall, List.of(pendingCall));

        // 消息历史
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "创建任务并通知"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(toolCall, pendingCall));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        // 确认但有反馈
        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, "标题改为'紧急任务'");

        assertEquals("done", result.getType());
    }

    // ==================== agentLoop 极限条件测试 ====================

    /**
     * 测试：ReAct 循环达到最大迭代次数时，返回超时提示。
     */
    @Test
    void agentLoop_ShouldReturnTimeout_WhenMaxIterationsReached() {
        // 模拟 VectorService
        when(vectorService.searchDocsWithFullContent(anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        // 每次返回 tool_calls（导致循环继续）
        Message responseMessage = new Message("assistant", null);
        ToolCall toolCall = new ToolCall("call_1", "function",
                new FunctionCall("query_task", "{\"assignee\":\"张三\"}"));
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(List.of(toolCall));

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        // 设置计划已确认，让循环进入执行阶段
        conversationStore.setPlanConfirmed("conv-1", true);

        // 设置已批准计划
        List<Map<String, Object>> approvedPlan = new ArrayList<>();
        Map<String, Object> planItem = new HashMap<>();
        planItem.put("tool", "query_task");
        approvedPlan.add(planItem);
        conversationStore.setApprovedPlan("conv-1", approvedPlan);

        // 工具可用：READ 类型（agentLoop 中只用到 getAction() 判断读写类型）
        ToolMeta readMeta = mock(ToolMeta.class);
        when(readMeta.getAction()).thenReturn(ActionType.READ);
        when(toolRegistry.getTool("query_task")).thenReturn(readMeta);

        // 工具执行成功
        when(toolRegistry.execute(any(ToolCall.class))).thenReturn("查询结果：...");

        // 执行
        AgentResponse result = agentService.chat("conv-1", "查工单");

        // 验证：超时应返回 done 类型，且包含超时提示
        assertEquals("done", result.getType());
        assertTrue(result.getReply().contains("最大处理轮次"));
    }
}
