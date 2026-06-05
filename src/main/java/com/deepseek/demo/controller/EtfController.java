package com.deepseek.demo.controller;

import com.deepseek.demo.service.EtfAnalysisService;
import com.deepseek.demo.service.EtfDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ETF 交易分析 API 控制器。
 * <p>
 * 提供ETF实时报价、净值/溢价率、技术分析和LLM分析报告功能。
 * 对应前端 ETF 分析页面。
 */
@RestController
@RequestMapping("/api/etf")
public class EtfController {

    private static final Logger log = LoggerFactory.getLogger(EtfController.class);

    private final EtfDataService etfDataService;
    private final EtfAnalysisService etfAnalysisService;

    public EtfController(EtfDataService etfDataService,
                          EtfAnalysisService etfAnalysisService) {
        this.etfDataService = etfDataService;
        this.etfAnalysisService = etfAnalysisService;
    }

    /**
     * ETF 交易分析主接口。
     * <p>
     * 请求：
     * <pre>
     * {
     *   "symbol": "510050",          // 必填，ETF 代码
     *   "request": "关注溢价率变化"   // 可选，用户补充需求
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
        log.info("ETF分析请求: symbol={}, request={}", symbol, userRequest);

        Map<String, Object> result = etfAnalysisService.analyze(symbol, userRequest);
        if (result.containsKey("error")) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 批量分析多个 ETF。
     * <pre>
     * { "symbols": ["510050", "159919", "510300"] }
     * </pre>
     */
    @PostMapping("/batch")
    public ResponseEntity<List<Map<String, Object>>> batchAnalyze(@RequestBody Map<String, List<String>> req) {
        List<String> symbols = req.getOrDefault("symbols", List.of());
        if (symbols.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        log.info("ETF批量分析: symbols={}", symbols);
        return ResponseEntity.ok(etfAnalysisService.batchAnalyze(symbols));
    }

    /**
     * 快速查询 ETF 实时报价（含净值、溢价率）。
     */
    @GetMapping("/quote/{symbol}")
    public ResponseEntity<Map<String, Object>> quote(@PathVariable String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol 不能为空"));
        }
        return ResponseEntity.ok(etfDataService.getQuote(symbol.trim()));
    }

    /**
     * 查询 ETF 技术指标（含净值/溢价率）。
     */
    @GetMapping("/indicators/{symbol}")
    public ResponseEntity<Map<String, Object>> indicators(@PathVariable String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol 不能为空"));
        }
        Map<String, Object> result = etfDataService.fullAnalysis(symbol.trim());
        if (result.containsKey("error")) {
            return ResponseEntity.ok(result);
        }
        // 只返回报价和技术指标，不调 LLM
        result.remove("marketData");
        return ResponseEntity.ok(result);
    }

    /**
     * 搜索 ETF（代码或名称）。
     */
    @GetMapping("/search")
    public ResponseEntity<List<Map<String, String>>> search(@RequestParam("q") String q,
                                                             @RequestParam(value = "limit", defaultValue = "10") int limit) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(etfDataService.searchEtf(q, Math.min(limit, 20)));
    }

    /**
     * 获取今日 ETF 市场统计。
     * <p>
     * 需要先运行 batch_collect.py 采集当日 ETF 数据。
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> result = etfDataService.getEtfStats();
        if (result.isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "暂无ETF统计数据，请先运行 batch_collect.py"));
        }
        return ResponseEntity.ok(result);
    }
}
