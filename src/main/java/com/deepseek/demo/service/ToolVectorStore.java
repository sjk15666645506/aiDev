package com.deepseek.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具 Embedding 内存向量存储。
 * <p>
 * 在系统启动时接收所有工具的 Name + Description 的 Embedding 向量，
 * 并提供余弦相似度检索，供 ToolRetriever 按语义召回相关工具。
 * 纯内存实现，无需 Qdrant 等外部依赖。
 */
@Component
public class ToolVectorStore {

    private static final Logger log = LoggerFactory.getLogger(ToolVectorStore.class);

    /** 工具名称 → Embedding 向量 */
    private final Map<String, float[]> store = new ConcurrentHashMap<>();

    /**
     * 保存工具的 Embedding 向量。
     */
    public void save(String toolName, float[] vector) {
        store.put(toolName, vector);
    }

    /**
     * 从存储中移除工具。
     */
    public void remove(String toolName) {
        store.remove(toolName);
    }

    /**
     * 清空所有向量。
     */
    public void clear() {
        store.clear();
    }

    /**
     * 向量数量。
     */
    public int size() {
        return store.size();
    }

    /**
     * 余弦相似度检索，按相似度降序返回。
     */
    public List<ScoredTool> search(float[] query, int topK) {
        if (store.isEmpty() || topK <= 0) {
            return Collections.emptyList();
        }

        List<ScoredTool> results = store.entrySet().stream()
                .map(entry -> new ScoredTool(
                        entry.getKey(),
                        cosineSimilarity(query, entry.getValue())))
                .sorted((a, b) -> Float.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .collect(Collectors.toList());

        if (results.isEmpty() || results.get(0).getScore() <= 0) {
            log.warn("语义检索未找到匹配结果: 最高得分={}",
                    results.isEmpty() ? "N/A" : results.get(0).getScore());
        }

        return results;
    }

    /**
     * 计算两个向量的余弦相似度。
     */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不匹配: " + a.length + " vs " + b.length);
        }

        float dotProduct = 0;
        float normA = 0;
        float normB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        float denominator = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        if (denominator == 0) return 0;

        return dotProduct / denominator;
    }

    /**
     * 带得分的检索结果。
     */
    public static class ScoredTool {
        private final String name;
        private final float score;

        public ScoredTool(String name, float score) {
            this.name = name;
            this.score = score;
        }

        public String getName() { return name; }
        public float getScore() { return score; }
    }
}
