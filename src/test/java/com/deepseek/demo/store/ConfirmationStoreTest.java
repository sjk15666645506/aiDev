package com.deepseek.demo.store;

import com.deepseek.demo.dto.FunctionCall;
import com.deepseek.demo.dto.ToolCall;
import com.deepseek.demo.store.ConfirmationStore.ConfirmationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfirmationStore 的单元测试。
 * 覆盖 plan/exec 确认点的创建、获取、消费和过期清理功能。
 */
class ConfirmationStoreTest {

    private ConfirmationStore store;

    @BeforeEach
    void setUp() {
        store = new ConfirmationStore();
    }

    @AfterEach
    void tearDown() {
        store.shutdown();
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
        assertEquals("conv-1", state.conversationId);
        assertEquals("plan", state.type);
        assertEquals(1, state.planToolCalls.size());
        assertEquals("createTask", state.planToolCalls.get(0).get("action"));
        assertFalse(state.consumed);
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
        assertEquals("conv-1", state.conversationId);
        assertEquals("exec", state.type);
        assertEquals("createTask", state.toolName);
        assertEquals("call_123", state.toolCallId);
        assertEquals("{\"title\":\"测试\"}", state.toolArguments);
        assertEquals(1, state.pendingToolCalls.size());
        assertFalse(state.consumed);
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
        // 消费不存在的确认点不应抛出异常
        store.consume("non-existent");
    }

    @Test
    void shouldExpireOldConfirmations() {
        String confirmationId = store.createPlanConfirmation("conv-1",
                Arrays.asList(Collections.singletonMap("action", "test")));

        // 立即清理 — 还未过期，应保留
        store.cleanupExpired();
        assertNotNull(store.get(confirmationId));
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
        assertEquals("plan", store.get(planId).type);
        assertEquals("exec", store.get(execId).type);
    }

    @Test
    void shouldCreateExecConfirmationWithNullToolCall() {
        String confirmationId = store.createExecConfirmation("conv-1", null, new ArrayList<>());
        ConfirmationState state = store.get(confirmationId);
        assertNotNull(state);
        assertEquals("exec", state.type);
        assertNull(state.toolName);
        assertNull(state.toolCallId);
        assertNull(state.toolArguments);
    }

    @Test
    void shouldCreateExecConfirmationWithToolCallHavingNullFunction() {
        ToolCall toolCall = new ToolCall("call_456", "function", null);
        String confirmationId = store.createExecConfirmation("conv-1", toolCall, new ArrayList<>());

        ConfirmationState state = store.get(confirmationId);
        assertNotNull(state);
        assertEquals("exec", state.type);
        assertNull(state.toolName);
        assertNull(state.toolArguments);
        assertEquals("call_456", state.toolCallId);
    }

    @Test
    void shouldNotReturnExpiredConfirmation() {
        String confirmationId = store.createPlanConfirmation("conv-1",
                Arrays.asList(Collections.singletonMap("action", "test")));

        // 直接修改状态的创建时间使其过期
        ConfirmationState state = store.get(confirmationId);
        assertNotNull(state);
        state.createdAt = System.currentTimeMillis() - 10 * 60 * 1000; // 10分钟前

        // get 方法应该检测到并返回 null
        assertNull(store.get(confirmationId));
    }
}
