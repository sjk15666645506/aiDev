package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.dto.*;
import com.deepseek.demo.store.ConfirmationStore;
import com.deepseek.demo.store.ConversationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AgentService 的集成测试。
 * <p>
 * 使用 @SpringBootTest 加载完整 Spring 上下文，
 * ConversationStore/ConfirmationStore 使用真实 Redis 后端，
 * DeepSeekService/ToolRegistry/VectorService 使用 MockBean 模拟。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AgentServiceTest {

    @MockBean
    private DeepSeekService deepSeekService;

    @MockBean
    private ToolRegistry toolRegistry;

    @MockBean
    private VectorService vectorService;

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private ConfirmationStore confirmationStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentService agentService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Captor
    private ArgumentCaptor<List<Message>> messagesCaptor;

    /** 清理 Redis 测试数据 */
    @AfterEach
    void tearDown() {
        Set<String> convKeys = redisTemplate.keys("conversation:*");
        if (convKeys != null && !convKeys.isEmpty()) {
            redisTemplate.delete(convKeys);
        }
        Set<String> cnfKeys = redisTemplate.keys("confirmation:*");
        if (cnfKeys != null && !cnfKeys.isEmpty()) {
            redisTemplate.delete(cnfKeys);
        }
    }

    // ==================== chat() 方法测试 ====================

    @Test
    void chat_ShouldReturnDone_WhenNoToolCalls() {
        when(vectorService.searchDocsWithFullContent(anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        Message responseMessage = new Message("assistant", "你好！有什么可以帮助你的吗？");
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(null);

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);

        AgentResponse result = agentService.chat("conv-1", "你好");

        assertEquals("done", result.getType());
        assertEquals("你好！有什么可以帮助你的吗？", result.getReply());
        assertNull(result.getConfirmationPoint());
    }

    @Test
    void chat_ShouldCreatePlanConfirmation_WhenToolCallsAndPlanNotConfirmed() {
        when(vectorService.searchDocsWithFullContent(anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

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

        ToolMeta queryMeta = mock(ToolMeta.class);
        when(queryMeta.getAction()).thenReturn(ActionType.READ);
        when(toolRegistry.getTool("query_task")).thenReturn(queryMeta);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        AgentResponse result = agentService.chat("conv-2", "查一下张三的工单");

        assertEquals("confirmation", result.getType());
        assertNotNull(result.getConfirmationPoint());
        assertEquals("plan", result.getConfirmationPoint().getType());
        assertNotNull(result.getConfirmationPoint().getConfirmationId());
        assertEquals("conv-2", result.getConfirmationPoint().getConversationId());
        assertNotNull(result.getConfirmationPoint().getPlan());
        assertFalse(result.getConfirmationPoint().getPlan().isEmpty());

        assertEquals(1, result.getConfirmationPoint().getPlan().size());
        assertEquals("query_task", result.getConfirmationPoint().getPlan().get(0).get("tool"));
        assertEquals("READ", result.getConfirmationPoint().getPlan().get(0).get("action"));
    }

    @Test
    void chat_ShouldReturnError_WhenDeepSeekThrowsException() {
        when(vectorService.searchDocsWithFullContent(anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenThrow(new RuntimeException("API 调用失败"));

        AgentResponse result = agentService.chat("conv-3", "查一下");

        assertEquals("error", result.getType());
        assertTrue(result.getReply().contains("大脑暂时离线"));
    }

    // ==================== confirm() 方法测试 ====================

    @Test
    void confirm_ShouldReturnError_WhenConfirmationNotFound() {
        AgentResponse result = agentService.confirm("conv-1", "nonexistent-id", true, null);

        assertEquals("error", result.getType());
        assertTrue(result.getReply().contains("无效或已过期"));
    }

    @Test
    void confirm_ShouldReturnError_WhenConfirmationAlreadyConsumed() {
        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "query_task");
        step.put("action", "READ");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        confirmationStore.consume(confirmationId);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, null);

        assertEquals("error", result.getType());
        assertTrue(result.getReply().contains("请勿重复确认"));
    }

    @Test
    void confirm_ShouldRejectPlan_WithoutFeedback() {
        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "create_task");
        step.put("action", "WRITE");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "帮我创建一个任务"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(new ToolCall("call_1", "function",
                new FunctionCall("create_task", "{}"))));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, false, null);

        assertEquals("done", result.getType());
        assertEquals("操作计划已被用户取消", result.getReply());

        assertNull(conversationStore.getApprovedPlan("conv-1"));
    }

    @Test
    void confirm_ShouldAdjustPlan_WhenPlanRejectedWithFeedback() {
        Message responseMessage = new Message("assistant", "好的，已调整方案。");
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(null);

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "create_task");
        step.put("action", "WRITE");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "帮我创建一个任务"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(new ToolCall("call_1", "function",
                new FunctionCall("create_task", "{}"))));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, false, "改为创建两个任务");

        assertEquals("done", result.getType());
        assertNotNull(result.getReply());
    }

    @Test
    void confirm_ShouldApprovePlan_WithoutFeedback() {
        Message responseMessage = new Message("assistant", "开始执行计划。");
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(null);

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "query_task");
        step.put("action", "READ");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "查一下工单"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(new ToolCall("call_1", "function",
                new FunctionCall("query_task", "{}"))));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, null);

        assertEquals("done", result.getType());

        assertTrue(conversationStore.getPlanConfirmed("conv-1"));
        assertNotNull(conversationStore.getApprovedPlan("conv-1"));
    }

    @Test
    void confirm_ShouldApprovePlan_WithFeedback() {
        Message responseMessage = new Message("assistant", "好的，已调整。");
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(null);

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "query_task");
        step.put("action", "READ");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "查一下工单"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(new ToolCall("call_1", "function",
                new FunctionCall("query_task", "{}"))));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, "只查张三的");

        assertEquals("done", result.getType());
    }

    @Test
    void confirm_ShouldRejectExec_WithoutFeedback() {
        ToolCall toolCall = new ToolCall("call_1", "function",
                new FunctionCall("create_task", "{\"title\":\"测试\"}"));
        String confirmationId = confirmationStore.createExecConfirmation("conv-1", toolCall, new ArrayList<>());

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "创建任务"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(toolCall));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, false, null);

        assertEquals("done", result.getType());
        assertEquals("操作已被用户取消", result.getReply());
    }

    @Test
    void confirm_ShouldExecuteTool_WhenExecConfirmedWithoutFeedback() {
        Message responseMessage = new Message("assistant", "任务已创建。");
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(null);

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        when(toolRegistry.execute(any(ToolCall.class)))
                .thenReturn("任务创建成功");

        ToolCall toolCall = new ToolCall("call_1", "function",
                new FunctionCall("create_task", "{\"title\":\"测试任务\"}"));
        String confirmationId = confirmationStore.createExecConfirmation("conv-1", toolCall, new ArrayList<>());

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "创建任务"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(toolCall));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, null);

        assertEquals("done", result.getType());
        verify(toolRegistry, times(1)).execute(any(ToolCall.class));
    }

    @Test
    void confirm_ShouldAdjustExec_WhenExecConfirmedWithFeedback() {
        Message responseMessage = new Message("assistant", "已调整参数。");
        DeepSeekChatResponse.Choice choice = new DeepSeekChatResponse.Choice();
        choice.setMessage(responseMessage);
        choice.setToolCalls(null);

        DeepSeekChatResponse response = new DeepSeekChatResponse();
        response.setChoices(List.of(choice));

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toJsonSchema()).thenReturn(new ArrayList<>());

        ToolCall toolCall = new ToolCall("call_1", "function",
                new FunctionCall("create_task", "{\"title\":\"测试\"}"));
        ToolCall pendingCall = new ToolCall("call_2", "function",
                new FunctionCall("send_notification", "{\"msg\":\"done\"}"));
        String confirmationId = confirmationStore.createExecConfirmation(
                "conv-1", toolCall, List.of(pendingCall));

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "你是一个助手"));
        messages.add(new Message("user", "创建任务并通知"));
        Message assistantMsg = new Message("assistant", null);
        assistantMsg.setToolCalls(List.of(toolCall, pendingCall));
        messages.add(assistantMsg);
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, "标题改为'紧急任务'");

        assertEquals("done", result.getType());
    }

    // ==================== agentLoop 极限条件测试 ====================

    @Test
    void agentLoop_ShouldReturnTimeout_WhenMaxIterationsReached() {
        when(vectorService.searchDocsWithFullContent(anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

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

        conversationStore.setPlanConfirmed("conv-1", true);

        List<Map<String, Object>> approvedPlan = new ArrayList<>();
        Map<String, Object> planItem = new HashMap<>();
        planItem.put("tool", "query_task");
        approvedPlan.add(planItem);
        conversationStore.setApprovedPlan("conv-1", approvedPlan);

        ToolMeta readMeta = mock(ToolMeta.class);
        when(readMeta.getAction()).thenReturn(ActionType.READ);
        when(toolRegistry.getTool("query_task")).thenReturn(readMeta);

        when(toolRegistry.execute(any(ToolCall.class))).thenReturn("查询结果：...");

        AgentResponse result = agentService.chat("conv-1", "查工单");

        assertEquals("done", result.getType());
        assertTrue(result.getReply().contains("无法在当前轮次内完成"));
    }
}
