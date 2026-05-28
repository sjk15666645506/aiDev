package com.deepseek.demo.service;

import com.deepseek.demo.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.*;

@Component
public class QdrantClient {

    private static final Logger log = LoggerFactory.getLogger(QdrantClient.class);

    private final RestTemplate restTemplate;
    private final QdrantEmbeddingStore embeddingStore;
    private final String qdrantHost;
    private final int qdrantPort;
    private final int qdrantGrpcPort;
    private final String docCollection;

    private String docVectorName;

    public QdrantClient(RestTemplate restTemplate,
                        @Value("${qdrant.host}") String qdrantHost,
                        @Value("${qdrant.port}") int qdrantPort,
                        @Value("${qdrant.grpc-port}") int qdrantGrpcPort,
                        @Value("${qdrant.doc-collection}") String docCollection) {
        this.restTemplate = restTemplate;
        this.qdrantHost = qdrantHost;
        this.qdrantPort = qdrantPort;
        this.qdrantGrpcPort = qdrantGrpcPort;
        this.docCollection = docCollection;
        this.embeddingStore = QdrantEmbeddingStore.builder()
                .host(qdrantHost)
                .port(qdrantGrpcPort)
                .collectionName(docCollection)
                .build();
        log.info("QdrantEmbeddingStore 已创建: host={}, grpcPort={}, collection={}",
                qdrantHost, qdrantGrpcPort, docCollection);
    }

    @PostConstruct
    public void init() {
        try {
            docVectorName = detectVectorName(docCollection);
            if (docVectorName == null) {
                createCollection(docCollection);
                docVectorName = null;
            }
            log.info("QdrantClient 初始化完成: doc={}(vector={})", docCollection, docVectorName);
            new Thread(this::repairCollectionVectors,
                    "qdrant-vector-repair").start();
        } catch (Exception e) {
            log.warn("QdrantClient 初始化失败，将在首次搜索时重试: {}", e.getMessage());
        }
    }

    // ── URL helpers ──

    public String qdrantUrl(String path) {
        return "http://" + qdrantHost + ":" + qdrantPort + path;
    }

    // ── Collection management ──

    public void createCollection(String collection) {
        String url = qdrantUrl("/collections/" + collection);
        Map<String, Object> vectorsConfig = new HashMap<>();
        vectorsConfig.put("size", 768);
        vectorsConfig.put("distance", "Cosine");
        Map<String, Object> body = new HashMap<>();
        body.put("vectors", vectorsConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            restTemplate.exchange(url, HttpMethod.PUT, entity, JsonNode.class);
            log.info("Collection 创建成功: {}", collection);
        } catch (HttpClientErrorException e) {
            if (e.getRawStatusCode() == 409) {
                log.info("Collection 已存在，跳过创建: {}", collection);
            } else {
                throw e;
            }
        }
    }

    public String detectVectorName(String collection) {
        String url = qdrantUrl("/collections/" + collection);
        try {
            ResponseEntity<JsonNode> resp = restTemplate.getForEntity(url, JsonNode.class);
            if (!resp.getStatusCode().is2xxSuccessful()) return null;
            JsonNode vectors = resp.getBody().path("result").path("config").path("params").path("vectors");
            if (vectors.isObject() && vectors.has("size")) {
                return null;
            }
            Iterator<String> it = vectors.fieldNames();
            if (it.hasNext()) return it.next();
        } catch (Exception ignored) {
        }
        return null;
    }

    // ── Search (REST API) ──

    public List<Map<String, Object>> search(String collection, float[] vector, int limit, String vectorName) {
        return searchByRestApi(collection, vector, limit, vectorName);
    }

    // ── Search (REST API, 更鲁棒) ──

