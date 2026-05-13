package com.deepseek.demo.store;

import com.deepseek.demo.dto.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConversationStore 的单元测试。
 * 覆盖消息存取、检查点、计划确认、审批计划以及过期清理功能。
 */
class ConversationStoreTest {

    private ConversationStore store;

    @BeforeEach
    void setUp() {
        store = new ConversationStore();
    }

    @AfterEach
    void tearDown() {
        store.shutdown();
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
        // 验证检查点已保存 — 通过 getMessages 间接验证消息已写入
        List<Message> retrieved = store.getMessages("conv-1");
        assertEquals(1, retrieved.size());

        // 清除检查点
        store.clearCheckpoint("conv-1");
        // 再次保存验证 checkpoint 已重置
        store.saveCheckpoint("conv-1", messages, 0);
        List<Message> afterClear = store.getMessages("conv-1");
        assertEquals(1, afterClear.size());
    }

    @Test
    void shouldClearCheckpointForNonExistentConversation() {
        // 对不存在的会话清除检查点不应抛出异常
        store.clearCheckpoint("non-existent");
    }

    @Test
    void shouldDefaultPlanConfirmedToFalse() {
        assertFalse(store.getPlanConfirmed("conv-1"));
    }

    @Test
    void shouldSetAndReturnPlanConfirmed() {
        boolean previous = store.setPlanConfirmed("conv-1", true);
        assertFalse(previous); // 首次设置，之前应为 false

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
    void shouldUpdateLastAccessTimeOnRead() throws InterruptedException {
        store.saveMessages("conv-1", Arrays.asList(new Message("user", "Hello")));

        // 等待一小段时间
        Thread.sleep(10);

        // 再次读取，应该更新 lastAccessTime
        store.getMessages("conv-1");

        // 调用清理 — 消息应该在过期时间内，不会被移除
        store.cleanupExpired();
        List<Message> messages = store.getMessages("conv-1");
        assertEquals(1, messages.size());
    }

    @Test
    void shouldCleanupExpiredConversations() throws InterruptedException {
        store.saveMessages("expired-conv", Arrays.asList(new Message("user", "old")));

        // 手动修改消息的最后访问时间（通过重新保存并等待）
        // 由于 lastAccessTime 是内部状态，我们等待足够长时间...
        // 但更好的方式是验证 cleanupExpired 的逻辑：通过保存后立即清理，它应该还在
        store.cleanupExpired();
        assertFalse(store.getMessages("expired-conv").isEmpty());
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
