package com.deepseek.demo.service;

import com.deepseek.demo.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class VectorService implements IVectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorService.class);

    private final QdrantClient qdrantClient;
    private final EmbeddingClient embeddingClient;
    private final MeiliSearchService meiliSearchService;

    public VectorService(QdrantClient qdrantClient,
                         EmbeddingClient embeddingClient,
                         MeiliSearchService meiliSearchService) {
        this.qdrantClient = qdrantClient;
        this.embeddingClient = embeddingClient;
        this.meiliSearchService = meiliSearchService;
    }

    // ═══════════════════════════════════════════
    //  Hybrid Search
    // ═══════════════════════════════════════════

    private static final double VECTOR_WEIGHT = 0.6;

    static List<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) return List.of();
        Set<String> keywords = new LinkedHashSet<>();
        for (String term : query.split("[\\s,，。；;：:、？！!?（）()（）+＋\\-\\-\\-＝=．.\\/\\\\\"\"''【】\\[\\]{}「」【】…《》<>·]+")) {
            term = term.strip();
            if (term.isEmpty()) continue;
            if (term.matches("[a-zA-Z0-9_\\-+.]+")) {
                keywords.add(term.toLowerCase());
            } else if (term.length() >= 2 && term.matches("[\\u4e00-\\u9fff]+")) {
                keywords.add(term);
            } else if (term.length() >= 2) {
                keywords.add(term);
            }
        }
        return List.copyOf(keywords);
    }

    private static double computeKeywordScore(String text, List<String> keywords) {
        if (keywords.isEmpty() || text == null || text.isBlank()) return 0;
        String lowerText = text.toLowerCase();
        int matches = 0;
        for (String kw : keywords) {
            if (lowerText.contains(kw)) matches++;
        }
        return (double) matches / keywords.size();
    }

    public List<Map<String, Object>> searchHybrid(String question, int limit,
                                                   String collection, String vectorName) {
        log.info("混合检索: collection={}, question={}",
                collection, StringUtils.truncate(question, 50));

        try {
            long start = System.currentTimeMillis();

            float[] vector = embeddingClient.embed(question);
            List<Map<String, Object>> qdrantResults = qdrantClient.search(
                    collection, vector, limit * 3, vectorName);

            List<String> keywords = extractKeywords(question);
            if (!keywords.isEmpty()) {
                for (Map<String, Object> cand : qdrantResults) {
                    String text = (String) cand.getOrDefault("text", "");
                    double kwScore = computeKeywordScore(text, keywords);
                    double vecScore = ((Number) cand.getOrDefault("score", 0.0)).doubleValue();
                    cand.put("hybrid_score", VECTOR_WEIGHT * vecScore + (1 - VECTOR_WEIGHT) * kwScore);
                }
                qdrantResults.sort((a, b) -> Double.compare(
                        ((Number) b.getOrDefault("hybrid_score", 0.0)).doubleValue(),
                        ((Number) a.getOrDefault("hybrid_score", 0.0)).doubleValue()));
            }

            List<Map<String, Object>> meiliResults = meiliSearchService.search(
                    collection, question, limit * 3);

            List<Map<String, Object>> merged = rrfMerge(qdrantResults, meiliResults, limit);

            long elapsed = System.currentTimeMillis() - start;
            log.info("混合检索完成: Qdrant={}条, Meilisearch={}条, 合并后={}条, 耗时={}ms",
                    qdrantResults.size(), meiliResults.size(), merged.size(), elapsed);
            return merged;
        } catch (Exception e) {
            log.error("混合检索失败: {} — {}", StringUtils.truncate(question, 50), e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> rrfMerge(List<Map<String, Object>> qdrantResults,
                                                List<Map<String, Object>> meiliResults,
                                                int limit) {
        int k = 60;
        Map<String, Double> rrfMap = new LinkedHashMap<>();
        for (int i = 0; i < qdrantResults.size(); i++) {
            String id = (String) qdrantResults.get(i).get("id");
            if (id != null) rrfMap.merge(id, 1.0 / (k + i), Double::sum);
        }
        for (int i = 0; i < meiliResults.size(); i++) {
            String id = (String) meiliResults.get(i).get("id");
            if (id != null) rrfMap.merge(id, 1.0 / (k + i), Double::sum);
        }

        List<String> sortedIds = rrfMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        Map<String, Map<String, Object>> allDocs = new LinkedHashMap<>();
        for (Map<String, Object> doc : qdrantResults) {
            String id = (String) doc.get("id");
            if (id != null) allDocs.put(id, doc);
        }
        for (Map<String, Object> doc : meiliResults) {
            String id = (String) doc.get("id");
            if (id != null) allDocs.putIfAbsent(id, doc);
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        for (String id : sortedIds) {
            Map<String, Object> doc = allDocs.get(id);
            if (doc != null) {
                doc.put("score", rrfMap.get(id));
                merged.add(doc);
            }
        }
        return merged;
    }

    // ═══════════════════════════════════════════
    //  Doc Search
    // ═══════════════════════════════════════════

    public List<Map<String, Object>> searchDocs(String question, int limit) {
        log.info("搜索文档库: collection={}, question={}, limit={}",
                qdrantClient.getDocCollection(), StringUtils.truncate(question, 50), limit);
        try {
            float[] vector = embeddingClient.embed(question);
            List<Map<String, Object>> results = qdrantClient.search(
                    qdrantClient.getDocCollection(), vector, limit, qdrantClient.getDocVectorName());
            log.info("文档搜索完成: 结果数={}", results.size());
            return results;
        } catch (Exception e) {
            log.error("文档库搜索失败: {} — {}",
                    StringUtils.truncate(question, 50), e.getMessage());
            return Collections.emptyList();
        }
    }

    // ═══════════════════════════════════════════
    //  Upsert
    // ═══════════════════════════════════════════

    public void upsertDoc(String id, String text, String fileId, int chunkIndex, int totalChunks) {
        float[] vector = embeddingClient.embed(text);
        qdrantClient.upsert(id, vector, text, fileId, chunkIndex, totalChunks);
    }

    // ═══════════════════════════════════════════
    //  Full Content / Scroll
    // ═══════════════════════════════════════════

    public String fetchCompleteDocContent(String fileId) {
        return qdrantClient.scrollAndAssemble(qdrantClient.getDocCollection(), fileId);
    }

    public List<Map<String, Object>> searchDocsWithFullContent(String question, int limit) {
        List<Map<String, Object>> results = searchHybrid(
                question, limit * 2, qdrantClient.getDocCollection(), qdrantClient.getDocVectorName());
        if (results.isEmpty()) return results;

        try {
            Map<String, int[]> fileRanges = new LinkedHashMap<>();
            for (Map<String, Object> r : results) {
                String fp = (String) r.get("file_path");
                int chunkIdx = r.containsKey("chunk_index")
                        ? ((Number) r.get("chunk_index")).intValue() : 0;
                if (fp != null) {
                    fileRanges.compute(fp, (k, existing) -> {
                        if (existing == null) return new int[]{chunkIdx, chunkIdx};
                        existing[0] = Math.min(existing[0], chunkIdx);
                        existing[1] = Math.max(existing[1], chunkIdx);
                        return existing;
                    });
                }
            }

            int window = 2;
            Map<String, String> contextMap = new HashMap<>();
            for (Map.Entry<String, int[]> entry : fileRanges.entrySet()) {
                String fp = entry.getKey();
                int minIdx = entry.getValue()[0];
                int maxIdx = entry.getValue()[1];
                String context = qdrantClient.scrollWithRange(
                        qdrantClient.getDocCollection(), fp,
                        Math.max(0, minIdx - window), maxIdx + window);
                if (context != null) contextMap.put(fp, context);
            }

            for (Map<String, Object> r : results) {
                String fp = (String) r.get("file_path");
                if (fp != null && contextMap.containsKey(fp)) {
                    r.put("text", contextMap.get(fp));
                }
            }
        } catch (Exception e) {
            log.warn("上下文扩展失败，返回原始搜索结果: {}", e.getMessage());
        }

        return results;
    }

    // ═══════════════════════════════════════════
    //  Misc
    // ═══════════════════════════════════════════

    public int getQdrantPort() {
        return qdrantClient.getQdrantPort();
    }
}
