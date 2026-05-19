package com.deepseek.demo.service;

import com.deepseek.demo.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final RestTemplate restTemplate;
    private final String ollamaHost;
    private final int ollamaPort;
    private final String ollamaModel;

    private final Map<String, EmbeddingCacheEntry> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_SEC = 300;

    private static class EmbeddingCacheEntry {
        final float[] vector;
        final long expiresAt;

        EmbeddingCacheEntry(float[] vector, long expiresAt) {
            this.vector = vector;
            this.expiresAt = expiresAt;
        }
    }

    public EmbeddingClient(RestTemplate restTemplate,
                           @Value("${ollama.host}") String ollamaHost,
                           @Value("${ollama.port}") int ollamaPort,
                           @Value("${ollama.model}") String ollamaModel) {
        this.restTemplate = restTemplate;
        this.ollamaHost = ollamaHost;
        this.ollamaPort = ollamaPort;
        this.ollamaModel = ollamaModel;
    }

    public float[] embed(String text) {
        EmbeddingCacheEntry cached = cache.get(text);
        if (cached != null && cached.expiresAt > Instant.now().getEpochSecond()) {
            log.debug("Embedding缓存命中: text={}", StringUtils.truncate(text, 50));
            return cached.vector;
        }

        String url = "http://" + ollamaHost + ":" + ollamaPort + "/api/embed";
        log.info("生成embedding: text={}", StringUtils.truncate(text, 50));

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
            cache.put(text, new EmbeddingCacheEntry(result,
                    Instant.now().getEpochSecond() + CACHE_TTL_SEC));
            if (cache.size() > 1000) {
                cache.values().removeIf(e -> e.expiresAt <= Instant.now().getEpochSecond());
            }
            return result;
        }
        throw new RuntimeException("Embedding失败: " + response.getBody());
    }
}