    private List<Map<String, Object>> searchByRestApi(String collection, float[] vector,
                                                       int limit, String vectorName) {
        String url = qdrantUrl("/collections/" + collection + "/points/search");

        Map<String, Object> body = new HashMap<>();
        body.put("vector", toSearchVectorParam(vector, vectorName));
        body.put("limit", limit);
        body.put("with_payload", true);
        body.put("with_vector", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(url, entity, JsonNode.class);
            JsonNode result = response.getBody().path("result");

            List<Map<String, Object>> results = new ArrayList<>();
            if (result.isArray()) {
                for (JsonNode point : result) {
                    Map<String, Object> item = new HashMap<>();
                    JsonNode id = point.get("id");
                    item.put("id", id != null ? id.asText() : UUID.randomUUID().toString());
                    item.put("score", point.path("score").asDouble(0.0));

                    JsonNode payload = point.path("payload");
                    item.put("text", extractPayloadField(payload, "text", "content"));
                    String filePath = extractPayloadField(payload, "file_path", "filePath");
                    if (filePath != null) item.put("file_path", filePath);
                    String fileName = extractPayloadField(payload, "file_name", "fileName");
                    if (fileName != null) item.put("file_name", fileName);
                    if (payload.has("chunk_index")) item.put("chunk_index", payload.get("chunk_index").asInt(0));
                    if (payload.has("total_chunks")) item.put("total_chunks", payload.get("total_chunks").asInt(0));

                    results.add(item);
                }
            }
            return results;
        } catch (HttpClientErrorException e) {
            log.error("REST API 搜索失败({}): {}", e.getRawStatusCode(), e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Vector repair ──

    public void repairCollectionVectors() {
        String scrollUrl = qdrantUrl("/collections/" + docCollection + "/points/scroll");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        int expectedDim = 768;
        int totalRemoved = 0;

        try {
            while (true) {
                Map<String, Object> body = new HashMap<>();
                body.put("limit", 100);
                body.put("with_payload", false);
                body.put("with_vector", true);

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
                ResponseEntity<JsonNode> response = restTemplate.postForEntity(scrollUrl, entity, JsonNode.class);
                JsonNode points = response.getBody().path("result").path("points");
                if (!points.isArray() || points.size() == 0) break;

                List<String> badIds = new ArrayList<>();
                for (JsonNode p : points) {
                    int dim = countVectorDimensions(p.path("vector"));
                    if (dim != expectedDim && dim > 0) {
                        badIds.add(p.path("id").asText());
                    } else if (dim == 0) {
                        badIds.add(p.path("id").asText());
                    }
                }

                if (!badIds.isEmpty()) {
                    log.warn("发现 {} 个异常向量点(维度≠{})，准备清理", badIds.size(), expectedDim);
                    deletePoints(docCollection, badIds);
                    totalRemoved += badIds.size();
                }

                JsonNode nextOffset = response.getBody().path("result").path("next_page_offset");
                if (nextOffset.isNull()) break;
                body.put("offset", nextOffset);
            }
        } catch (Exception e) {
            log.warn("向量修复过程异常: {}", e.getMessage());
        }

        if (totalRemoved > 0) {
            log.warn("向量修复完成，共移除 {} 个异常点(维度≠{})", totalRemoved, expectedDim);
        }
    }

    private int countVectorDimensions(JsonNode vectorNode) {
        if (vectorNode == null || vectorNode.isNull()) return 0;
        if (vectorNode.isArray()) return vectorNode.size();
        if (vectorNode.isObject()) {
            int max = 0;
            for (JsonNode v : vectorNode) {
                if (v.isArray() && v.size() > max) max = v.size();
            }
            return max;
        }
        return 0;
    }

    private void deletePoints(String collection, List<String> pointIds) {
        String url = qdrantUrl("/collections/" + collection + "/points/delete");
        Map<String, Object> body = new HashMap<>();
        body.put("points", pointIds);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(url, entity, JsonNode.class);
        log.info("已删除 {} 个异常点", pointIds.size());
    }

    // ── Upsert (HTTP — QdrantEmbeddingStore 不支持自定义 ID + payload) ──

    public void upsert(String id, float[] vector, String text, String fileId,
                       int chunkIndex, int totalChunks) {
        if (vector == null || vector.length == 0) {
            log.warn("跳过空向量 upsert: id={}", StringUtils.truncate(id, 50));
            return;
        }
        String url = qdrantUrl("/collections/" + docCollection + "/points");

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
        log.info("写入文档库成功: id={}, fileId={}, chunk={}/{}",
                StringUtils.truncate(id, 50), fileId, chunkIndex + 1, totalChunks);
    }

    // ── Scroll (HTTP — QdrantEmbeddingStore 无对应操作) ──

    public String scrollAndAssemble(String collection, String filePath) {
        String url = qdrantUrl("/collections/" + collection + "/points/scroll");

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
                    : pl.has("chunkIndex") ? pl.get("chunkIndex").asInt(0) : 0;
        }));

        StringBuilder full = new StringBuilder();
        for (JsonNode p : sorted) {
            String text = extractPayloadField(p.path("payload"), "text", "content");
            if (text != null) full.append(text).append('\n');
        }
        return full.toString().strip();
    }

    public String scrollWithRange(String collection, String filePath, int startIdx, int endIdx) {
        String url = qdrantUrl("/collections/" + collection + "/points/scroll");

        List<Object> fileMatchOr = new ArrayList<>();
        for (String key : new String[]{"file_path", "filePath"}) {
            Map<String, Object> mc = new HashMap<>();
            mc.put("key", key);
            Map<String, String> mv = new HashMap<>();
            mv.put("value", filePath);
            mc.put("match", mv);
            fileMatchOr.add(mc);
        }

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
                    : pl.has("chunkIndex") ? pl.get("chunkIndex").asInt(0) : 0;
        }));

        StringBuilder full = new StringBuilder();
        for (JsonNode p : sorted) {
            String text = extractPayloadField(p.path("payload"), "text", "content");
            if (text != null) full.append(text).append('\n');
        }
        return full.toString().strip();
    }

    // ── Payload parsing ──

    public String extractPayloadField(JsonNode payload, String... keys) {
        for (String key : keys) {
            if (payload.has(key)) {
                return payload.get(key).asText();
            }
        }
        return null;
    }

    // ── Vector params ──

    public Object toVectorParam(float[] vector, String vectorName) {
        if (vectorName != null) {
            return Collections.singletonMap(vectorName, toList(vector));
        }
        return toList(vector);
    }

    public Object toSearchVectorParam(float[] vector, String vectorName) {
        if (vectorName == null) return toList(vector);
        Map<String, Object> named = new HashMap<>();
        named.put("name", vectorName);
        named.put("vector", toList(vector));
        return named;
    }

    public List<Double> toList(float[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add((double) v);
        }
        return list;
    }

    // ── Getters ──

    public String getDocCollection() { return docCollection; }
    public String getDocVectorName() { return docVectorName; }
    public int getQdrantPort() { return qdrantPort; }
}
