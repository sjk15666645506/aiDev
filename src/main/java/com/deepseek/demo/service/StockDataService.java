package com.deepseek.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 真实股票数据服务 —— 通过 Python yfinance 脚本获取行情并计算技术指标。
 * <p>
 * Java 调用 {@code yfinance_data.py}（位于 ingestion-pipeline/ 目录），
 * 通过 ProcessBuilder 执行 Python 子进程，解析 JSON 输出。
 * <p>
 * 支持美股（AAPL）和 A 股（600519 → 600519.SS）。
 */
@Service
public class StockDataService {

    private static final Logger log = LoggerFactory.getLogger(StockDataService.class);

    private final ObjectMapper objectMapper;

    /** Python 脚本路径（相对于项目根目录） */
    private static final String SCRIPT_PATH = "ingestion-pipeline/yfinance_data.py";

    public StockDataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════

    /** 统一符号：美股直接返回，A 股 6 开头加 .SS，0/3 开头加 .SZ。 */
    public String normalizeSymbol(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String s = raw.trim().toUpperCase();
        if (s.matches("^[A-Z]{1,5}$")) return s;
        if (s.matches("\\d{6}")) {
            if (s.startsWith("6") || s.startsWith("9")) return s + ".SS";
            if (s.startsWith("0") || s.startsWith("3")) return s + ".SZ";
        }
        // 港股格式如 0700.HK
        if (s.matches("\\d{4}\\.HK")) return s;
        return s;
    }

    /** 获取实时报价。 */
    public Map<String, Object> getQuote(String symbol) {
        return callPython("quote", symbol);
    }

    /** 获取历史 K 线。 */
    public List<Map<String, Object>> getHistory(String symbol, String period, String interval) {
        String json = callPythonRaw("history", symbol, period, interval);
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("解析历史数据失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 综合股票分析：报价 + 多周期技术指标。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fullAnalysis(String symbol) {
        String json = callPythonRaw("full", symbol);
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            if (result.containsKey("error")) {
                log.warn("全量分析失败: symbol={}, error={}", symbol, result.get("error"));
            }
            return result;
        } catch (Exception e) {
            log.warn("解析全量分析失败: {}", e.getMessage());
            return Map.of("error", "解析市场数据失败: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Python 子进程调用
    // ═══════════════════════════════════════════════════════

    /** 调用 Python 脚本，返回 Map 结果。 */
    private Map<String, Object> callPython(String... args) {
        String json = callPythonRaw(args);
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析 Python 输出失败: {}", e.getMessage());
            return Map.of("error", "数据解析失败: " + e.getMessage());
        }
    }

    /** 调用 Python 脚本，返回原始 JSON 字符串。 */
    private String callPythonRaw(String... args) {
        List<String> command = new ArrayList<>();
        command.add("python3");
        command.add(SCRIPT_PATH);
        command.addAll(Arrays.asList(args));

        log.debug("调用 Python 脚本: {}", String.join(" ", command));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(".").getAbsoluteFile()); // 项目根目录
            pb.redirectErrorStream(false);

            Process process = pb.start();

            // 读取 stdout（JSON 结果）
            String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                stdout = reader.lines().collect(Collectors.joining("\n"));
            }

            // 读取 stderr（日志/错误）
            String stderr;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                stderr = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();

            if (exitCode != 0 && (stdout.isEmpty() || stdout.trim().isEmpty())) {
                log.warn("Python 脚本异常退出: exit={}, stderr={}", exitCode, stderr);
                return "{\"error\":\"数据获取失败: " + escapeJson(stderr) + "\"}";
            }

            if (!stderr.isEmpty()) {
                log.debug("Python stderr: {}", stderr);
            }

            return stdout;

        } catch (IOException e) {
            log.error("Python 子进程启动失败: {}", e.getMessage());
            return "{\"error\":\"无法启动数据脚本: " + escapeJson(e.getMessage()) + "\"}";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Python 子进程被中断");
            return "{\"error\":\"数据获取被中断\"}";
        }
    }

    /**
     * 搜索股票（支持代码和名称模糊匹配）。
     * 优先匹配 A 股（从本地 JSON 文件），再匹配常用美股。
     *
     * @param keyword 搜索关键字（代码或名称）
     * @param limit   最多返回数量
     * @return [{code, name, market, type}] 列表
     */
    public List<Map<String, String>> searchStock(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String q = keyword.trim().toLowerCase();

        List<Map<String, String>> results = new ArrayList<>();

        // 1. 搜索 A 股
        File stocksFile = new File("ingestion-pipeline/market_data/meta/stocks_list.json");
        if (stocksFile.exists()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(stocksFile), StandardCharsets.UTF_8))) {
                List<Map<String, String>> aShares = objectMapper.readValue(
                        br, new TypeReference<List<Map<String, String>>>() {});
                for (Map<String, String> s : aShares) {
                    if (results.size() >= limit) break;
                    String code = s.getOrDefault("code", "");
                    String name = s.getOrDefault("name", "");
                    if (code.startsWith(q) || name.toLowerCase().replace(" ", "").contains(q)) {
                        Map<String, String> item = new LinkedHashMap<>();
                        item.put("code", code);
                        item.put("name", (String) s.getOrDefault("name", ""));
                        item.put("market", (String) s.getOrDefault("market", "SZ"));
                        item.put("type", "A");
                        results.add(item);
                    }
                }
            } catch (Exception e) {
                log.warn("读取股票清单失败: {}", e.getMessage());
            }
        }

        // 2. 搜索常用美股
        if (results.size() < limit) {
            for (Map<String, String> us : US_STOCKS) {
                if (results.size() >= limit) break;
                String code = us.get("code").toLowerCase();
                String name = us.get("name").toLowerCase();
                if (code.startsWith(q) || name.contains(q)) {
                    if (results.stream().noneMatch(r -> code.equals(r.get("code")))) {
                        results.add(us);
                    }
                }
            }
        }

        return results;
    }

    /**
     * 常用美股列表（用于名称搜索）。
     */
    private static final List<Map<String, String>> US_STOCKS = List.of(
            stock("AAPL", "Apple Inc.", "US"),
            stock("TSLA", "Tesla Inc.", "US"),
            stock("MSFT", "Microsoft Corp.", "US"),
            stock("GOOGL", "Alphabet Inc.", "US"),
            stock("AMZN", "Amazon.com Inc.", "US"),
            stock("META", "Meta Platforms Inc.", "US"),
            stock("NVDA", "NVIDIA Corp.", "US"),
            stock("AMD", "Advanced Micro Devices", "US"),
            stock("INTC", "Intel Corp.", "US"),
            stock("BABA", "Alibaba Group", "US"),
            stock("PDD", "Pinduoduo Inc.", "US"),
            stock("NIO", "NIO Inc.", "US"),
            stock("BIDU", "Baidu Inc.", "US"),
            stock("TCEHY", "Tencent Holdings", "US"),
            stock("JPM", "JPMorgan Chase", "US"),
            stock("V", "Visa Inc.", "US"),
            stock("MA", "Mastercard Inc.", "US"),
            stock("JNJ", "Johnson & Johnson", "US"),
            stock("WMT", "Walmart Inc.", "US"),
            stock("KO", "Coca-Cola Co.", "US")
    );

    private static Map<String, String> stock(String code, String name, String market) {
        return Map.of("code", code, "name", name, "market", market, "type", market.equals("US") ? "US" : "A");
    }

    /** 简单 JSON 转义。 */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
