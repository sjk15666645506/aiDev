package com.deepseek.demo.controller;

import com.deepseek.demo.service.MarketAnalysisService;
import com.deepseek.demo.service.StockDataService;
import com.deepseek.demo.service.TradingAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 交易分析 API 控制器。
 * <p>
 * 提供实时行情获取和技术分析功能，对接本地 Ollama LLM 生成交易建议。
 */
@RestController
@RequestMapping("/api/trading")
public class TradingController {

    private static final Logger log = LoggerFactory.getLogger(TradingController.class);

    private final StockDataService stockDataService;
    private final TradingAnalysisService tradingAnalysisService;
    private final MarketAnalysisService marketAnalysisService;

    public TradingController(StockDataService stockDataService,
                              TradingAnalysisService tradingAnalysisService,
                              MarketAnalysisService marketAnalysisService) {
        this.stockDataService = stockDataService;
        this.tradingAnalysisService = tradingAnalysisService;
        this.marketAnalysisService = marketAnalysisService;
    }

    /**
     * 交易分析主接口。
     * <p>
     * 请求：
     * <pre>
     * {
     *   "symbol": "AAPL",         // 必填，股票代码
     *   "request": "关注短线机会"   // 可选，用户补充需求
     * }
     * </pre>
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, String> req) {
        String symbol = req.getOrDefault("symbol", "").trim();
        if (symbol.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol 不能为空"));
        }

        String userRequest = req.getOrDefault("request", "");
        log.info("交易分析请求: symbol={}, request={}", symbol, userRequest);

        Map<String, Object> result = tradingAnalysisService.analyze(symbol, userRequest);
        if (result.containsKey("error")) {
            return ResponseEntity.ok(result); // 200 但带 error 字段，前端自行展示
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 批量分析多个股票。
     * <pre>
     * { "symbols": ["AAPL", "600519", "TSLA"] }
     * </pre>
     */
    @PostMapping("/batch")
    public ResponseEntity<List<Map<String, Object>>> batchAnalyze(@RequestBody Map<String, List<String>> req) {
        List<String> symbols = req.getOrDefault("symbols", List.of());
        if (symbols.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        log.info("批量分析: symbols={}", symbols);
        return ResponseEntity.ok(tradingAnalysisService.batchAnalyze(symbols));
    }

    /**
     * 快速查询实时报价。
     */
    @GetMapping("/quote/{symbol}")
    public ResponseEntity<Map<String, Object>> quote(@PathVariable String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol 不能为空"));
        }
        return ResponseEntity.ok(stockDataService.getQuote(symbol.trim()));
    }

    /**
     * 查询技术指标。
     */
    @GetMapping("/indicators/{symbol}")
    public ResponseEntity<Map<String, Object>> indicators(@PathVariable String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol 不能为空"));
        }
        Map<String, Object> result = stockDataService.fullAnalysis(symbol.trim());
        if (result.containsKey("error")) {
            return ResponseEntity.ok(result);
        }
        // 只返回报价和技术指标，不调 LLM
        result.remove("marketData");
        return ResponseEntity.ok(result);
    }

    /**
     * 搜索股票（代码或名称）。
     */
    @GetMapping("/search")
    public ResponseEntity<List<Map<String, String>>> search(@RequestParam("q") String q,
                                                              @RequestParam(value = "limit", defaultValue = "10") int limit) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(stockDataService.searchStock(q, Math.min(limit, 20)));
    }

    /**
     * 大盘分析 — 基于今日全市场数据，分析盘面情绪、关注板块和个股。
     * <p>
     * 需要先运行 batch_collect.py 采集当日数据。
     */
    @PostMapping("/market-analysis")
    public ResponseEntity<Map<String, Object>> marketAnalysis() {
        Map<String, Object> result = marketAnalysisService.analyzeMarket();
        return ResponseEntity.ok(result);
    }
}
