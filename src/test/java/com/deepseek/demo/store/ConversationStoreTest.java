package com.deepseek.demo.store;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConversationStoreTest {

    @Autowired
    private ConversationStore store;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        Set<String> keys = redisTemplate.keys("conversation:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void shouldReturnEmptyListWhenConversationNotFound() {
        List<ChatMessage> messages = store.getMessages("non-existent");
        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    @Test
    void shouldSaveAndRetrieveMessages() {
        List<ChatMessage> messages = Arrays.asList(
                UserMessage.from("你好"),
                AiMessage.from("你好！有什么可以帮助你的吗？")
        );

        store.saveMessages("conv-1", messages);
        List<ChatMessage> retrieved = store.getMessages("conv-1");

        assertEquals(2, retrieved.size());
        assertTrue(retrieved.get(0) instanceof UserMessage);
        assertEquals("你好", ((UserMessage) retrieved.get(0)).singleText());
        assertTrue(retrieved.get(1) instanceof AiMessage);
    }

    @Test
    void shouldOverwriteExistingMessages() {
        List<ChatMessage> initial = Arrays.asList(UserMessage.from("Hello"));
        store.saveMessages("conv-1", initial);

        List<ChatMessage> updated = Arrays.asList(
                UserMessage.from("Hi"),
                AiMessage.from("How can I help?")
        );
        store.saveMessages("conv-1", updated);

        List<ChatMessage> retrieved = store.getMessages("conv-1");
        assertEquals(2, retrieved.size());
        assertEquals("Hi", ((UserMessage) retrieved.get(0)).singleText());
    }

    @Test
    void shouldSaveAndClearCheckpoint() {
        List<ChatMessage> messages = Arrays.asList(UserMessage.from("创建任务"));

        store.saveCheckpoint("conv-1", messages, 5);
        List<ChatMessage> retrieved = store.getMessages("conv-1");
        assertEquals(1, retrieved.size());

        store.clearCheckpoint("conv-1");
        store.saveCheckpoint("conv-1", messages, 0);
        List<ChatMessage> afterClear = store.getMessages("conv-1");
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
        store.saveMessages("conv-A", Arrays.asList(UserMessage.from("A")));
        store.saveMessages("conv-B", Arrays.asList(UserMessage.from("B")));
        store.saveMessages("conv-C", Arrays.asList(UserMessage.from("C")));

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
        List<ChatMessage> messages = Arrays.asList(UserMessage.from("请创建一个任务"));
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
