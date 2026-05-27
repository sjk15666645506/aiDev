package com.deepseek.demo.service;

import com.deepseek.demo.util.StringUtils;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final OllamaEmbeddingModel embeddingModel;

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

    public EmbeddingClient(@Value("${ollama.host}") String ollamaHost,
                           @Value("${ollama.port}") int ollamaPort,
                           @Value("${ollama.model}") String ollamaModel) {
        this.embeddingModel = OllamaEmbeddingModel.builder()
                .baseUrl("http://" + ollamaHost + ":" + ollamaPort)
                .modelName(ollamaModel)
                .timeout(Duration.ofSeconds(30))
                .maxRetries(2)
                .build();
        log.info("EmbeddingClient initialized: baseUrl=http://{}:{}, model={}",
                ollamaHost, ollamaPort, ollamaModel);
    }

    public float[] embed(String text) {
        EmbeddingCacheEntry cached = cache.get(text);
        if (cached != null && cached.expiresAt > Instant.now().getEpochSecond()) {
            log.debug("Embedding缓存命中: text={}", StringUtils.truncate(text, 50));
            return cached.vector;
        }

        log.info("生成embedding: text={}", StringUtils.truncate(text, 50));
        long start = System.currentTimeMillis();

        dev.langchain4j.model.output.Response<List<Embedding>> response =
                embeddingModel.embedAll(List.of(TextSegment.from(text)));

        long elapsed = System.currentTimeMillis() - start;
        List<Embedding> embeddings = response.content();
        if (embeddings != null && !embeddings.isEmpty()) {
            float[] result = embeddings.get(0).vector();
            log.debug("Embedding完成: 维度={}, 耗时={}ms", result.length, elapsed);
            cache.put(text, new EmbeddingCacheEntry(result,
                    Instant.now().getEpochSecond() + CACHE_TTL_SEC));
            if (cache.size() > 1000) {
                cache.values().removeIf(e -> e.expiresAt <= Instant.now().getEpochSecond());
            }
            return result;
        }
        throw new RuntimeException("Embedding失败: 返回空结果");
    }
}
