package com.deepseek.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class VectorService {

    private static final Logger log = LoggerFactory.getLogger(VectorService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final String qdrantHost;
    private final int qdrantPort;
    private final String docCollection;

    private final String ollamaHost;
    private final int ollamaPort;
    private final String ollamaModel;

    /**
     * docCollection 的向量名称（null 表示无名/默认向量）
     */
    private String docVectorName;

    private final MeiliSearchService meiliSearchService;

    /**
     * Embedding 缓存：相同文本 5 分钟内不重复调用 Ollama
     */
    private final Map<String, EmbeddingCacheEntry> embedCache = new ConcurrentHashMap<>();
    private static final long EMBED_CACHE_TTL_SEC = 300;

    private static class EmbeddingCacheEntry {
        final float[] vector;
        final long expiresAt;

        EmbeddingCacheEntry(float[] vector, long expiresAt) {
            this.vector = vector;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * 构造 VectorService
     *
     * @param restTemplate       HTTP 客户端（带连接池）
     * @param objectMapper       JSON 序列化/反序列化
     * @param qdrantHost         Qdrant 主机地址（${qdrant.host}）
     * @param qdrantPort         Qdrant 端口（${qdrant.port}）
     * @param docCollection      文档知识库 collection 名（${qdrant.doc-collection}）
     * @param ollamaHost         Ollama 主机地址（${ollama.host}）
     * @param ollamaPort         Ollama API 端口（${ollama.port}）
     * @param ollamaModel        Embedding 模型名（${ollama.model}，默认 nomic-embed-text）
     * @param meiliSearchService Meilisearch 全文搜索服务
     */
    public VectorService(RestTemplate restTemplate,
                         ObjectMapper objectMapper,
                         @Value("${qdrant.host}") String qdrantHost,
                         @Value("${qdrant.port}") int qdrantPort,
                         @Value("${qdrant.doc-collection}") String docCollection,
                         @Value("${ollama.host}") String ollamaHost,
                         @Value("${ollama.port}") int ollamaPort,
                         @Value("${ollama.model}") String ollamaModel,
                         MeiliSearchService meiliSearchService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.qdrantHost = qdrantHost;
        this.qdrantPort = qdrantPort;
        this.docCollection = docCollection;
        this.ollamaHost = ollamaHost;
        this.ollamaPort = ollamaPort;
        this.ollamaModel = ollamaModel;
        this.meiliSearchService = meiliSearchService;
    }

    @PostConstruct
    public void init() {
        try {
            docVectorName = detectVectorName(docCollection);
            if (docVectorName == null && !collectionExists(docCollection)) {
                createCollection(docCollection);
                docVectorName = null;
            }
            log.info("向量库初始化完成: doc={}(vector={})", docCollection, docVectorName);
        } catch (Exception e) {
            log.warn("向量库初始化失败，将在首次搜索时重试: {}", e.getMessage());
        }
    }

    /**
     * 检测 collection 的向量名称，null 表示无名/默认向量
     *
     * @param collection Qdrant collection 名称
     * @return 向量名称，无名向量返回 null
     */
    private String detectVectorName(String collection) {
        String url = "http://" + qdrantHost + ":" + qdrantPort + "/collections/" + collection;
        try {
            ResponseEntity<JsonNode> resp = restTemplate.getForEntity(url, JsonNode.class);
            if (!resp.getStatusCode().is2xxSuccessful()) return null;
            JsonNode vectors = resp.getBody().path("result").path("config").path("params").path("vectors");
            if (vectors.isObject() && vectors.has("size")) {
                return null; // 无名向量: {"size": 768, "distance": "Cosine"}
            }
            // 命名向量: {"dense": {"size": 768, ...}}
            Iterator<String> it = vectors.fieldNames();
            if (it.hasNext()) return it.next();
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 检查 collection 是否已存在
     *
     * @param collection Qdrant collection 名称
     * @return true 已存在
     */
    private boolean collectionExists(String collection) {
        String url = "http://" + qdrantHost + ":" + qdrantPort + "/collections/" + collection;
        try {
            ResponseEntity<JsonNode> resp = restTemplate.getForEntity(url, JsonNode.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 创建 Qdrant collection（768 维 Cosine 距离）
     *
     * @param collection 要创建的 collection 名称
     */
    private void createCollection(String collection) {
        String url = "http://" + qdrantHost + ":" + qdrantPort + "/collections/" + collection;
        try {
            ResponseEntity<JsonNode> resp = restTemplate.getForEntity(url, JsonNode.class);
            if (resp.getStatusCode().is2xxSuccessful()) {
                log.info("Collection已存在: {}", collection);
                return;
            }
        } catch (Exception ignored) {
        }

        String createUrl = "http://" + qdrantHost + ":" + qdrantPort + "/collections/" + collection;
        Map<String, Object> vectorsConfig = new HashMap<>();
        vectorsConfig.put("size", 768);
        vectorsConfig.put("distance", "Cosine");
        Map<String, Object> body = new HashMap<>();
        body.put("vectors", vectorsConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        restTemplate.exchange(createUrl, HttpMethod.PUT, entity, JsonNode.class);
        log.info("Collection创建成功: {}", collection);
    }

    /**
     * 调用 Ollama 生成文本的 embedding 向量（带 5 分钟缓存）
     *
     * @param text 输入文本
     * @return 768 维 float 向量
     */
    public float[] embed(String text) {
        // 缓存命中直接返回
        EmbeddingCacheEntry cached = embedCache.get(text);
        if (cached != null && cached.expiresAt > Instant.now().getEpochSecond()) {
            log.debug("Embedding缓存命中: text={}", truncate(text, 50));
            return cached.vector;
        }

        String url = "http://" + ollamaHost + ":" + ollamaPort + "/api/embed";
        log.info("生成embedding: text={}", truncate(text, 50));

        Map<String, Object> body = new HashMap<>();
        body.put("model", ollamaModel);
        body.put("input", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        long start = System.currentTimeMillis();
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(url, entity, JsonNode.class);
        long elapsed = System.currentTimeMillis() - start;

        JsonNode embeddings = response.getBody().path("embeddings");
        if (embeddings.isArray() && !embeddings.isEmpty()) {
            JsonNode vector = embeddings.get(0);
            float[] result = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                result[i] = (float) vector.get(i).asDouble();
            }
            log.debug("Embedding完成: 维度={}, 耗时={}ms", result.length, elapsed);
            // 写入缓存
            embedCache.put(text, new EmbeddingCacheEntry(result,
                    Instant.now().getEpochSecond() + EMBED_CACHE_TTL_SEC));
            // 惰性淘汰：缓存超过 1000 条时清理过期项
            if (embedCache.size() > 1000) {
                embedCache.values().removeIf(e -> e.expiresAt <= Instant.now().getEpochSecond());
            }
            return result;
        }
        throw new RuntimeException("Embedding失败: " + response.getBody());
    }

    // ========== 混合检索（关键词加权 + RRF） ==========

    /**
     * 向量得分权重（关键词得分权重 = 1 - VECTOR_WEIGHT）
     */
    private static final double VECTOR_WEIGHT = 0.6;

    /**
     * 从查询中提取关键词：按空白和标点分割，保留英文/数字词和中文字段（>=2字）
     */
    static List<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) return List.of();
        Set<String> keywords = new LinkedHashSet<>();
        // 先按空白和常见标点切分
        for (String term : query.split("[\\s,，。；;：:、？！!?（）()（）+＋\\-\\-\\-＝=．.\\/\\\\\"\"''【】\\[\\]{}「」【】…《》<>·]+")) {
            term = term.strip();
            if (term.isEmpty()) continue;
            // 英文/数字/符号词（含中划线 dot 等）
            if (term.matches("[a-zA-Z0-9_\\-+.]+")) {
                keywords.add(term.toLowerCase());
            }
            // 中文词（>=2 字才有区分度）
            else if (term.length() >= 2 && term.matches("[\\u4e00-\\u9fff]+")) {
                keywords.add(term);
            }
            // 中英混合：保留原样
            else if (term.length() >= 2) {
                keywords.add(term);
            }
        }
        return List.copyOf(keywords);
    }

    /**
     * 计算关键词匹配得分（0～1），按出现比例
     */
    private static double computeKeywordScore(String text, List<String> keywords) {
        if (keywords.isEmpty() || text == null || text.isBlank()) return 0;
        String lowerText = text.toLowerCase();
        int matches = 0;
        for (String kw : keywords) {
            if (lowerText.contains(kw)) matches++;
        }
        return (double) matches / keywords.size();
    }

    /**
     * 混合检索：向量搜索 + 关键词加权重排序
     *
     * @param question   用户问题
     * @param limit      返回条数
     * @param collection collection 名称
     * @param vectorName 向量名称
     * @return 重排后的结果，每条带上 hybrid_score 字段
     */
    public List<Map<String, Object>> searchHybrid(String question, int limit,
                                                  String collection, String vectorName) {
        log.info("混合检索: collection={}, question={}", collection, truncate(question, 50));

        try {
            long start = System.currentTimeMillis();

            // ── 1. Qdrant 向量搜索 + 关键词加权 ──
            List<Map<String, Object>> qdrantResults = searchQdrant(question, limit * 3, collection, vectorName);

            // ── 2. Meilisearch 全文搜索 ──
            List<Map<String, Object>> meiliResults = meiliSearchService.search(collection, question, limit * 3);

            // ── 3. 两路合并：RRF ──
            List<Map<String, Object>> merged = rrfMerge(qdrantResults, meiliResults, limit);

            long elapsed = System.currentTimeMillis() - start;
            log.info("混合检索完成: Qdrant={}条, Meilisearch={}条, 合并后={}条, 耗时={}ms",
                    qdrantResults.size(), meiliResults.size(), merged.size(), elapsed);
            return merged;
        } catch (Exception e) {
            log.error("混合检索失败: {} — {}", truncate(question, 50), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Qdrant 向量搜索 + 关键词加权排序
     *
     * @param question   用户问题文本
     * @param limit      返回条数
     * @param collection Qdrant collection 名称
     * @param vectorName 向量名称（null 表示默认向量）
     * @return 搜索结果列表，含 hybrid_score 字段
     */
    private List<Map<String, Object>> searchQdrant(String question, int limit,
                                                   String collection, String vectorName) {
        float[] vector = embed(question);
        String url = "http://" + qdrantHost + ":" + qdrantPort
                + "/collections/" + collection + "/points/search";

        Map<String, Object> body = buildSearchBody(vector, limit, vectorName);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                url, new HttpEntity<>(body, headers), JsonNode.class);

        List<Map<String, Object>> results = parseSearchResults(response.getBody());
        if (results.isEmpty()) return results;

        // 关键词加权
        List<String> keywords = extractKeywords(question);
        if (!keywords.isEmpty()) {
            for (Map<String, Object> cand : results) {
                String text = (String) cand.getOrDefault("text", "");
                double kwScore = computeKeywordScore(text, keywords);
                double vecScore = ((Number) cand.getOrDefault("score", 0.0)).doubleValue();
                cand.put("hybrid_score", VECTOR_WEIGHT * vecScore + (1 - VECTOR_WEIGHT) * kwScore);
            }
            results.sort((a, b) -> Double.compare(
                    ((Number) b.getOrDefault("hybrid_score", 0.0)).doubleValue(),
                    ((Number) a.getOrDefault("hybrid_score", 0.0)).doubleValue()));
        }
        return results;
    }

    /**
     * RRF（Reciprocal Rank Fusion）合并 Qdrant 和 Meilisearch 两路结果，k=60
     *
     * @param qdrantResults Qdrant 向量搜索结果
     * @param meiliResults  Meilisearch 全文搜索结果
     * @param limit         最终返回条数
     * @return 按 RRF 得分降序排列的合并结果
     */
    private List<Map<String, Object>> rrfMerge(List<Map<String, Object>> qdrantResults,
                                               List<Map<String, Object>> meiliResults,
                                               int limit) {
        int k = 60;

        // 第一路：Qdrant → rank → RRF score
        Map<String, Double> rrfMap = new LinkedHashMap<>();
        for (int i = 0; i < qdrantResults.size(); i++) {
            String id = (String) qdrantResults.get(i).get("id");
            if (id != null) {
                rrfMap.merge(id, 1.0 / (k + i), Double::sum);
            }
        }

        // 第二路：Meilisearch → rank → RRF score
        for (int i = 0; i < meiliResults.size(); i++) {
            String id = (String) meiliResults.get(i).get("id");
            if (id != null) {
                rrfMap.merge(id, 1.0 / (k + i), Double::sum);
            }
        }

        // 按 RRF 得分排序
        List<String> sortedIds = rrfMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 按排序后的 ID 组装结果（优先取 Qdrant 的结果，因为它字段更全）
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

    // ========== 文档库搜索 ==========

    /**
     * 纯向量搜索文档库（不经过混合检索）
     *
     * @param question 用户问题
     * @param limit    返回条数
     * @return 搜索结果列表
     */
    public List<Map<String, Object>> searchDocs(String question, int limit) {
        log.info("搜索文档库: collection={}, question={}, limit={}", docCollection, truncate(question, 50), limit);
        try {
            float[] vector = embed(question);
            String url = "http://" + qdrantHost + ":" + qdrantPort + "/collections/" + docCollection + "/points/search";

            Map<String, Object> body = buildSearchBody(vector, limit, docVectorName);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            long start = System.currentTimeMillis();
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(url, entity, JsonNode.class);
            long elapsed = System.currentTimeMillis() - start;

            List<Map<String, Object>> results = parseSearchResults(response.getBody());

            log.info("文档搜索完成: 结果数={}, 耗时={}ms", results.size(), elapsed);
            return results;
        } catch (Exception e) {
            log.error("文档库搜索失败: {} — {}", truncate(question, 50), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 构造 Qdrant search 请求体
     *
     * @param vector     查询向量
     * @param limit      返回条数
     * @param vectorName 向量名称（null 表示默认向量）
     * @return Qdrant API 请求体
     */
    private Map<String, Object> buildSearchBody(float[] vector, int limit, String vectorName) {
        Map<String, Object> body = new HashMap<>();
        body.put("vector", toSearchVectorParam(vector, vectorName));
        body.put("limit", limit);
        body.put("with_payload", true);
        return body;
    }

    /**
     * 根据向量名称构造 Qdrant upsert vector 参数
     *
     * @param vector     向量数据
     * @param vectorName 向量名称，null 时返回普通 list
     * @return upsert 接口兼容的 vector 参数
     */
    private Object toVectorParam(float[] vector, String vectorName) {
        if (vectorName != null) {
            return Collections.singletonMap(vectorName, toList(vector));
        }
        return toList(vector);
    }

    /**
     * 构造搜索用的向量参数（搜索接口的 named vector 格式与 upsert 不同）
     *
     * @param vector     向量数据
     * @param vectorName 向量名称，null 时返回普通 list
     * @return search 接口兼容的 vector 参数
     */
    private Object toSearchVectorParam(float[] vector, String vectorName) {
        if (vectorName == null) return toList(vector);
        Map<String, Object> named = new HashMap<>();
        named.put("name", vectorName);
        named.put("vector", toList(vector));
        return named;
    }

    /**
     * Qdrant 端口（供同步等工具方法使用）
     */
    public int getQdrantPort() {
        return qdrantPort;
    }

    /**
     * 解析 Qdrant search 响应，兼容新旧 payload 字段名
     *
     * @param responseBody Qdrant API 原始响应
     * @return 统一格式的结果列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSearchResults(JsonNode responseBody) {
        List<Map<String, Object>> results = new ArrayList<>();
        JsonNode points = responseBody.path("result");
        for (JsonNode point : points) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", point.path("id").asText());
            double score = point.path("score").asDouble();
            item.put("score", score);

            JsonNode payload = point.path("payload");
            String text = extractPayloadField(payload, "text", "content");
            if (text != null) item.put("text", text);
            String filePath = extractPayloadField(payload, "file_path", "filePath", "relativePath");
            if (filePath != null) item.put("file_path", filePath);
            String fileName = extractPayloadField(payload, "file_name", "fileName");
            if (fileName == null && filePath != null) {
                int idx = filePath.lastIndexOf('/');
                fileName = idx >= 0 ? filePath.substring(idx + 1) : filePath;
            }
            if (fileName != null) item.put("file_name", fileName);

            // 兼容新旧 chunk_index 字段
            Integer chunkIdx = null;
            if (payload.has("chunk_index")) chunkIdx = payload.get("chunk_index").asInt();
            else if (payload.has("chunkIndex")) chunkIdx = payload.get("chunkIndex").asInt();
            if (chunkIdx != null) item.put("chunk_index", chunkIdx);

            // 知识条目特有字段
            if (payload.has("title")) item.put("title", payload.get("title").asText());
            if (payload.has("tags") && payload.get("tags").isArray()) {
                List<String> tags = new ArrayList<>();
                payload.get("tags").forEach(t -> tags.add(t.asText()));
                item.put("tags", tags);
            }
            if (payload.has("relatedFiles") && payload.get("relatedFiles").isArray()) {
                List<String> relatedFiles = new ArrayList<>();
                payload.get("relatedFiles").forEach(f -> relatedFiles.add(f.asText()));
                item.put("relatedFiles", relatedFiles);
            }

            results.add(item);
        }
        return results;
    }

    /**
     * RAG 上下文最大字符数（约 4000-6000 tokens）。限制检索内容长度以防噪声过多冲淡回答质量，
     * 非 LLM context window 限制（DeepSeek 支持 1M tokens）。
     */
    public static final int MAX_CONTEXT_CHARS = 12000;

    /**
     * 截断上下文列表，按 score 从高到低保留，确保总长度不超过 maxChars。
     * 最少保留 1 条，防止空上下文。
     */
    public static List<Map<String, Object>> truncateContexts(List<Map<String, Object>> contexts, int maxChars) {
        if (contexts == null || contexts.isEmpty()) return contexts;
        if (maxChars <= 0) maxChars = MAX_CONTEXT_CHARS;

        // 按 score 降序排列
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

    /**
     * 向文档库写入一个 chunk
     *
     * @param id          chunk 唯一 ID
     * @param text        chunk 文本内容
     * @param fileId      来源文件路径
     * @param chunkIndex  当前 chunk 在文件中的序号
     * @param totalChunks 文件总分片数
     */
    public void upsertDoc(String id, String text, String fileId, int chunkIndex, int totalChunks) {
        float[] vector = embed(text);

        String url = "http://" + qdrantHost + ":" + qdrantPort + "/collections/" + docCollection + "/points";

        Map<String, Object> payload = new HashMap<>();
        payload.put("text", text);
        payload.put("chunk_index", chunkIndex);
        payload.put("total_chunks", totalChunks);
        payload.put("file_path", fileId);
        if (fileId != null) {
            int idx = fileId.lastIndexOf('/');
            payload.put("file_name", idx >= 0 ? fileId.substring(idx + 1) : fileId);
        }

        Map<String, Object> point = new HashMap<>();
        point.put("id", id);
        point.put("vector", toVectorParam(vector, docVectorName));
        point.put("payload", payload);

        Map<String, Object> body = new HashMap<>();
        body.put("points", Collections.singletonList(point));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        restTemplate.put(url, entity);
        log.info("写入文档库成功: id={}, fileId={}, chunk={}/{}", truncate(id, 50), fileId, chunkIndex + 1, totalChunks);
    }

    // ========== 文档库特有方法 ==========

    /**
     * 获取某个文件在文档库中的完整内容（所有 chunks 按序拼接）
     *
     * @param fileId 来源文件路径
     * @return 完整文档文本
     */
    public String fetchCompleteDocContent(String fileId) {
        return scrollAndAssemble(docCollection, fileId);
    }

    /**
     * Scroll 某个 collection 中指定文件的所有 points，按 chunk_index 排序拼接
     *
     * @param collection Qdrant collection 名称
     * @param filePath   来源文件路径
     * @return 完整文本，无数据时返回 null
     */
    private String scrollAndAssemble(String collection, String filePath) {
        String url = "http://" + qdrantHost + ":" + qdrantPort + "/collections/" + collection + "/points/scroll";

        // 兼容新旧字段名
        List<Object> should = new ArrayList<>();
        for (String key : new String[]{"file_path", "filePath"}) {
            Map<String, Object> mc = new HashMap<>();
            mc.put("key", key);
            Map<String, String> mv = new HashMap<>();
            mv.put("value", filePath);
            mc.put("match", mv);
            should.add(mc);
        }
        Map<String, Object> filter = new HashMap<>();
        filter.put("should", should);

        Map<String, Object> body = new HashMap<>();
        body.put("filter", filter);
        body.put("limit", 100);
        body.put("with_payload", true);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(url, entity, JsonNode.class);
        JsonNode points = response.getBody().path("result").path("points");

        if (points.size() == 0) return null;

        List<JsonNode> sorted = new ArrayList<>();
        points.forEach(sorted::add);
        sorted.sort(Comparator.comparingInt(p -> {
            JsonNode pl = p.path("payload");
            return pl.has("chunk_index") ? pl.get("chunk_index").asInt(0)
                    : pl.has("chunkIndex") ? pl.get("chunkIndex").asInt(0)
                      : 0;
        }));

        StringBuilder full = new StringBuilder();
        for (JsonNode p : sorted) {
            String text = extractPayloadField(p.path("payload"), "text", "content");
            if (text != null) full.append(text).append('\n');
        }
        return full.toString().strip();
    }

    /**
     * 混合检索 + 上下文扩展：搜索后按文件分组，取匹配 chunk 前后各 2 个 chunk 拼接
     *
     * @param question 用户问题
     * @param limit    返回条数
     * @return 搜索结果，每条 text 已扩展为带上下文的完整段落
     */
    public List<Map<String, Object>> searchDocsWithFullContent(String question, int limit) {
        // 混合检索 + 取更多候选以支持上下文扩展
        List<Map<String, Object>> results = searchHybrid(question, limit * 2, docCollection, docVectorName);
        if (results.isEmpty()) return results;

        try {
            // 按文件分组，记录每个文件中匹配到的 chunk 范围
            Map<String, int[]> fileRanges = new LinkedHashMap<>();
            for (Map<String, Object> r : results) {
                String fp = (String) r.get("file_path");
                int chunkIdx = r.containsKey("chunk_index") ? ((Number) r.get("chunk_index")).intValue() : 0;
                if (fp != null) {
                    fileRanges.compute(fp, (k, existing) -> {
                        if (existing == null) return new int[]{chunkIdx, chunkIdx};
                        existing[0] = Math.min(existing[0], chunkIdx);
                        existing[1] = Math.max(existing[1], chunkIdx);
                        return existing;
                    });
                }
            }

            // 对每个文件，只取匹配 chunk 前后各 2 个
            int window = 2;
            Map<String, String> contextMap = new HashMap<>();
            for (Map.Entry<String, int[]> entry : fileRanges.entrySet()) {
                String fp = entry.getKey();
                int minIdx = entry.getValue()[0];
                int maxIdx = entry.getValue()[1];
                String context = scrollWithRange(docCollection, fp, Math.max(0, minIdx - window), maxIdx + window);
                if (context != null) contextMap.put(fp, context);
            }

            // 把拼接好的上下文替换到结果中
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

    /**
     * 按 chunkIndex 范围过滤 scroll，只取指定范围内的 chunk
     *
     * @param collection Qdrant collection 名称
     * @param filePath   来源文件路径
     * @param startIdx   chunk 起始下标（含）
     * @param endIdx     chunk 结束下标（含）
     * @return 拼接后的文本
     */
    private String scrollWithRange(String collection, String filePath, int startIdx, int endIdx) {
        String url = "http://" + qdrantHost + ":" + qdrantPort + "/collections/" + collection + "/points/scroll";

        // 文件路径匹配：新旧字段名兼容
        List<Object> fileMatchOr = new ArrayList<>();
        for (String key : new String[]{"file_path", "filePath"}) {
            Map<String, Object> mc = new HashMap<>();
            mc.put("key", key);
            Map<String, String> mv = new HashMap<>();
            mv.put("value", filePath);
            mc.put("match", mv);
            fileMatchOr.add(mc);
        }

        // chunk_index 范围：新旧字段名兼容
        Map<String, Object> range = new HashMap<>();
        range.put("gte", startIdx);
        range.put("lte", endIdx);

        List<Object> chunkIndexOr = new ArrayList<>();
        for (String key : new String[]{"chunk_index", "chunkIndex"}) {
            Map<String, Object> rc = new HashMap<>();
            rc.put("key", key);
            rc.put("range", range);
            chunkIndexOr.add(rc);
        }

        Map<String, Object> filter = new HashMap<>();
        filter.put("must", fileMatchOr);
        filter.put("should", chunkIndexOr);

        Map<String, Object> body = new HashMap<>();
        body.put("filter", filter);
        body.put("limit", 100);
        body.put("with_payload", true);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(url, entity, JsonNode.class);
        JsonNode points = response.getBody().path("result").path("points");

        if (points.size() == 0) return null;

        List<JsonNode> sorted = new ArrayList<>();
        points.forEach(sorted::add);
        sorted.sort(Comparator.comparingInt(p -> {
            JsonNode pl = p.path("payload");
            return pl.has("chunk_index") ? pl.get("chunk_index").asInt(0)
                    : pl.has("chunkIndex") ? pl.get("chunkIndex").asInt(0)
                      : 0;
        }));

        StringBuilder full = new StringBuilder();
        for (JsonNode p : sorted) {
            String text = extractPayloadField(p.path("payload"), "text", "content");
            if (text != null) full.append(text).append('\n');
        }
        return full.toString().strip();
    }

    /**
     * 从 Qdrant payload 中按多个 key 名依次尝试取值（兼容新旧字段）
     *
     * @param payload Qdrant point payload
     * @param keys    待尝试的 key 列表，按优先级排列
     * @return 第一个匹配到的文本值
     */
    private String extractPayloadField(JsonNode payload, String... keys) {
        for (String key : keys) {
            if (payload.has(key)) {
                return payload.get(key).asText();
            }
        }
        return null;
    }

    /**
     * float 数组转 List<Double>（Qdrant API 需要）
     */
    private List<Double> toList(float[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add((double) v);
        }
        return list;
    }

    /**
     * 截断长文本用于日志输出
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
