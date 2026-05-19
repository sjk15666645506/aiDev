package com.deepseek.demo.service;

import java.util.*;

public interface IVectorSearchService {
    int MAX_CONTEXT_CHARS = 12000;

    List<Map<String, Object>> searchHybrid(String question, int limit, String collection, String vectorName);
    List<Map<String, Object>> searchDocs(String question, int limit);
    List<Map<String, Object>> searchDocsWithFullContent(String question, int limit);
    void upsertDoc(String id, String text, String fileId, int chunkIndex, int totalChunks);
    String fetchCompleteDocContent(String fileId);
    int getQdrantPort();

    static List<Map<String, Object>> truncateContexts(List<Map<String, Object>> contexts, int maxChars) {
        if (contexts == null || contexts.isEmpty()) return contexts;
        if (maxChars <= 0) maxChars = MAX_CONTEXT_CHARS;

        List<Map<String, Object>> sorted = new ArrayList<>(contexts);
        sorted.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()));

        List<Map<String, Object>> result = new ArrayList<>();
        int total = 0;
        for (Map<String, Object> ctx : sorted) {
            String text = (String) ctx.get("text");
            int len = text != null ? text.length() : 0;
            if (result.isEmpty() || total + len <= maxChars) {
                result.add(ctx);
                total += len;
            } else {
                break;
            }
        }
        return result;
    }
}
