package com.deepseek.demo.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 AgentResponse 和 ConfirmationPoint DTO 的序列化与反序列化。
 */
class AgentResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldCreateDoneResponse() {
        AgentResponse r = AgentResponse.done("任务已创建成功");
        assertEquals("done", r.getType());
        assertEquals("任务已创建成功", r.getReply());
        assertNull(r.getConfirmationPoint());
    }

    @Test
    void shouldCreateErrorResponse() {
        AgentResponse r = AgentResponse.error("服务暂时不可用");
        assertEquals("error", r.getType());
        assertEquals("服务暂时不可用", r.getReply());
        assertNull(r.getConfirmationPoint());
    }

    @Test
    void shouldCreateWaitConfirmResponse() {
        ConfirmationPoint cp = new ConfirmationPoint();
        cp.setConfirmationId("uuid-123");
        cp.setType("plan");
        cp.setReasoning("需要创建以下任务");
        cp.setSummary("2 个操作");

        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> op1 = new HashMap<>();
        op1.put("tool", "createTask");
        op1.put("args", Map.of("title", "测试任务"));
        plan.add(op1);
        cp.setPlan(plan);
        cp.setCreatedAt(System.currentTimeMillis());

        AgentResponse r = AgentResponse.waitConfirm(cp);
        assertEquals("confirmation", r.getType());
        assertNull(r.getReply());
        assertNotNull(r.getConfirmationPoint());
        assertEquals("uuid-123", r.getConfirmationPoint().getConfirmationId());
        assertEquals("plan", r.getConfirmationPoint().getType());
        assertEquals("需要创建以下任务", r.getConfirmationPoint().getReasoning());
        assertEquals("2 个操作", r.getConfirmationPoint().getSummary());
        assertEquals(1, r.getConfirmationPoint().getPlan().size());
    }

    @Test
    void shouldSerializeAndDeserializeDoneResponse() throws JsonProcessingException {
        AgentResponse r = AgentResponse.done("Hello, world!");

        String json = objectMapper.writeValueAsString(r);
        assertTrue(json.contains("\"type\":\"done\""));
        assertTrue(json.contains("\"reply\":\"Hello, world!\""));

        AgentResponse deserialized = objectMapper.readValue(json, AgentResponse.class);
        assertEquals("done", deserialized.getType());
        assertEquals("Hello, world!", deserialized.getReply());
        assertNull(deserialized.getConfirmationPoint());
    }

    @Test
    void shouldSerializeAndDeserializeErrorResponse() throws JsonProcessingException {
        AgentResponse r = AgentResponse.error("出错了");

        String json = objectMapper.writeValueAsString(r);
        assertTrue(json.contains("\"type\":\"error\""));
        assertTrue(json.contains("\"reply\":\"出错了\""));

        AgentResponse deserialized = objectMapper.readValue(json, AgentResponse.class);
        assertEquals("error", deserialized.getType());
        assertEquals("出错了", deserialized.getReply());
        assertNull(deserialized.getConfirmationPoint());
    }

    @Test
    void shouldSerializeAndDeserializeConfirmationResponse() throws JsonProcessingException {
        ConfirmationPoint cp = new ConfirmationPoint();
        cp.setConfirmationId("uuid-456");
        cp.setConversationId("conv-789");
        cp.setType("exec");
        cp.setTool("createTask");
        cp.setArgs(Map.of("title", "重要任务", "assignee", "张三"));
        cp.setCreatedAt(1234567890L);

        AgentResponse r = AgentResponse.waitConfirm(cp);

        String json = objectMapper.writeValueAsString(r);
        assertTrue(json.contains("\"type\":\"confirmation\""));
        assertTrue(json.contains("\"confirmationId\":\"uuid-456\""));
        assertTrue(json.contains("\"conversationId\":\"conv-789\""));
        assertTrue(json.contains("\"tool\":\"createTask\""));

        AgentResponse deserialized = objectMapper.readValue(json, AgentResponse.class);
        assertEquals("confirmation", deserialized.getType());
        assertNull(deserialized.getReply());
        assertNotNull(deserialized.getConfirmationPoint());
        assertEquals("uuid-456", deserialized.getConfirmationPoint().getConfirmationId());
        assertEquals("conv-789", deserialized.getConfirmationPoint().getConversationId());
        assertEquals("exec", deserialized.getConfirmationPoint().getType());
        assertEquals("createTask", deserialized.getConfirmationPoint().getTool());
        assertNotNull(deserialized.getConfirmationPoint().getArgs());
        assertEquals("重要任务", deserialized.getConfirmationPoint().getArgs().get("title"));
        assertEquals("张三", deserialized.getConfirmationPoint().getArgs().get("assignee"));
        assertEquals(1234567890L, deserialized.getConfirmationPoint().getCreatedAt());
    }

    @Test
    void shouldHandleNullConfirmationPointFields() throws JsonProcessingException {
        ConfirmationPoint cp = new ConfirmationPoint();
        cp.setConfirmationId("uuid-null-test");
        cp.setType("plan");

        AgentResponse r = AgentResponse.waitConfirm(cp);

        String json = objectMapper.writeValueAsString(r);
        AgentResponse deserialized = objectMapper.readValue(json, AgentResponse.class);
        assertEquals("confirmation", deserialized.getType());
        assertNotNull(deserialized.getConfirmationPoint());
        assertEquals("uuid-null-test", deserialized.getConfirmationPoint().getConfirmationId());
        assertNull(deserialized.getConfirmationPoint().getConversationId());
        assertNull(deserialized.getConfirmationPoint().getReasoning());
        assertNull(deserialized.getConfirmationPoint().getPlan());
        assertNull(deserialized.getConfirmationPoint().getTool());
        assertNull(deserialized.getConfirmationPoint().getArgs());
        assertNull(deserialized.getConfirmationPoint().getSummary());
        assertEquals(0L, deserialized.getConfirmationPoint().getCreatedAt());
    }

    @Test
    void shouldHandleConfirmationPointWithFullPlan() throws JsonProcessingException {
        ConfirmationPoint cp = new ConfirmationPoint();
        cp.setConfirmationId("plan-uuid");
        cp.setConversationId("conv-plan");
        cp.setType("plan");
        cp.setReasoning("用户请求创建多个任务和会议");
        cp.setSummary("3 个操作");

        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> op1 = new HashMap<>();
        op1.put("tool", "createTask");
        op1.put("args", Map.of("title", "任务1"));
        plan.add(op1);

        Map<String, Object> op2 = new HashMap<>();
        op2.put("tool", "createTask");
        op2.put("args", Map.of("title", "任务2"));
        plan.add(op2);

        Map<String, Object> op3 = new HashMap<>();
        op3.put("tool", "scheduleMeeting");
        op3.put("args", Map.of("title", "周会", "time", "10:00"));
        plan.add(op3);

        cp.setPlan(plan);
        cp.setCreatedAt(987654321L);

        AgentResponse r = AgentResponse.waitConfirm(cp);

        String json = objectMapper.writeValueAsString(r);
        AgentResponse deserialized = objectMapper.readValue(json, AgentResponse.class);

        assertEquals("confirmation", deserialized.getType());
        ConfirmationPoint dcp = deserialized.getConfirmationPoint();
        assertEquals("plan-uuid", dcp.getConfirmationId());
        assertEquals("conv-plan", dcp.getConversationId());
        assertEquals("plan", dcp.getType());
        assertEquals("用户请求创建多个任务和会议", dcp.getReasoning());
        assertEquals("3 个操作", dcp.getSummary());
        assertEquals(3, dcp.getPlan().size());
        assertEquals("createTask", dcp.getPlan().get(0).get("tool"));
        assertEquals("scheduleMeeting", dcp.getPlan().get(2).get("tool"));
        assertEquals("10:00", ((Map<String, Object>) dcp.getPlan().get(2).get("args")).get("time"));
        assertEquals(987654321L, dcp.getCreatedAt());
    }
}
