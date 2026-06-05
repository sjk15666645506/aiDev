package com.deepseek.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ETF 交易分析服务 —— 获取 ETF 市场数据（含净值/溢价率）→ 构建专家提示词 → 调用 DeepSeek V4 Flash → 返回交易建议。
 * <p>
 * 与 TradingAnalysisService 的区别：
 * 1. 提示词包含 ETF 特有的净值/溢价率/跟踪误差分析
 * 2. 分析维度侧重折溢价套利、流动性、跟踪偏差
 * 3. 无基本面分析（ETF 无 EPS/BPS），替代为跟踪指数和基金规模
 */
@Service
public class EtfAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(EtfAnalysisService.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final EtfDataService etfDataService;
    private final ILlmService llmService;
    private final MarketDataService marketDataService;

    public EtfAnalysisService(EtfDataService etfDataService,
                               ILlmService llmService,
                               MarketDataService marketDataService) {
        this.etfDataService = etfDataService;
        this.llmService = llmService;
        this.marketDataService = marketDataService;
    }

    /**
     * ETF 交易分析入口。
     *
     * @param symbol      ETF 代码（如 510050、159919）
     * @param userRequest 用户补充需求（可选）
     * @return 包含报价、净值、溢价率、技术指标、LLM 分析的结构
     */
    public Map<String, Object> analyze(String symbol, String userRequest) {
        long start = System.currentTimeMillis();
        log.info("ETF交易分析开始: symbol={}, request={}", symbol, userRequest);

        // 1. 获取 ETF 全量数据
        Map<String, Object> analysis = etfDataService.fullAnalysis(symbol);
        if (analysis.containsKey("error")) {
            log.warn("ETF分析失败(数据获取错误): symbol={}", symbol);
            return analysis;
        }

        // 2. 构建 LLM 提示词
        String systemPrompt = buildEtfSystemPrompt();
        String marketContext = buildEtfContext(symbol, analysis);
        String userPrompt = (userRequest != null && !userRequest.isBlank())
                ? marketContext + "\n\n用户补充问题：\n" + userRequest
                : marketContext;

        // 3. 调用 DeepSeek V4 Flash
        String llmReply;
        try {
            llmReply = llmService.chatWithSystem(systemPrompt, userPrompt);
        } catch (Exception e) {
            log.error("DeepSeek V4 Flash 调用失败: {}", e.getMessage());
            analysis.put("llmError", "分析服务暂不可用: " + e.getMessage());
            analysis.put("analysisTime", LocalDateTime.now().format(DTF));
            return analysis;
        }

        // 4. 组织结果
        analysis.put("llmAnalysis", llmReply);
        analysis.put("analysisTime", LocalDateTime.now().format(DTF));
        analysis.put("llmModel", "deepseek-v4-flash");

        long elapsed = System.currentTimeMillis() - start;
        log.info("ETF交易分析完成: symbol={}, 耗时={}ms, LLM回复长度={}",
                symbol, elapsed, llmReply.length());

        return analysis;
    }

    /**
     * 批量分析多个 ETF。
     */
    public List<Map<String, Object>> batchAnalyze(List<String> symbols) {
        return symbols.stream()
                .map(sym -> {
                    try {
                        return analyze(sym, "请简要分析");
                    } catch (Exception e) {
                        log.warn("ETF批量分析失败: symbol={}", sym);
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("symbol", sym);
                        err.put("error", e.getMessage());
                        return err;
                    }
                })
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════
    //  提示词构建
    // ═══════════════════════════════════════════════════════

    @SuppressWarnings("StringBufferReplaceableByString")
    private String buildEtfSystemPrompt() {
        StringBuilder p = new StringBuilder();
        p.append("你是一位资深 ETF 交易分析师，精通 ETF 折溢价套利、技术分析和跟踪指数研究。\n");
        p.append("根据提供的 ETF 市场数据（含净值、溢价率），给出专业、客观的交易建议。\n\n");
        p.append("请按以下结构输出分析：\n\n");
        p.append("===== 大盘背景 =====\n");
        p.append("- 今日市场整体情绪\n");
        p.append("- 跟踪指数的走势及趋势\n\n");
        p.append("===== 折溢价分析 =====\n");
        p.append("- 当前溢价率水平及历史对比\n");
        p.append("- 溢价/折价的原因分析\n");
        p.append("- 是否存在折溢价套利机会\n\n");
        p.append("===== 趋势研判 =====\n");
        p.append("- ETF 价格趋势方向及强度\n");
        p.append("- 关键均线排列\n");
        p.append("- MACD 信号解读\n");
        p.append("- ETF vs 跟踪指数的偏离度\n\n");
        p.append("===== 关键技术位 =====\n");
        p.append("- 支撑位 / 阻力位\n");
        p.append("- RSI 超买/超卖判断\n");
        p.append("- 波动率评估\n");
        p.append("- 成交量/换手率分析\n\n");
        p.append("===== 交易建议 =====\n");
        p.append("- 操作方向（买入/持有/观望/卖出）\n");
        p.append("- 建议入场区间\n");
        p.append("- 止损位\n");
        p.append("- 止盈位\n");
        p.append("- 盈亏比\n");
        p.append("- 仓位建议\n\n");
        p.append("===== 基金信息 =====\n");
        p.append("- 跟踪指数及其表现\n");
        p.append("- 基金规模和流动性评估\n");
        p.append("- 跟踪误差分析\n\n");
        p.append("===== 风险提示 =====\n");
        p.append("- 当前主要风险因素\n");
        p.append("- 流动性风险\n");
        p.append("- 需要关注的事件\n\n");
        p.append("重要原则：\n");
        p.append("1. 数据来源为公开市场数据，仅供参考\n");
        p.append("2. 所有建议不构成投资建议，仅为技术分析参考\n");
        p.append("3. ETF 的溢价率是重要的交易参考指标，请重点关注\n");
        p.append("4. 用中文回答，保持专业、冷静、客观\n");
        p.append("5. 明确标出不确定性高的判断\n");
        return p.toString();
    }

    @SuppressWarnings("unchecked")
    private String buildEtfContext(String symbol, Map<String, Object> analysis) {
        Map<String, Object> quote = (Map<String, Object>) analysis.get("quote");
        Map<String, Object> ind = (Map<String, Object>) analysis.get("indicators");
        Map<String, Object> etfInfo = (Map<String, Object>) analysis.get("etfInfo");

        StringBuilder sb = new StringBuilder();

        // ══════════════════════════════════════════
        //  实时大盘指数（T+0，与 ETF 数据同一时刻）
        // ══════════════════════════════════════════
        Map<String, Object> realtimeIndices = (Map<String, Object>) analysis.get("realtimeIndices");
        if (realtimeIndices != null && !realtimeIndices.isEmpty()) {
            sb.append("## 实时大盘指数（今日实时 T+0）\n");
            for (Map.Entry<String, Object> e : realtimeIndices.entrySet()) {
                Map<String, Object> idx = (Map<String, Object>) e.getValue();
                if (idx != null) {
                    sb.append(String.format("- %s: %.2f (%+.2f%%)\n",
                            idx.get("name"), toDouble(idx.get("price")), toDouble(idx.get("changePercent"))));
                }
            }
            sb.append("\n");
        }

        // ══════════════════════════════════════════
        //  实时行业板块（T+0）
        // ══════════════════════════════════════════
        Map<String, Object> realtimeSectors = (Map<String, Object>) analysis.get("realtimeSectors");
        if (realtimeSectors != null && !realtimeSectors.isEmpty()) {
            sb.append("## 实时行业板块（今日实时 T+0）\n");
            List<Map<String, Object>> topSectors = (List<Map<String, Object>>) realtimeSectors.get("topSectors");
            if (topSectors != null && !topSectors.isEmpty()) {
                sb.append("涨幅居前: ");
                for (int i = 0; i < Math.min(5, topSectors.size()); i++) {
                    Map<String, Object> sec = topSectors.get(i);
                    sb.append(String.format("%s(%+.2f%%) ", sec.get("name"), toDouble(sec.get("changePercent"))));
                }
                sb.append("\n");
            }
            List<Map<String, Object>> bottomSectors = (List<Map<String, Object>>) realtimeSectors.get("bottomSectors");
            if (bottomSectors != null && !bottomSectors.isEmpty()) {
                sb.append("跌幅居前: ");
                for (int i = 0; i < Math.min(5, bottomSectors.size()); i++) {
                    Map<String, Object> sec = bottomSectors.get(i);
                    sb.append(String.format("%s(%+.2f%%) ", sec.get("name"), toDouble(sec.get("changePercent"))));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 实时市场宽度（板块涨跌统计）
        if (realtimeSectors != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> breadth = (Map<String, Object>) realtimeSectors.get("marketBreadth");
            if (breadth != null && !breadth.isEmpty()) {
                sb.append("## 实时市场宽度（今日 T+0）\n");
                int upSec = toInt(breadth.get("upSectors"));
                int downSec = toInt(breadth.get("downSectors"));
                int totalSec = toInt(breadth.get("totalSectors"));
                sb.append(String.format("- 行业板块: %d涨 %d跌 (共%d个)\n", upSec, downSec, totalSec));
                int upStk = toInt(breadth.get("upStocks"));
                int downStk = toInt(breadth.get("downStocks"));
                int totalStk = toInt(breadth.get("totalStocks"));
                sb.append(String.format("- 板块内个股: 约%d涨 约%d跌 (共约%d只)\n", upStk, downStk, totalStk));
                sb.append("\n");
            }
        }

        // T-1 大盘背景（仅保留涨停跌停等无法实时获取的数据，已去掉过时的涨跌家数）
        String marketOverview = marketDataService.getMarketOverviewWithoutStaleStats();
        if (!marketOverview.isEmpty()) {
            sb.append("## 大盘补充（昨日数据 T-1）\n");
            sb.append(marketOverview).append("\n");
        }

        // ══════════════════════════════════════════
        //  ETF 报价（含净值/溢价率）
        // ══════════════════════════════════════════
        sb.append("## ETF 实时报价\n");
        if (quote != null) {
            sb.append(String.format("- 代码: %s  |  名称: %s\n", quote.get("symbol"), quote.get("name")));
            sb.append(String.format("- 现价: %.2f\n", toDouble(quote.get("price"))));
            sb.append(String.format("- 涨跌: %+.2f (%+.2f%%)\n", toDouble(quote.get("change")), toDouble(quote.get("changePercent"))));
            sb.append(String.format("- 今开: %.2f  |  昨收: %.2f\n", toDouble(quote.get("open")), toDouble(quote.get("prevClose"))));
            sb.append(String.format("- 日内: %.2f ~ %.2f\n", toDouble(quote.get("dayLow")), toDouble(quote.get("dayHigh"))));
            sb.append(String.format("- 成交量: %s\n", formatLarge(toLong(quote.get("volume")))));

            // 净值和溢价率
            Object nav = quote.get("nav");
            Object navRealtime = quote.get("navRealtime");
            Object navChg = quote.get("navChangePercent");
            Object premium = quote.get("premiumRate");
            Object premiumBasedOn = quote.get("premiumBasedOn");
            if (nav != null || navRealtime != null) {
                sb.append(String.format("\n## 净值与溢价率\n"));
                if (navRealtime != null) {
                    sb.append(String.format("- 盘中实时估值: %.4f\n", toDouble(navRealtime)));
                }
                if (nav != null) {
                    sb.append(String.format("- 昨日确认净值: %.4f\n", toDouble(nav)));
                }
                if (navChg != null) {
                    sb.append(String.format("- 净值估值涨跌幅: %+.2f%%\n", toDouble(navChg)));
                }
                if (premium != null) {
                    String basedOn = "realtime_estimate".equals(premiumBasedOn != null ? premiumBasedOn.toString() : "")
                            ? "基于盘中实时估值" : "基于昨日确认净值";
                    sb.append(String.format("- 溢价率: %+.2f%%（%s）", toDouble(premium), basedOn));
                    double premVal = toDouble(premium);
                    if (premVal > 0.5) sb.append("（溢价偏高，注意风险）");
                    else if (premVal > 0) sb.append("（小幅溢价）");
                    else if (premVal > -0.5) sb.append("（小幅折价）");
                    else sb.append("（折价明显，可能存在套利机会）");
                    sb.append("\n");
                }
            }
        }

        // 基金信息
        if (etfInfo != null && !etfInfo.isEmpty()) {
            sb.append("\n## 基金信息\n");
            Object fundName = etfInfo.get("fundName");
            if (fundName != null) sb.append(String.format("- 基金名称: %s\n", fundName));
            Object trackIndex = etfInfo.get("trackIndexName");
            if (trackIndex != null) sb.append(String.format("- 跟踪指数: %s\n", trackIndex));
            Object trackCode = etfInfo.get("trackIndexCode");
            if (trackCode != null) sb.append(String.format("- 指数代码: %s\n", trackCode));
            Object scale = etfInfo.get("fundScale");
            if (scale != null) sb.append(String.format("- 基金规模: %s\n", scale));
        }

        // 技术指标
        sb.append("\n## 技术指标\n");
        if (ind != null) {
            for (Map.Entry<String, Object> e : ind.entrySet()) {
                if (!"analysisDate".equals(e.getKey())) {
                    sb.append(String.format("- %s: %s\n", e.getKey(), e.getValue()));
                }
            }
        }

        // K 线数据
        Map<String, Object> marketData = (Map<String, Object>) analysis.get("marketData");
        List<Map<String, Object>> kline = marketData != null
                ? (List<Map<String, Object>>) marketData.get("dailyKline") : null;
        if (kline != null && !kline.isEmpty()) {
            sb.append("\n## 最近 K 线（最多 20 日）\n");
            sb.append("| 日期 | 开盘 | 最高 | 最低 | 收盘 | 成交量 |\n");
            sb.append("|------|------|------|------|------|--------|\n");
            for (Map<String, Object> bar : kline) {
                sb.append(String.format("| %s | %.2f | %.2f | %.2f | %.2f | %s |\n",
                        bar.get("date"), toDouble(bar.get("open")), toDouble(bar.get("high")),
                        toDouble(bar.get("low")), toDouble(bar.get("close")), formatLarge(toLong(bar.get("volume")))));
            }
        }

        sb.append("\n\n请基于以上 ETF 数据给出详细的交易分析建议：");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════

    private static double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    private static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }

    private static String formatLarge(long n) {
        if (n >= 1_000_000_000) return String.format("%.2fB", n / 1_000_000_000.0);
        if (n >= 1_000_000) return String.format("%.2fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.2fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
