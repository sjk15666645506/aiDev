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
 * ETF 数据服务 —— 通过 Python yfinance_data.py 获取 ETF 行情、净值和溢价率。
 * <p>
 * Java 调用 {@code yfinance_data.py etf-full}（位于 ingestion-pipeline/ 目录），
 * 通过 ProcessBuilder 执行 Python 子进程，解析 JSON 输出。
 * <p>
 * 支持 A 股 ETF（510050、159919 等）和美股 ETF（SPY、QQQ 等）。
 */
@Service
public class EtfDataService {

    private static final Logger log = LoggerFactory.getLogger(EtfDataService.class);

    private final ObjectMapper objectMapper;

    /** Python 脚本路径（相对于项目根目录） */
    private static final String SCRIPT_PATH = "ingestion-pipeline/yfinance_data.py";

    /** ETF 数据目录 */
    private static final String MARKET_DATA_DIR = "ingestion-pipeline/market_data";

    public EtfDataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════

    /** 获取 ETF 实时报价（含净值、溢价率）。 */
    public Map<String, Object> getQuote(String symbol) {
        return callPython("quote", symbol);
    }

    /** 获取 ETF 历史K线。 */
    public List<Map<String, Object>> getHistory(String symbol, String period, String interval) {
        String json = callPythonRaw("history", symbol, period, interval);
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("解析ETF历史数据失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** ETF 全量分析：报价 + 净值/溢价率 + 技术指标 + 基金信息。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fullAnalysis(String symbol) {
        String json = callPythonRaw("etf-full", symbol);
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            if (result.containsKey("error")) {
                log.warn("ETF全量分析失败: symbol={}, error={}", symbol, result.get("error"));
            }
            return result;
        } catch (Exception e) {
            log.warn("解析ETF全量分析失败: {}", e.getMessage());
            return Map.of("error", "解析ETF数据失败: " + e.getMessage());
        }
    }

    /**
     * 搜索 ETF（从本地 etf_list.json 模糊匹配）。
     *
     * @param keyword 搜索关键字（代码或名称）
     * @param limit   最多返回数量
     * @return [{code, name, market, type}] 列表
     */
    public List<Map<String, String>> searchEtf(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String q = keyword.trim().toLowerCase();

        List<Map<String, String>> results = new ArrayList<>();

        File etfFile = new File(MARKET_DATA_DIR, "meta/etf_list.json");
        if (etfFile.exists()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(etfFile), StandardCharsets.UTF_8))) {
                List<Map<String, String>> etfs = objectMapper.readValue(
                        br, new TypeReference<List<Map<String, String>>>() {});
                for (Map<String, String> e : etfs) {
                    if (results.size() >= limit) break;
                    String code = e.getOrDefault("code", "");
                    String name = e.getOrDefault("name", "");
                    if (code.startsWith(q) || name.toLowerCase().replace(" ", "").contains(q)) {
                        Map<String, String> item = new LinkedHashMap<>();
                        item.put("code", code);
                        item.put("name", name);
                        item.put("market", e.getOrDefault("market", "SZ"));
                        item.put("type", "ETF");
                        results.add(item);
                    }
                }
            } catch (Exception ex) {
                log.warn("读取ETF清单失败: {}", ex.getMessage());
            }
        }

        // 补充常用美股 ETF
        if (results.size() < limit) {
            for (Map<String, String> us : US_ETFS) {
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
     * 加载当日 ETF 统计数据（从 etf_stats.json）。
     *
     * @return ETF 统计信息，若无数据返回空 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getEtfStats() {
        String latestDate = findLatestDate();
        if (latestDate == null) return Map.of();

        File file = new File(MARKET_DATA_DIR, latestDate + "/etf_stats.json");
        if (!file.exists()) {
            log.debug("未找到ETF统计: {}", file.getPath());
            return Map.of();
        }

        try {
            return objectMapper.readValue(file, Map.class);
        } catch (IOException e) {
            log.warn("读取ETF统计失败: {}", e.getMessage());
            return Map.of();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  常用美股 ETF 列表
    // ═══════════════════════════════════════════════════════

    private static final List<Map<String, String>> US_ETFS = List.of(
            etf("SPY", "SPDR S&P 500 ETF", "US"),
            etf("QQQ", "Invesco QQQ Trust", "US"),
            etf("IWM", "iShares Russell 2000 ETF", "US"),
            etf("DIA", "SPDR Dow Jones Industrial Average ETF", "US"),
            etf("VTI", "Vanguard Total Stock Market ETF", "US"),
            etf("VEA", "Vanguard FTSE Developed Markets ETF", "US"),
            etf("VWO", "Vanguard FTSE Emerging Markets ETF", "US"),
            etf("AGG", "iShares Core US Aggregate Bond ETF", "US"),
            etf("GLD", "SPDR Gold Shares", "US"),
            etf("TLT", "iShares 20+ Year Treasury Bond ETF", "US"),
            etf("XLF", "Financial Select Sector SPDR Fund", "US"),
            etf("XLE", "Energy Select Sector SPDR Fund", "US"),
            etf("KWEB", "KraneShares CSI China Internet ETF", "US"),
            etf("FXI", "iShares China Large-Cap ETF", "US"),
            etf("MCHI", "iShares MSCI China ETF", "US")
    );

    private static Map<String, String> etf(String code, String name, String market) {
        return Map.of("code", code, "name", name, "market", market, "type", "ETF");
    }

    // ═══════════════════════════════════════════════════════
    //  Python 子进程调用
    // ═══════════════════════════════════════════════════════

    private Map<String, Object> callPython(String... args) {
        String json = callPythonRaw(args);
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析Python输出失败: {}", e.getMessage());
            return Map.of("error", "数据解析失败: " + e.getMessage());
        }
    }

    private String callPythonRaw(String... args) {
        List<String> command = new ArrayList<>();
        command.add("python3");
        command.add(SCRIPT_PATH);
        command.addAll(Arrays.asList(args));

        log.debug("调用Python脚本: {}", String.join(" ", command));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(".").getAbsoluteFile());
            pb.redirectErrorStream(false);

            Process process = pb.start();

            String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                stdout = reader.lines().collect(Collectors.joining("\n"));
            }

            String stderr;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                stderr = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();

            if (exitCode != 0 && (stdout.isEmpty() || stdout.trim().isEmpty())) {
                log.warn("Python脚本异常退出: exit={}, stderr={}", exitCode, stderr);
                return "{\"error\":\"数据获取失败: " + escapeJson(stderr) + "\"}";
            }

            if (!stderr.isEmpty()) {
                log.debug("Python stderr: {}", stderr);
            }

            return stdout;

        } catch (IOException e) {
            log.error("Python子进程启动失败: {}", e.getMessage());
            return "{\"error\":\"无法启动数据脚本: " + escapeJson(e.getMessage()) + "\"}";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Python子进程被中断");
            return "{\"error\":\"数据获取被中断\"}";
        }
    }

    // ═══════════════════════════════════════════════════════
    //  内部辅助
    // ═══════════════════════════════════════════════════════

    private String findLatestDate() {
        File base = new File(MARKET_DATA_DIR);
        File[] dirs = base.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return null;

        return Arrays.stream(dirs)
                .map(File::getName)
                .filter(name -> name.matches("\\d{8}"))
                .max(String::compareTo)
                .orElse(null);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
