package com.deepseek.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolVectorStoreTest {

    private ToolVectorStore store;

    @BeforeEach
    void setUp() {
        store = new ToolVectorStore();
    }

    @Test
    void shouldSaveAndSearchByCosineSimilarity() {
        // 两个平行向量（余弦相似度 ≈ 1）
        store.save("tool-a", new float[]{1, 0, 0});
        // 一个垂直向量（余弦相似度 ≈ 0）
        store.save("tool-b", new float[]{0, 1, 0});

        float[] query = new float[]{0.99f, 0.01f, 0};
        List<ToolVectorStore.ScoredTool> results = store.search(query, 5);

        assertEquals(2, results.size());
        assertEquals("tool-a", results.get(0).getName());
        assertTrue(results.get(0).getScore() > 0.9);
    }

    @Test
    void shouldRespectTopK() {
        store.save("t1", new float[]{1, 0});
        store.save("t2", new float[]{1, 0});
        store.save("t3", new float[]{1, 0});

        List<ToolVectorStore.ScoredTool> results = store.search(new float[]{1, 0}, 2);

        assertEquals(2, results.size());
    }

    @Test
    void shouldReturnEmptyWhenNoTools() {
        List<ToolVectorStore.ScoredTool> results = store.search(new float[]{1, 0}, 5);

        assertTrue(results.isEmpty());
    }
}
