package com.deepseek.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 交易分析服务 —— 获取真实市场数据 → 构建专家提示词 → 调用 DeepSeek V4 Flash → 返回交易建议。
 * <p>
 * 仅负责推理分析，不存储训练数据。
 */
@Service
public class TradingAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TradingAnalysisService.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final StockDataService stockDataService;
    private final ILlmService llmService;
    private final MarketDataService marketDataService;

    public TradingAnalysisService(StockDataService stockDataService,
                                   ILlmService llmService,
                                   MarketDataService marketDataService) {
        this.stockDataService = stockDataService;
        this.llmService = llmService;
        this.marketDataService = marketDataService;
    }

    /**
     * 交易分析入口。
     *
     * @param symbol      股票代码（如 AAPL、600519）
     * @param userRequest 用户补充需求（可选）
     * @return 包含报价、技术指标、LLM 分析的结构
     */
    public Map<String, Object> analyze(String symbol, String userRequest) {
        long start = System.currentTimeMillis();
        log.info("交易分析开始: symbol={}, request={}", symbol, userRequest);

        // 1. 获取市场数据
        Map<String, Object> analysis = stockDataService.fullAnalysis(symbol);
        if (analysis.containsKey("error")) {
            log.warn("交易分析失败(数据获取错误): symbol={}", symbol);
            return analysis;
        }

        // 2. 构建 LLM 提示词
        String systemPrompt = buildTradingSystemPrompt();
        String marketContext = buildMarketContext(symbol, analysis);
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
        log.info("交易分析完成: symbol={}, 耗时={}ms, LLM回复长度={}",
                symbol, elapsed, llmReply.length());

        return analysis;
    }

    /**
     * 批量分析多个股票（用于组合推荐）。
     */
    public List<Map<String, Object>> batchAnalyze(List<String> symbols) {
        return symbols.stream()
                .map(sym -> {
                    try {
                        return analyze(sym, "请简要分析");
                    } catch (Exception e) {
                        log.warn("批量分析失败: symbol={}", sym);
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
    private String buildTradingSystemPrompt() {
        StringBuilder p = new StringBuilder();
        p.append("你是一位资深股票分析师，精通技术面和基本面分析。\n");
        p.append("根据提供的市场数据（大盘背景 + 个股详情），给出专业、客观的交易建议。\n\n");
        p.append("请按以下结构输出分析：\n\n");
        p.append("===== 大盘背景 =====\n");
        p.append("- 今日市场整体情绪（上涨/下跌家数比、涨停跌停数）\n");
        p.append("- 主要指数位置及趋势\n");
        p.append("- 当前市场风格（大盘/小盘、主题/价值）\n\n");
        p.append("===== 趋势研判 =====\n");
        p.append("- 当前趋势方向（上升/下降/震荡）及强度\n");
        p.append("- 关键均线排列（MA5/MA10/MA20/MA60）\n");
        p.append("- MACD 信号解读\n");
        p.append("- 个股 vs 大盘/板块的相对强弱\n\n");
        p.append("===== 关键技术位 =====\n");
        p.append("- 支撑位 / 阻力位\n");
        p.append("- RSI 超买/超卖判断\n");
        p.append("- 波动率评估\n\n");
        p.append("===== 交易建议 =====\n");
        p.append("- 操作方向（买入/持有/观望/卖出）\n");
        p.append("- 建议入场区间\n");
        p.append("- 止损位\n");
        p.append("- 止盈位\n");
        p.append("- 盈亏比\n");
        p.append("- 仓位建议（占总投资比例）\n\n");
        p.append("===== 基本面分析 =====\n");
        p.append("- 估值水平（PE/PB 是否合理）\n");
        p.append("- 盈利能力（毛利率、净利率、ROE 趋势）\n");
        p.append("- 成长性（营收和利润的同比增速）\n");
        p.append("- 财务健康度（资产负债率）\n\n");
        p.append("===== 风险提示 =====\n");
        p.append("- 当前主要风险因素\n");
        p.append("- 需要关注的催化剂/事件\n\n");
        p.append("重要原则：\n");
        p.append("1. 数据来源为公开市场数据，仅供参考\n");
        p.append("2. 所有建议不构成投资建议，仅为技术分析参考\n");
        p.append("3. 如果数据不足，请明确说明局限性\n");
        p.append("4. 用中文回答，保持专业、冷静、客观\n");
        p.append("5. 明确标出不确定性高的判断\n");
        p.append("6. 优先从大盘背景出发理解个股走势，避免脱离市场环境孤立分析");
        return p.toString();
    }

    private String buildMarketContext(String symbol, Map<String, Object> analysis) {
        @SuppressWarnings("unchecked")
        Map<String, Object> quote = (Map<String, Object>) analysis.get("quote");
        @SuppressWarnings("unchecked")
        Map<String, Object> ind = (Map<String, Object>) analysis.get("indicators");
        @SuppressWarnings("unchecked")
        Map<String, Object> marketData = (Map<String, Object>) analysis.get("marketData");

        StringBuilder sb = new StringBuilder();

        // ══════════════════════════════════════════
        //  实时大盘指数（T+0，与个股数据同一时刻）
        // ══════════════════════════════════════════
        @SuppressWarnings("unchecked")
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
        @SuppressWarnings("unchecked")
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

        // T-1 大盘补充（仅保留涨停跌停等无法实时获取的数据）
        String marketOverview = marketDataService.getMarketOverviewWithoutStaleStats();
        if (!marketOverview.isEmpty()) {
            sb.append("## 大盘补充（昨日数据 T-1）\n");
            sb.append(marketOverview).append("\n");
        }

        // 行业板块相对强度（T-1 数据，实时板块已在上文展示）
        double changePct = quote != null && quote.get("changePercent") != null
                ? ((Number) quote.get("changePercent")).doubleValue() : 0;
        String sectorCtx = marketDataService.getSectorContext(symbol, changePct);
        if (!sectorCtx.isEmpty()) {
            sb.append("## 个股所属板块（昨日数据 T-1）\n");
            sb.append(sectorCtx).append("\n");
        }

        // TOP 榜单
        String topInfo = marketDataService.getTopListInfo(symbol);
        if (!topInfo.isEmpty()) {
            sb.append(topInfo).append("\n");
        }

        // ══════════════════════════════════════════
        //  个股数据
        // ══════════════════════════════════════════
        sb.append("## 实时报价\n");
        sb.append(String.format("- 代码: %s  |  名称: %s\n", quote.get("symbol"), quote.get("name")));
        sb.append(String.format("- 现价: %.2f %s\n", quote.get("price"), quote.get("currency")));
        sb.append(String.format("- 涨跌: %+.2f (%+.2f%%)\n", quote.get("change"), quote.get("changePercent")));
        sb.append(String.format("- 今开: %.2f  |  昨收: %.2f\n", quote.get("open"), quote.get("prevClose")));
        sb.append(String.format("- 日内: %.2f ~ %.2f\n", quote.get("dayLow"), quote.get("dayHigh")));
        sb.append(String.format("- 成交量: %s  |  市值: %s\n",
                formatLarge(toLong(quote.get("volume"))), formatLarge(toLong(quote.get("marketCap")))));

        // 基本面数据（A 股）
        @SuppressWarnings("unchecked")
        Map<String, Object> fund = (Map<String, Object>) analysis.get("fundamentals");
        if (fund != null && !fund.isEmpty() && fund.get("eps") != null) {
            double price = toDouble(quote.get("price"));
            double eps = toDouble(fund.get("eps"));
            double bps = toDouble(fund.get("bps"));
            sb.append("\n## 基本面\n");
            sb.append(String.format("- 财报期: %s\n", fund.get("reportPeriod")));
            sb.append(String.format("- 每股收益(EPS): %.2f 元  |  每股净资产(BPS): %.2f 元\n", eps, bps));
            sb.append(String.format("- 市盈率(PE): %.1f  |  市净率(PB): %.2f\n",
                    eps > 0 ? price / eps : 0, bps > 0 ? price / bps : 0));
            sb.append(String.format("- 营收: %.2f 亿  |  同比: %s%%\n",
                    toDouble(fund.get("revenue")) / 1e8, fund.get("revenueYoy")));
            sb.append(String.format("- 净利润: %.2f 亿  |  同比: %s%%\n",
                    toDouble(fund.get("netProfit")) / 1e8, fund.get("netProfitYoy")));
            sb.append(String.format("- 毛利率: %.2f%%  |  净利率: %.2f%%\n",
                    toDouble(fund.get("grossMargin")), toDouble(fund.get("netMargin"))));
            sb.append(String.format("- ROE: %.2f%%  |  资产负债率: %.2f%%\n",
                    toDouble(fund.get("roe")), toDouble(fund.get("liabilityRatio"))));
        }

        // 时序预测（PatchTST 结果）
        @SuppressWarnings("unchecked")
        Map<String, Object> pred = (Map<String, Object>) analysis.get("prediction");
        if (pred != null && !pred.isEmpty() && pred.get("summary") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) pred.get("summary");
            sb.append("\n## 时序预测 (PatchTST)\n");
            sb.append(String.format("- 趋势方向: %s\n", summary.get("direction")));
            sb.append(String.format("- 预期涨跌幅: %s%%\n", summary.get("expectedChange")));
            sb.append(String.format("- 置信度: %s\n", summary.get("confidence")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> preds = (List<Map<String, Object>>) pred.get("predictions");
            if (preds != null && !preds.isEmpty()) {
                sb.append("- 未来5日预测:\n");
                for (Map<String, Object> p : preds) {
                    @SuppressWarnings("unchecked")
                    List<Object> range = (List<Object>) p.get("range_80pct");
                    sb.append(String.format("  第%d日: %.2f (80%%区间: %.2f~%.2f)\n",
                            p.get("day"), p.get("mean"),
                            range != null && range.size() > 0 ? toDouble(range.get(0)) : 0,
                            range != null && range.size() > 1 ? toDouble(range.get(1)) : 0));
                }
            }
        }

        sb.append("\n## 技术指标\n");
        if (ind != null) {
            for (Map.Entry<String, Object> e : ind.entrySet()) {
                if (!"analysisDate".equals(e.getKey())) {
                    sb.append(String.format("- %s: %s\n", e.getKey(), e.getValue()));
                }
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kline = marketData != null
                ? (List<Map<String, Object>>) marketData.get("dailyKline") : null;
        if (kline != null && !kline.isEmpty()) {
            sb.append("\n## 最近 K 线（最多 20 日）\n");
            sb.append("| 日期 | 开盘 | 最高 | 最低 | 收盘 | 成交量 |\n");
            sb.append("|------|------|------|------|------|--------|\n");
            for (Map<String, Object> bar : kline) {
                sb.append(String.format("| %s | %.2f | %.2f | %.2f | %.2f | %s |\n",
                        bar.get("date"), bar.get("open"), bar.get("high"),
                        bar.get("low"), bar.get("close"), formatLarge(toLong(bar.get("volume")))));
            }
        }

        sb.append("\n\n请基于以上数据给出详细的交易分析建议：");
        return sb.toString();
    }

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
