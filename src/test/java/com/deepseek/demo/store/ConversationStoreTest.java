package com.deepseek.demo.store;

import com.deepseek.demo.dto.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConversationStore 的集成测试（Redis 后端）。
 * 覆盖消息存取、检查点、计划确认和审批计划功能。
 * 过期清理由 Redis TTL 自动完成，不在此处测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConversationStoreTest {

    @Autowired
    private ConversationStore store;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 清理测试数据 */
    @AfterEach
    void tearDown() {
        Set<String> keys = redisTemplate.keys("conversation:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void shouldReturnEmptyListWhenConversationNotFound() {
        List<Message> messages = store.getMessages("non-existent");
        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    @Test
    void shouldSaveAndRetrieveMessages() {
        List<Message> messages = Arrays.asList(
                new Message("user", "你好"),
                new Message("assistant", "你好！有什么可以帮助你的吗？")
        );

        store.saveMessages("conv-1", messages);
        List<Message> retrieved = store.getMessages("conv-1");

        assertEquals(2, retrieved.size());
        assertEquals("user", retrieved.get(0).getRole());
        assertEquals("你好", retrieved.get(0).getContent());
        assertEquals("assistant", retrieved.get(1).getRole());
    }

    @Test
    void shouldOverwriteExistingMessages() {
        List<Message> initial = Arrays.asList(new Message("user", "Hello"));
        store.saveMessages("conv-1", initial);

        List<Message> updated = Arrays.asList(
                new Message("user", "Hi"),
                new Message("assistant", "How can I help?")
        );
        store.saveMessages("conv-1", updated);

        List<Message> retrieved = store.getMessages("conv-1");
        assertEquals(2, retrieved.size());
        assertEquals("Hi", retrieved.get(0).getContent());
    }

    @Test
    void shouldSaveAndClearCheckpoint() {
        List<Message> messages = Arrays.asList(new Message("user", "创建任务"));

        store.saveCheckpoint("conv-1", messages, 5);
        List<Message> retrieved = store.getMessages("conv-1");
        assertEquals(1, retrieved.size());

        store.clearCheckpoint("conv-1");
        store.saveCheckpoint("conv-1", messages, 0);
        List<Message> afterClear = store.getMessages("conv-1");
        assertEquals(1, afterClear.size());
    }

    @Test
    void shouldClearCheckpointForNonExistentConversation() {
        store.clearCheckpoint("non-existent");
    }

    @Test
    void shouldDefaultPlanConfirmedToFalse() {
        assertFalse(store.getPlanConfirmed("conv-1"));
    }

    @Test
    void shouldSetAndReturnPlanConfirmed() {
        boolean previous = store.setPlanConfirmed("conv-1", true);
        assertFalse(previous);

        assertTrue(store.getPlanConfirmed("conv-1"));
    }

    @Test
    void shouldReturnPreviousPlanConfirmedState() {
        store.setPlanConfirmed("conv-1", true);
        boolean previous = store.setPlanConfirmed("conv-1", false);

        assertTrue(previous);
        assertFalse(store.getPlanConfirmed("conv-1"));
    }

    @Test
    void shouldReturnNullApprovedPlanWhenNotSet() {
        assertNull(store.getApprovedPlan("conv-1"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldSetAndGetApprovedPlan() {
        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step1 = new HashMap<>();
        step1.put("action", "createTask");
        step1.put("params", "{\"title\":\"测试\"}");
        plan.add(step1);

        store.setApprovedPlan("conv-1", plan);
        List<Map<String, Object>> retrieved = store.getApprovedPlan("conv-1");

        assertNotNull(retrieved);
        assertEquals(1, retrieved.size());
        assertEquals("createTask", retrieved.get(0).get("action"));
    }

    @Test
    void shouldHandleMultipleConversations() {
        store.saveMessages("conv-A", Arrays.asList(new Message("user", "A")));
        store.saveMessages("conv-B", Arrays.asList(new Message("user", "B")));
        store.saveMessages("conv-C", Arrays.asList(new Message("user", "C")));

        assertEquals(1, store.getMessages("conv-A").size());
        assertEquals(1, store.getMessages("conv-B").size());
        assertEquals(1, store.getMessages("conv-C").size());

        store.clearCheckpoint("conv-B");
        store.setPlanConfirmed("conv-A", true);
        assertTrue(store.getPlanConfirmed("conv-A"));
        assertFalse(store.getPlanConfirmed("conv-B"));
    }

    @Test
    void shouldHandleMixedOperationsOnSameConversation() {
        List<Message> messages = Arrays.asList(new Message("user", "请创建一个任务"));
        store.saveMessages("conv-1", messages);

        store.setPlanConfirmed("conv-1", true);

        List<Map<String, Object>> plan = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("tool", "createTask");
        step.put("args", "{\"title\":\"Demo\"}");
        plan.add(step);
        store.setApprovedPlan("conv-1", plan);

        assertTrue(store.getPlanConfirmed("conv-1"));
        assertNotNull(store.getApprovedPlan("conv-1"));
        assertEquals(1, store.getApprovedPlan("conv-1").size());
        assertEquals("createTask", store.getApprovedPlan("conv-1").get(0).get("tool"));
    }
}
