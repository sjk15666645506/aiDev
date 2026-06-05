package com.deepseek.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 读取 batch_collect.py 采集的全市场数据，为个股分析提供大盘上下文。
 * <p>
 * 数据来源：ingestion-pipeline/market_data/YYYYMMDD/training.json
 * 以及 meta/industry_map.json
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final String MARKET_DATA_DIR = "ingestion-pipeline/market_data";

    private final ObjectMapper objectMapper;

    /** 当天大盘数据缓存（每天首次访问时加载） */
    private Map<String, Object> dailyDataCache = null;
    private String cachedDate = null;

    public MarketDataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 获取当天市场整体概况。
     *
     * @return 市场概况文本（多行），若无数据则返回空字符串
     */
    public String getMarketOverview() {
        Map<String, Object> daily = loadDailyData();
        if (daily == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 今日大盘概况\n");

        // 指数
        @SuppressWarnings("unchecked")
        Map<String, Object> indices = (Map<String, Object>) daily.get("indices");
        if (indices != null && !indices.isEmpty()) {
            for (Map.Entry<String, Object> e : indices.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> idx = (Map<String, Object>) e.getValue();
                if (idx != null) {
                    String name = (String) idx.getOrDefault("name", "");
                    Object price = idx.get("price");
                    Object chgPct = idx.get("changePercent");
                    sb.append(String.format("- %s: %s (%+.2f%%)\n",
                            name, price != null ? price : "?", chgPct instanceof Number ? ((Number) chgPct).doubleValue() : 0));
                }
            }
        }

        // 全市场涨跌统计
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) daily.get("marketStats");
        if (stats != null && !stats.isEmpty()) {
            Object up = stats.get("upCount");
            Object down = stats.get("downCount");
            Object ratio = stats.get("upDownRatio");
            Object limitUp = stats.get("limitUpCount");
            Object limitDown = stats.get("limitDownCount");
            Object avgChg = stats.get("avgChange");

            sb.append(String.format("\n- 上涨: %s  |  下跌: %s  |  涨跌比: %s\n",
                    up != null ? up : "?", down != null ? down : "?", ratio != null ? ratio : "?"));
            sb.append(String.format("- 涨停: %s  |  跌停: %s\n",
                    limitUp != null ? limitUp : "?", limitDown != null ? limitDown : "?"));
            sb.append(String.format("- 全市场平均涨跌幅: %s%%\n", avgChg != null ? avgChg : "?"));

            // 涨跌分布
            @SuppressWarnings("unchecked")
            Map<String, Object> dist = (Map<String, Object>) stats.get("distribution");
            if (dist != null && !dist.isEmpty()) {
                sb.append("- 涨跌分布: ");
                sb.append(dist.entrySet().stream()
                        .map(e -> e.getKey() + ":" + e.getValue())
                        .collect(Collectors.joining(" | ")));
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 获取个股所属行业板块的表现。
     *
     * @param symbol      股票代码（如 600519）
     * @param stockChange 个股涨跌幅（%）
     * @return 行业板块上下文文本，若无法确定则返回空字符串
     */
    public String getSectorContext(String symbol, double stockChange) {
        String industry = lookupIndustry(symbol);
        if (industry == null || industry.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 行业板块\n");
        sb.append(String.format("- 所属行业: %s\n", industry));

        // 从 sectors.json 查找行业表现
        String sectorChange = lookupSectorChange(industry);
        if (sectorChange != null) {
            sb.append(String.format("- 行业板块涨跌幅: %s\n", sectorChange));
            // 相对强度
            double sectorChgVal = parsePercent(sectorChange);
            double diff = stockChange - sectorChgVal;
            sb.append(String.format("- 个股 vs 板块: %+.2f%%", diff));
            if (diff > 2) sb.append("（明显强于板块）");
            else if (diff > 0) sb.append("（略强于板块）");
            else if (diff > -2) sb.append("（略弱于板块）");
            else sb.append("（明显弱于板块）");
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 检查个股是否在今日的 TOP 榜单中。
     *
     * @param symbol 股票代码
     * @return 榜单信息文本，不在榜则返回空字符串
     */
    public String getTopListInfo(String symbol) {
        Map<String, Object> daily = loadDailyData();
        if (daily == null) return "";

        @SuppressWarnings("unchecked")
        Map<String, Object> topStocks = (Map<String, Object>) daily.get("topStocks");
        if (topStocks == null) return "";

        String code = symbol.replaceAll("\\.(SS|SZ)$", "");

        List<String> tags = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gainers = (List<Map<String, Object>>) topStocks.get("topGainers");
        if (gainers != null && gainers.stream().anyMatch(s -> code.equals(s.get("code")))) {
            tags.add("今日涨幅 TOP30");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> losers = (List<Map<String, Object>>) topStocks.get("topLosers");
        if (losers != null && losers.stream().anyMatch(s -> code.equals(s.get("code")))) {
            tags.add("今日跌幅 TOP30");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> volume = (List<Map<String, Object>>) topStocks.get("topVolume");
        if (volume != null && volume.stream().anyMatch(s -> code.equals(s.get("code")))) {
            tags.add("今日成交额 TOP30");
        }

        if (tags.isEmpty()) return "";

        return "## 市场关注度\n- " + String.join("、", tags) + "\n";
    }

    // ══════════════════════════════════════════════
    //  内部方法
    // ══════════════════════════════════════════════

    /**
     * 加载当天 training.json。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadDailyData() {
        String latestDate = findLatestDate();
        if (latestDate == null) return null;

        if (latestDate.equals(cachedDate) && dailyDataCache != null) {
            return dailyDataCache;
        }

        File file = new File(MARKET_DATA_DIR, latestDate + "/training.json");
        if (!file.exists()) {
            log.debug("未找到大盘数据: {}", file.getPath());
            return null;
        }

        try {
            dailyDataCache = objectMapper.readValue(file, Map.class);
            cachedDate = latestDate;
            return dailyDataCache;
        } catch (IOException e) {
            log.warn("读取大盘数据失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 在 market_data/ 下查找最新日期目录。
     */
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

    /**
     * 从 industry_map.json 查询个股行业。
     */
    private String lookupIndustry(String symbol) {
        String code = symbol.replaceAll("\\.(SS|SZ)$", "");
        File mapFile = new File(MARKET_DATA_DIR, "meta/industry_map.json");
        if (!mapFile.exists()) return null;

        try {
            Map<String, Map<String, String>> industryMap = objectMapper.readValue(
                    mapFile, new TypeReference<Map<String, Map<String, String>>>() {});
            Map<String, String> info = industryMap.get(code);
            return info != null ? info.get("industry") : null;
        } catch (IOException e) {
            log.debug("读取行业映射失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 sectors.json 查找行业板块涨跌幅。
     */
    @SuppressWarnings("unchecked")
    private String lookupSectorChange(String industry) {
        String latestDate = findLatestDate();
        if (latestDate == null) return null;

        File file = new File(MARKET_DATA_DIR, latestDate + "/sectors.json");
        if (!file.exists()) return null;

        try {
            Map<String, Object> sectors = objectMapper.readValue(file, Map.class);

            List<Map<String, Object>> top = (List<Map<String, Object>>) sectors.get("topSectors");
            if (top != null) {
                for (Map<String, Object> s : top) {
                    if (industry.equals(s.get("name"))) {
                        Object chg = s.get("changePercent");
                        return chg != null ? String.format("%+.2f%%", ((Number) chg).doubleValue()) : null;
                    }
                }
            }

            List<Map<String, Object>> bottom = (List<Map<String, Object>>) sectors.get("bottomSectors");
            if (bottom != null) {
                for (Map<String, Object> s : bottom) {
                    if (industry.equals(s.get("name"))) {
                        Object chg = s.get("changePercent");
                        return chg != null ? String.format("%+.2f%%", ((Number) chg).doubleValue()) : null;
                    }
                }
            }
        } catch (IOException e) {
            log.debug("读取板块数据失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 获取当天完整的 topStocks 榜单数据（涨幅 TOP10 + 跌幅 TOP10 + 成交额 TOP10）。
     */
    public Map<String, Object> getTopStocks() {
        Map<String, Object> daily = loadDailyData();
        if (daily == null) return Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> top = (Map<String, Object>) daily.get("topStocks");
        if (top == null) return Map.of();
        // 精简到各 TOP10
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("topGainers", "topLosers", "topVolume")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) top.get(key);
            if (list != null) result.put(key, list.subList(0, Math.min(list.size(), 10)));
        }
        return result;
    }

    /**
     * 获取当天大盘原始数据（用于 LLM 分析）。
     */
    public Map<String, Object> getDailyData() {
        return loadDailyData();
    }

    /**
     * 获取最新数据日期。
     */
    public String getLatestDate() {
        return findLatestDate();
    }

    /**
     * 获取当天 ETF 统计数据（从 etf_stats.json）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getEtfStats() {
        String latestDate = findLatestDate();
        if (latestDate == null) return Map.of();

        File file = new File(MARKET_DATA_DIR, latestDate + "/etf_stats.json");
        if (!file.exists()) return Map.of();

        try {
            return objectMapper.readValue(file, Map.class);
        } catch (IOException e) {
            log.debug("读取ETF统计失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 获取当天 TOP ETF 榜单（溢价榜/折价榜/成交量榜各 TOP10）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTopEtfs() {
        Map<String, Object> stats = getEtfStats();
        if (stats.isEmpty()) return Map.of();

        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("topGainers", "topLosers", "topVolume", "topPremium", "topDiscount")) {
            Object list = stats.get(key);
            if (list instanceof List) {
                List<Map<String, Object>> l = (List<Map<String, Object>>) list;
                result.put(key, l.subList(0, Math.min(l.size(), 10)));
            }
        }
        return result;
    }

    private static double parsePercent(String s) {
        try {
            return Double.parseDouble(s.replace("%", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
