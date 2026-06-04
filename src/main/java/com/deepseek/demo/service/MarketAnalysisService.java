package com.deepseek.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 大盘分析服务 —— 读取全市场数据，调用 DeepSeek V4 Flash 分析当日盘面，
 * 输出市场情绪、值得关注的板块和个股。
 */
@Service
public class MarketAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(MarketAnalysisService.class);

    private final MarketDataService marketDataService;
    private final ILlmService llmService;

    public MarketAnalysisService(MarketDataService marketDataService,
                                  ILlmService llmService) {
        this.marketDataService = marketDataService;
        this.llmService = llmService;
    }

    /**
     * 分析今日大盘，返回关注建议。
     */
    public Map<String, Object> analyzeMarket() {
        long start = System.currentTimeMillis();
        log.info("大盘分析开始");

        Map<String, Object> daily = marketDataService.getDailyData();
        if (daily == null || daily.isEmpty()) {
            return Map.of("error", "暂无今日市场数据，请先运行 batch_collect.py 采集数据");
        }

        // 构建大盘上下文
        String marketContext = buildMarketContext(daily);
        String systemPrompt = buildSystemPrompt();
        String userPrompt = "请基于以下今日A股市场数据，给出盘面分析和关注建议：\n\n" + marketContext;

        // 调用 DS V4 Flash
        String llmReply;
        try {
            llmReply = llmService.chatWithSystem(systemPrompt, userPrompt);
        } catch (Exception e) {
            log.error("大盘分析调用失败: {}", e.getMessage());
            return Map.of("error", "分析服务暂不可用: " + e.getMessage());
        }

        // 提取关键数据给前端展示
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) daily.get("marketStats");
        @SuppressWarnings("unchecked")
        Map<String, Object> indices = (Map<String, Object>) daily.get("indices");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", daily.getOrDefault("date", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))));
        result.put("analysis", llmReply);
        result.put("indices", summarizeIndices(indices));
        result.put("marketStats", summarizeStats(stats));
        result.put("topStocks", marketDataService.getTopStocks());
        result.put("analysisTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        result.put("llmModel", "deepseek-v4-flash");

        long elapsed = System.currentTimeMillis() - start;
        log.info("大盘分析完成, 耗时={}ms, LLM回复长度={}", elapsed, llmReply.length());

        return result;
    }

    // ════════════════════════════════════════════
    //  Prompt 构建
    // ════════════════════════════════════════════

    private String buildSystemPrompt() {
        return "你是一位资深A股策略分析师。根据今日全市场数据，输出盘面分析和关注建议。\n\n"
                + "请按以下结构输出：\n\n"
                + "===== 今日盘面综述 =====\n"
                + "- 市场整体情绪（强/弱/分化）\n"
                + "- 主要指数表现及原因\n"
                + "- 涨跌结构特征\n\n"
                + "===== 板块关注 =====\n"
                + "- 今日最强板块及驱动逻辑（如有）\n"
                + "- 该板块的核心个股\n"
                + "- 今日最弱板块（回避方向）\n\n"
                + "===== 个股关注 =====\n"
                + "- 涨幅榜中值得关注的个股（基本面/题材/技术面）\n"
                + "- 成交额异常放大的个股\n"
                + "- 连板/跌停个股中的信号\n\n"
                + "===== 后市研判 =====\n"
                + "- 短期市场方向判断\n"
                + "- 需要关注的关键因素（政策/事件/资金面）\n"
                + "- 操作策略建议\n\n"
                + "要求：\n"
                + "1. 所有分析基于今日实际数据，不编造\n"
                + "2. 如果某方面数据不足，明确说明\n"
                + "3. 用中文输出，专业、简洁\n"
                + "4. 板块和个股推荐需有明确的逻辑支撑";
    }

    @SuppressWarnings("unchecked")
    private String buildMarketContext(Map<String, Object> daily) {
        StringBuilder sb = new StringBuilder();

        // 日期
        sb.append("日期: ").append(daily.getOrDefault("date", "未知")).append("\n\n");

        // 指数
        Map<String, Object> indices = (Map<String, Object>) daily.get("indices");
        if (indices != null && !indices.isEmpty()) {
            sb.append("## 主要指数\n");
            for (Map.Entry<String, Object> e : indices.entrySet()) {
                Map<String, Object> idx = (Map<String, Object>) e.getValue();
                if (idx != null) {
                    sb.append(String.format("- %s: %.2f (%+.2f%%)\n",
                            idx.get("name"), idx.get("price"), idx.get("changePercent")));
                }
            }
            sb.append("\n");
        }

        // 全市场统计
        Map<String, Object> stats = (Map<String, Object>) daily.get("marketStats");
        if (stats != null && !stats.isEmpty()) {
            sb.append("## 全市场统计\n");
            sb.append(String.format("- 上涨: %s | 下跌: %s | 涨跌比: %s\n",
                    stats.get("upCount"), stats.get("downCount"), stats.get("upDownRatio")));
            sb.append(String.format("- 涨停: %s | 跌停: %s\n",
                    stats.get("limitUpCount"), stats.get("limitDownCount")));
            sb.append(String.format("- 平均涨跌幅: %s%%\n", stats.get("avgChange")));

            Map<String, Object> dist = (Map<String, Object>) stats.get("distribution");
            if (dist != null && !dist.isEmpty()) {
                sb.append("- 涨跌分布: ").append(dist.entrySet().stream()
                        .map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(" | ")));
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 行业板块
        Map<String, Object> sectors = (Map<String, Object>) daily.get("sectors");
        if (sectors != null) {
            sb.append("## 行业板块排行\n");
            List<Map<String, Object>> top = (List<Map<String, Object>>) sectors.get("topSectors");
            if (top != null && !top.isEmpty()) {
                sb.append("涨幅前5板块:\n");
                for (Map<String, Object> s : top.subList(0, Math.min(5, top.size()))) {
                    sb.append(String.format("  %s: %+.2f%%\n", s.get("name"), s.get("changePercent")));
                }
            }
            List<Map<String, Object>> bottom = (List<Map<String, Object>>) sectors.get("bottomSectors");
            if (bottom != null && !bottom.isEmpty()) {
                sb.append("跌幅前5板块:\n");
                for (Map<String, Object> s : bottom.subList(0, Math.min(5, bottom.size()))) {
                    sb.append(String.format("  %s: %+.2f%%\n", s.get("name"), s.get("changePercent")));
                }
            }
            sb.append("\n");
        }

        // TOP 个股榜单
        Map<String, Object> topStocks = (Map<String, Object>) daily.get("topStocks");
        if (topStocks != null && !topStocks.isEmpty()) {
            sb.append("## TOP 个股榜单\n");

            List<Map<String, Object>> gainers = (List<Map<String, Object>>) topStocks.get("topGainers");
            if (gainers != null && !gainers.isEmpty()) {
                sb.append("涨幅前10:\n");
                for (Map<String, Object> s : gainers.subList(0, Math.min(10, gainers.size()))) {
                    sb.append(String.format("  %s(%s): +%.2f%%\n", s.get("name"), s.get("code"), s.get("pctChange")));
                }
            }

            List<Map<String, Object>> losers = (List<Map<String, Object>>) topStocks.get("topLosers");
            if (losers != null && !losers.isEmpty()) {
                sb.append("跌幅前10:\n");
                for (Map<String, Object> s : losers.subList(0, Math.min(10, losers.size()))) {
                    sb.append(String.format("  %s(%s): %.2f%%\n", s.get("name"), s.get("code"), s.get("pctChange")));
                }
            }

            List<Map<String, Object>> volume = (List<Map<String, Object>>) topStocks.get("topVolume");
            if (volume != null && !volume.isEmpty()) {
                sb.append("成交额前10:\n");
                for (Map<String, Object> s : volume.subList(0, Math.min(10, volume.size()))) {
                    sb.append(String.format("  %s(%s): %.2f 涨%.2f%%\n",
                            s.get("name"), s.get("code"), s.get("amount"), s.get("pctChange")));
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // ════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> summarizeIndices(Map<String, Object> indices) {
        if (indices == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Object> e : indices.entrySet()) {
            Map<String, Object> idx = (Map<String, Object>) e.getValue();
            if (idx != null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", idx.get("name"));
                item.put("price", idx.get("price"));
                item.put("changePercent", idx.get("changePercent"));
                result.add(item);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summarizeStats(Map<String, Object> stats) {
        if (stats == null) return Map.of();
        Map<String, Object> s = new LinkedHashMap<>();
        for (String key : List.of("upCount", "downCount", "upDownRatio", "limitUpCount", "limitDownCount", "avgChange")) {
            s.put(key, stats.get(key));
        }
        s.put("distribution", stats.get("distribution"));
        return s;
    }
}
