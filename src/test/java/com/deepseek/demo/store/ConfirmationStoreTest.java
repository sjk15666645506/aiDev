package com.deepseek.demo.store;

import com.deepseek.demo.dto.FunctionCall;
import com.deepseek.demo.dto.ToolCall;
import com.deepseek.demo.store.ConfirmationStore.ConfirmationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfirmationStore 的集成测试（Redis 后端）。
 * 覆盖 plan/exec 确认点的创建、获取和消费功能。
 * 过期清理由 Redis TTL 自动完成，不在此处测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConfirmationStoreTest {

    @Autowired
    private ConfirmationStore store;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 清理测试数据 */
    @AfterEach
    void tearDown() {
        Set<String> keys = redisTemplate.keys("confirmation:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void shouldCreatePlanConfirmation() {
        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("action", "createTask");
        step.put("params", "{\"title\":\"测试任务\"}");
        plan.add(step);

        String confirmationId = store.createPlanConfirmation("conv-1", plan);
        assertNotNull(confirmationId);

        ConfirmationState state = store.get(confirmationId);
        assertNotNull(state);
        assertEquals("conv-1", state.getConversationId());
        assertEquals("plan", state.getType());
        assertEquals(1, state.getPlanToolCalls().size());
        assertEquals("createTask", state.getPlanToolCalls().get(0).get("action"));
        assertFalse(state.isConsumed());
    }

    @Test
    void shouldCreateExecConfirmation() {
        FunctionCall functionCall = new FunctionCall("createTask", "{\"title\":\"测试\"}");
        ToolCall toolCall = new ToolCall("call_123", "function", functionCall);
        List<ToolCall> pendingToolCalls = Arrays.asList(toolCall);

        String confirmationId = store.createExecConfirmation("conv-1", toolCall, pendingToolCalls);
        assertNotNull(confirmationId);

        ConfirmationState state = store.get(confirmationId);
        assertNotNull(state);
        assertEquals("conv-1", state.getConversationId());
        assertEquals("exec", state.getType());
        assertEquals("createTask", state.getToolName());
        assertEquals("call_123", state.getToolCallId());
        assertEquals("{\"title\":\"测试\"}", state.getToolArguments());
        assertEquals(1, state.getPendingToolCalls().size());
        assertFalse(state.isConsumed());
    }

    @Test
    void shouldReturnNullForNonExistentConfirmation() {
        assertNull(store.get("non-existent"));
    }

    @Test
    void shouldConsumeConfirmation() {
        String confirmationId = store.createPlanConfirmation("conv-1",
                Arrays.asList(Collections.singletonMap("action", "test")));

        assertFalse(store.isConsumed(confirmationId));

        store.consume(confirmationId);
        assertTrue(store.isConsumed(confirmationId));
    }

    @Test
    void shouldReturnFalseForIsConsumedOnNonExistent() {
        assertFalse(store.isConsumed("non-existent"));
    }

    @Test
    void shouldConsumeNonExistentConfirmationGracefully() {
        store.consume("non-existent");
    }

    @Test
    void shouldHandleMultipleConfirmations() {
        String planId = store.createPlanConfirmation("conv-1",
                Arrays.asList(Collections.singletonMap("action", "plan1")));
        String execId = store.createExecConfirmation("conv-1",
                new ToolCall("call_1", "function", new FunctionCall("tool1", "{}")),
                new ArrayList<>());

        assertNotNull(store.get(planId));
        assertNotNull(store.get(execId));
        assertEquals("plan", store.get(planId).getType());
        assertEquals("exec", store.get(execId).getType());
    }

    @Test
    void shouldCreateExecConfirmationWithNullToolCall() {
        String confirmationId = store.createExecConfirmation("conv-1", null, new ArrayList<>());
        ConfirmationState state = store.get(confirmationId);
        assertNotNull(state);
        assertEquals("exec", state.getType());
        assertNull(state.getToolName());
        assertNull(state.getToolCallId());
        assertNull(state.getToolArguments());
    }

    @Test
    void shouldCreateExecConfirmationWithToolCallHavingNullFunction() {
        ToolCall toolCall = new ToolCall("call_456", "function", null);
        String confirmationId = store.createExecConfirmation("conv-1", toolCall, new ArrayList<>());

        ConfirmationState state = store.get(confirmationId);
        assertNotNull(state);
        assertEquals("exec", state.getType());
        assertNull(state.getToolName());
        assertNull(state.getToolArguments());
        assertEquals("call_456", state.getToolCallId());
    }
}
