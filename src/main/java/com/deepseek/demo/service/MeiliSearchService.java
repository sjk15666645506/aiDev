package com.deepseek.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class MeiliSearchService {

    private static final Logger log = LoggerFactory.getLogger(MeiliSearchService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String meiliBaseUrl;

    /** 构造 MeiliSearchService
     * @param restTemplate  HTTP 客户端
     * @param objectMapper  JSON 序列化/反序列化
     * @param host          Meilisearch 主机地址（${meilisearch.host}）
     * @param port          Meilisearch 端口号（${meilisearch.port}） */
    public MeiliSearchService(RestTemplate restTemplate,
                              ObjectMapper objectMapper,
                              @Value("${meilisearch.host}") String host,
                              @Value("${meilisearch.port}") int port) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.meiliBaseUrl = "http://" + host + ":" + port;
    }

    /**
     * BM25 全文搜索 Meilisearch 索引。
     *
     * @param index  索引名称（对应 collection 名）
     * @param query  搜索关键词
     * @param limit  返回条数
     * @return 与 VectorService.parseSearchResults 相同结构的结果列表
     */
    public List<Map<String, Object>> search(String index, String query, int limit) {
        try {
            String url = meiliBaseUrl + "/indexes/" + index + "/search";

            Map<String, Object> body = new HashMap<>();
            body.put("q", query);
            body.put("limit", limit);
            body.put("attributesToRetrieve", new String[]{
                    "id", "text", "file_path", "file_name", "chunk_index",
                    "file_type", "file_hash"
            });

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            long start = System.currentTimeMillis();
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(url, entity, JsonNode.class);
            long elapsed = System.currentTimeMillis() - start;

            List<Map<String, Object>> results = parseHits(response.getBody());
            log.info("Meilisearch[{}] 搜索完成: 关键词={}, 结果数={}, 耗时={}ms",
                    index, truncate(query, 30), results.size(), elapsed);
            return results;
        } catch (Exception e) {
            log.warn("Meilisearch 搜索失败(非致命): {} — {}", truncate(query, 30), e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 解析 Meilisearch 响应，将 hits 转为统一格式（与 VectorService 兼容） */
    private List<Map<String, Object>> parseHits(JsonNode responseBody) {
        List<Map<String, Object>> results = new ArrayList<>();
        JsonNode hits = responseBody.path("hits");
        int rank = 1;
        for (JsonNode hit : hits) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", hit.path("id").asText());
            // Meilisearch 不返回 score，用排名位置的倒数作为近似分
            item.put("score", 1.0 / (rank + 1));
            String text = hit.path("text").asText("");
            if (!text.isEmpty()) item.put("text", text);
            String filePath = hit.path("file_path").asText("");
            if (!filePath.isEmpty()) item.put("file_path", filePath);
            String fileName = hit.path("file_name").asText("");
            if (!fileName.isEmpty()) item.put("file_name", fileName);
            if (hit.has("chunk_index")) item.put("chunk_index", hit.get("chunk_index").asInt());
            if (hit.has("file_type")) item.put("file_type", hit.get("file_type").asText());
            results.add(item);
            rank++;
        }
        return results;
    }

    /** 截断长文本用于日志输出 */
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
