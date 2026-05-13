package com.deepseek.demo.service;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具调用频率追踪器。
 * 用于 ToolRetriever 在语义检索不足时补充高频工具。
 */
@Component
public class FrequencyTracker {

    private static class ToolFreq {
        int count = 0;
        long lastUsed = System.currentTimeMillis();
    }

    private final Map<String, ToolFreq> freqs = new ConcurrentHashMap<>();

    /** 记录一次工具调用 */
    public void recordCall(String toolName) {
        freqs.compute(toolName, (k, v) -> {
            ToolFreq f = (v == null) ? new ToolFreq() : v;
            f.count++;
            f.lastUsed = System.currentTimeMillis();
            return f;
        });
    }

    /**
     * 获取调用频率最高的 N 个工具名称。
     * 按 "调用次数 × 时间衰减" 排序。
     */
    public List<String> getMostUsed(int limit) {
        long now = System.currentTimeMillis();
        return freqs.entrySet().stream()
                .map(e -> {
                    ToolFreq f = e.getValue();
                    double daysSinceUse = (now - f.lastUsed) / 86400000.0;
                    double score = f.count * Math.exp(-daysSinceUse * 0.1);
                    return new AbstractMap.SimpleEntry<>(e.getKey(), score);
                })
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /** 获取总调用次数（用于测试和监控） */
    public int getTotalCalls() {
        return freqs.values().stream().mapToInt(f -> f.count).sum();
    }

    /** 重置所有记录 */
    public void reset() {
        freqs.clear();
    }
}
