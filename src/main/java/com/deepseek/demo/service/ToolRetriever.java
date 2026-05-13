package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ToolDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Layer 2: 工具召回器。
 * <p>
 * 在 DomainRouter 确定的领域内，结合语义检索和频率衰减，
 * 从该领域工具中召回最相关的 top K 个工具。
 */
@Component
public class ToolRetriever {

    private static final Logger log = LoggerFactory.getLogger(ToolRetriever.class);

    private final ToolRegistry toolRegistry;
    private final ToolVectorStore vectorStore;
    private final VectorService vectorService;
    private final FrequencyTracker frequencyTracker;

    public ToolRetriever(ToolRegistry toolRegistry,
                         ToolVectorStore vectorStore,
                         VectorService vectorService,
                         FrequencyTracker frequencyTracker) {
        this.toolRegistry = toolRegistry;
        this.vectorStore = vectorStore;
        this.vectorService = vectorService;
        this.frequencyTracker = frequencyTracker;
    }

    /**
     * 系统启动后，为所有已注册工具构建 Embedding 索引。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("开始构建工具 Embedding 索引...");
        for (ToolMeta meta : toolRegistry.getAllTools().values()) {
            String text = buildEmbeddingText(meta);
            try {
                float[] vector = vectorService.embed(text);
                vectorStore.save(meta.getName(), vector);
            } catch (Exception e) {
                log.warn("工具 Embedding 失败: name={}, error={}", meta.getName(), e.getMessage());
            }
        }
        log.info("工具 Embedding 索引构建完成，共 {} 个工具", vectorStore.size());
    }

    /**
     * 从用户 query 中召回最相关的 top K 个工具。
     * 限制在指定领域内。
     */
    public List<ToolMeta> retrieve(String userQuery, ToolDomain domain, int topK) {
        // 1. 获取该领域的所有工具作为候选池
        List<ToolMeta> candidates = toolRegistry.getByDomain(domain);
        if (candidates.isEmpty()) {
            log.warn("领域 {} 下无已注册工具", domain);
            return Collections.emptyList();
        }

        // 2. 尝试语义检索
        List<ToolVectorStore.ScoredTool> scored;
        try {
            float[] queryVector = vectorService.embed(userQuery);
            scored = vectorStore.search(queryVector, topK);
        } catch (Exception e) {
            log.warn("语义检索失败，降级为频率排序: {}", e.getMessage());
            scored = Collections.emptyList();
        }

        // 3. 按领域过滤语义结果 + 补充频率最高的工具
        Set<String> selected = new LinkedHashSet<>();

        // 先加语义检索到的（必须是目标领域）
        for (ToolVectorStore.ScoredTool st : scored) {
            ToolMeta meta = safeGetTool(st.getName());
            if (meta != null && meta.getDomain() == domain) {
                selected.add(st.getName());
            }
        }

        // 如果不足 topK，补充该领域最近使用频率最高的
        if (selected.size() < topK) {
            List<String> popular = frequencyTracker.getMostUsed(topK - selected.size());
            for (String name : popular) {
                if (selected.size() >= topK) break;
                ToolMeta meta = safeGetTool(name);
                if (meta != null && meta.getDomain() == domain) {
                    selected.add(name);
                }
            }
        }

        // 如果还不够，从该领域按注册顺序补全
        if (selected.size() < topK) {
            for (ToolMeta meta : candidates) {
                if (selected.size() >= topK) break;
                selected.add(meta.getName());
            }
        }

        List<ToolMeta> result = selected.stream()
                .map(this::safeGetTool)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        log.debug("工具召回: domain={}, query={}, candidates={}, selected={}",
                domain, truncate(userQuery, 30), candidates.size(), result.size());
        return result;
    }

    /**
     * 构建用于 Embedding 的文本：名字 + description + capabilities。
     */
    private String buildEmbeddingText(ToolMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append(meta.getName()).append(": ").append(meta.getDescription());
        if (meta.getCapabilities() != null && !meta.getCapabilities().isEmpty()) {
            sb.append(". Capabilities: ").append(String.join(", ", meta.getCapabilities()));
        }
        return sb.toString();
    }

    /** 安全获取工具，不存在时返回 null 而非抛异常 */
    private ToolMeta safeGetTool(String name) {
        try {
            return toolRegistry.getTool(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
