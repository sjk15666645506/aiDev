package com.deepseek.demo.service;

import com.deepseek.demo.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
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
    private final String qdrantHost;
    private final int qdrantPort;
    private final String docCollection;

    private String docVectorName;

    public QdrantClient(RestTemplate restTemplate,
                        @Value("${qdrant.host}") String qdrantHost,
                        @Value("${qdrant.port}") int qdrantPort,
                        @Value("${qdrant.doc-collection}") String docCollection) {
        this.restTemplate = restTemplate;
        this.qdrantHost = qdrantHost;
        this.qdrantPort = qdrantPort;
        this.docCollection = docCollection;
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

    // ── Vector params ──

    public Map<String, Object> buildSearchBody(float[] vector, int limit, String vectorName) {
        Map<String, Object> body = new HashMap<>();
        body.put("vector", toSearchVectorParam(vector, vectorName));
        body.put("limit", limit);
        body.put("with_payload", true);
        return body;
    }

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

    // ── Search ──

    public List<Map<String, Object>> search(String collection, float[] vector, int limit, String vectorName) {
        String url = qdrantUrl("/collections/" + collection + "/points/search");

        Map<String, Object> body = buildSearchBody(vector, limit, vectorName);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                url, new HttpEntity<>(body, headers), JsonNode.class);

        return parseSearchResults(response.getBody());
    }

    // ── Upsert ──

    public void upsert(String id, float[] vector, String text, String fileId,
                       int chunkIndex, int totalChunks) {
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

    // ── Scroll ──

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

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> parseSearchResults(JsonNode responseBody) {
        List<Map<String, Object>> results = new ArrayList<>();
        JsonNode points = responseBody.path("result");
        for (JsonNode point : points) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", point.path("id").asText());
            item.put("score", point.path("score").asDouble());

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

            Integer chunkIdx = null;
            if (payload.has("chunk_index")) chunkIdx = payload.get("chunk_index").asInt();
            else if (payload.has("chunkIndex")) chunkIdx = payload.get("chunkIndex").asInt();
            if (chunkIdx != null) item.put("chunk_index", chunkIdx);

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

    public String extractPayloadField(JsonNode payload, String... keys) {
        for (String key : keys) {
            if (payload.has(key)) {
                return payload.get(key).asText();
            }
        }
        return null;
    }

    // ── Getters ──

    public String getDocCollection() { return docCollection; }
    public String getDocVectorName() { return docVectorName; }
    public int getQdrantPort() { return qdrantPort; }
}
