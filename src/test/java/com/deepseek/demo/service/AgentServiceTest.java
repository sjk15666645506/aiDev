package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.dto.AgentResponse;
import com.deepseek.demo.store.ConfirmationStore;
import com.deepseek.demo.store.ConversationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AgentServiceTest {

    @MockBean
    private ILlmService deepSeekService;

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
    private ArgumentCaptor<List<ChatMessage>> messagesCaptor;

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

        AiMessage aiMessage = AiMessage.from("你好！有什么可以帮助你的吗？");
        Response<AiMessage> response = Response.from(aiMessage);

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

        ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                .id("call_1")
                .name("query_task")
                .arguments("{\"assignee\":\"张三\"}")
                .build();
        AiMessage aiMessage = AiMessage.from(toolCall);
        Response<AiMessage> response = Response.from(aiMessage);

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);

        ToolMeta queryMeta = mock(ToolMeta.class);
        when(queryMeta.getAction()).thenReturn(ActionType.READ);
        when(toolRegistry.getTool("query_task")).thenReturn(queryMeta);
        when(toolRegistry.toToolSpecifications()).thenReturn(new ArrayList<>());
        when(toolRegistry.toToolSpecifications(anyList())).thenReturn(new ArrayList<>());

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

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个助手"));
        messages.add(UserMessage.from("帮我创建一个任务"));
        messages.add(AiMessage.from(
                ToolExecutionRequest.builder().id("call_1").name("create_task").arguments("{}").build()));
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, false, null);

        assertEquals("done", result.getType());
        assertEquals("操作计划已被用户取消", result.getReply());

        assertNull(conversationStore.getApprovedPlan("conv-1"));
    }

    @Test
    void confirm_ShouldAdjustPlan_WhenPlanRejectedWithFeedback() {
        AiMessage aiMessage = AiMessage.from("好的，已调整方案。");
        Response<AiMessage> response = Response.from(aiMessage);

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toToolSpecifications()).thenReturn(new ArrayList<>());
        when(toolRegistry.toToolSpecifications(anyList())).thenReturn(new ArrayList<>());

        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "create_task");
        step.put("action", "WRITE");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个助手"));
        messages.add(UserMessage.from("帮我创建一个任务"));
        messages.add(AiMessage.from(
                ToolExecutionRequest.builder().id("call_1").name("create_task").arguments("{}").build()));
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, false, "改为创建两个任务");

        assertEquals("done", result.getType());
        assertNotNull(result.getReply());
    }

    @Test
    void confirm_ShouldApprovePlan_WithoutFeedback() {
        AiMessage aiMessage = AiMessage.from("开始执行计划。");
        Response<AiMessage> response = Response.from(aiMessage);

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toToolSpecifications()).thenReturn(new ArrayList<>());
        when(toolRegistry.toToolSpecifications(anyList())).thenReturn(new ArrayList<>());

        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "query_task");
        step.put("action", "READ");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个助手"));
        messages.add(UserMessage.from("查一下工单"));
        messages.add(AiMessage.from(
                ToolExecutionRequest.builder().id("call_1").name("query_task").arguments("{}").build()));
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, null);

        assertEquals("done", result.getType());

        assertTrue(conversationStore.getPlanConfirmed("conv-1"));
        assertNotNull(conversationStore.getApprovedPlan("conv-1"));
    }

    @Test
    void confirm_ShouldApprovePlan_WithFeedback() {
        AiMessage aiMessage = AiMessage.from("好的，已调整。");
        Response<AiMessage> response = Response.from(aiMessage);

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toToolSpecifications()).thenReturn(new ArrayList<>());
        when(toolRegistry.toToolSpecifications(anyList())).thenReturn(new ArrayList<>());

        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "query_task");
        step.put("action", "READ");
        plan.add(step);

        String confirmationId = confirmationStore.createPlanConfirmation("conv-1", plan);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个助手"));
        messages.add(UserMessage.from("查一下工单"));
        messages.add(AiMessage.from(
                ToolExecutionRequest.builder().id("call_1").name("query_task").arguments("{}").build()));
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, "只查张三的");

        assertEquals("done", result.getType());
    }

    @Test
    void confirm_ShouldRejectExec_WithoutFeedback() {
        ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                .id("call_1").name("create_task").arguments("{\"title\":\"测试\"}").build();
        String confirmationId = confirmationStore.createExecConfirmation("conv-1", toolCall, new ArrayList<>());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个助手"));
        messages.add(UserMessage.from("创建任务"));
        messages.add(AiMessage.from(toolCall));
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, false, null);

        assertEquals("done", result.getType());
        assertEquals("操作已被用户取消", result.getReply());
    }

    @Test
    void confirm_ShouldExecuteTool_WhenExecConfirmedWithoutFeedback() {
        AiMessage aiMessage = AiMessage.from("任务已创建。");
        Response<AiMessage> response = Response.from(aiMessage);

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toToolSpecifications()).thenReturn(new ArrayList<>());
        when(toolRegistry.toToolSpecifications(anyList())).thenReturn(new ArrayList<>());

        when(toolRegistry.execute(any(ToolExecutionRequest.class)))
                .thenReturn("任务创建成功");

        ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                .id("call_1").name("create_task").arguments("{\"title\":\"测试任务\"}").build();
        String confirmationId = confirmationStore.createExecConfirmation("conv-1", toolCall, new ArrayList<>());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个助手"));
        messages.add(UserMessage.from("创建任务"));
        messages.add(AiMessage.from(toolCall));
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, null);

        assertEquals("done", result.getType());
        verify(toolRegistry, times(1)).execute(any(ToolExecutionRequest.class));
    }

    @Test
    void confirm_ShouldAdjustExec_WhenExecConfirmedWithFeedback() {
        AiMessage aiMessage = AiMessage.from("已调整参数。");
        Response<AiMessage> response = Response.from(aiMessage);

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toToolSpecifications()).thenReturn(new ArrayList<>());
        when(toolRegistry.toToolSpecifications(anyList())).thenReturn(new ArrayList<>());

        ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                .id("call_1").name("create_task").arguments("{\"title\":\"测试\"}").build();
        ToolExecutionRequest pendingCall = ToolExecutionRequest.builder()
                .id("call_2").name("send_notification").arguments("{\"msg\":\"done\"}").build();
        String confirmationId = confirmationStore.createExecConfirmation(
                "conv-1", toolCall, List.of(pendingCall));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个助手"));
        messages.add(UserMessage.from("创建任务并通知"));
        messages.add(new AiMessage(
                "我需要创建任务并发送通知。",
                List.of(toolCall, pendingCall)));
        conversationStore.saveMessages("conv-1", messages);

        AgentResponse result = agentService.confirm("conv-1", confirmationId, true, "标题改为'紧急任务'");

        assertEquals("done", result.getType());
    }

    // ==================== agentLoop 极限条件测试 ====================

    @Test
    void agentLoop_ShouldReturnTimeout_WhenMaxIterationsReached() {
        when(vectorService.searchDocsWithFullContent(anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                .id("call_1").name("query_task").arguments("{\"assignee\":\"张三\"}").build();
        AiMessage aiMessage = AiMessage.from(toolCall);
        Response<AiMessage> response = Response.from(aiMessage);

        when(deepSeekService.chatWithTools(anyList(), anyList()))
                .thenReturn(response);
        when(toolRegistry.toToolSpecifications()).thenReturn(new ArrayList<>());
        when(toolRegistry.toToolSpecifications(anyList())).thenReturn(new ArrayList<>());

        conversationStore.setPlanConfirmed("conv-1", true);

        List<Map<String, Object>> approvedPlan = new ArrayList<>();
        Map<String, Object> planItem = new HashMap<>();
        planItem.put("tool", "query_task");
        approvedPlan.add(planItem);
        conversationStore.setApprovedPlan("conv-1", approvedPlan);

        ToolMeta readMeta = mock(ToolMeta.class);
        when(readMeta.getAction()).thenReturn(ActionType.READ);
        when(toolRegistry.getTool("query_task")).thenReturn(readMeta);

        when(toolRegistry.execute(any(ToolExecutionRequest.class))).thenReturn("查询结果：...");

        AgentResponse result = agentService.chat("conv-1", "查工单");

        assertEquals("done", result.getType());
        assertTrue(result.getReply().contains("无法在当前轮次内完成"));
    }
}
