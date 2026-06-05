package com.deepseek.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MarketDataService 单元测试 — 重点验证 TOP 榜单格式。
 * <p>
 * 使用 Mockito spy mock loadDailyData() 来绕过文件系统依赖。
 */
class MarketDataServiceTest {

    private MarketDataService service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new MarketDataService(new ObjectMapper()));
    }

    /**
     * 模拟 loadDailyData 返回包含 topStocks 的数据。
     */
    private void mockDailyDataWithTopStocks(
            List<Map<String, Object>> gainers,
            List<Map<String, Object>> losers,
            List<Map<String, Object>> volume) {
        Map<String, Object> daily = new HashMap<>();
        Map<String, Object> topStocks = new HashMap<>();
        topStocks.put("topGainers", gainers);
        topStocks.put("topLosers", losers);
        topStocks.put("topVolume", volume);
        daily.put("topStocks", topStocks);
        daily.put("indices", Map.of());
        daily.put("marketStats", Map.of());
        Mockito.doReturn(daily).when(service).loadDailyData();
    }

    /**
     * 模拟 loadDailyData 返回包含 marketStats 的数据。
     */
    private void mockDailyDataWithStats(Map<String, Object> stats) {
        Map<String, Object> daily = new HashMap<>();
        daily.put("marketStats", stats);
        daily.put("indices", Map.of());
        Mockito.doReturn(daily).when(service).loadDailyData();
    }

    // ══════════════════════════════════════════
    //  getTopListInfo — 榜单格式测试
    // ══════════════════════════════════════════

    @Test
    void getTopListInfo_stockInGainers_shouldLabelGainers() {
        mockDailyDataWithTopStocks(
                List.of(Map.of("code", "600519", "name", "贵州茅台")),
                List.of(),
                List.of()
        );

        String result = service.getTopListInfo("600519.SS");

        assertTrue(result.contains("涨幅 TOP30"), "应包含'涨幅 TOP30'，实际: " + result);
        assertTrue(result.startsWith("## 市场关注度"), "标题应以'## 市场关注度'开头，实际: " + result);
    }

    @Test
    void getTopListInfo_stockInLosers_shouldLabelLosers() {
        mockDailyDataWithTopStocks(
                List.of(),
                List.of(Map.of("code", "000001", "name", "平安银行")),
                List.of()
        );

        String result = service.getTopListInfo("000001.SZ");

        assertTrue(result.contains("跌幅 TOP30"), "应包含'跌幅 TOP30'，实际: " + result);
        assertTrue(result.startsWith("## 市场关注度"), "标题应以'## 市场关注度'开头，实际: " + result);
    }

    @Test
    void getTopListInfo_stockInVolume_shouldLabelVolume() {
        mockDailyDataWithTopStocks(
                List.of(),
                List.of(),
                List.of(Map.of("code", "300750", "name", "宁德时代"))
        );

        String result = service.getTopListInfo("300750.SZ");

        assertTrue(result.contains("成交额 TOP30"), "应包含'成交额 TOP30'，实际: " + result);
        assertTrue(result.startsWith("## 市场关注度"), "标题应以'## 市场关注度'开头，实际: " + result);
    }

    @Test
    void getTopListInfo_stockInMultipleLists_shouldJoinWithChineseComma() {
        mockDailyDataWithTopStocks(
                List.of(Map.of("code", "600519", "name", "贵州茅台")),
                List.of(Map.of("code", "600519", "name", "贵州茅台")),
                List.of()
        );

        String result = service.getTopListInfo("600519.SS");

        assertTrue(result.contains("涨幅 TOP30"), "应包含涨幅标签");
        assertTrue(result.contains("跌幅 TOP30"), "应包含跌幅标签");
        assertTrue(result.contains("、"), "多标签应以中文顿号连接");
        assertTrue(result.startsWith("## 市场关注度"), "标题应以'## 市场关注度'开头");
    }

    @Test
    void getTopListInfo_stockNotInAnyList_shouldReturnEmpty() {
        mockDailyDataWithTopStocks(
                List.of(Map.of("code", "600519", "name", "贵州茅台")),
                List.of(),
                List.of()
        );

        String result = service.getTopListInfo("000001.SZ");
        assertEquals("", result);
    }

    @Test
    void getTopListInfo_noDailyData_shouldReturnEmpty() {
        Mockito.doReturn(null).when(service).loadDailyData();

        String result = service.getTopListInfo("600519.SS");
        assertEquals("", result);
    }

    @Test
    void getTopListInfo_noTopStocksKey_shouldReturnEmpty() {
        Map<String, Object> daily = new HashMap<>();
        daily.put("indices", Map.of());
        daily.put("marketStats", Map.of());
        Mockito.doReturn(daily).when(service).loadDailyData();

        String result = service.getTopListInfo("600519.SS");
        assertEquals("", result);
    }

    @Test
    void getTopListInfo_outputFormat_shouldMatchExpectedPattern() {
        mockDailyDataWithTopStocks(
                List.of(Map.of("code", "600519", "name", "贵州茅台")),
                List.of(),
                List.of(Map.of("code", "600519", "name", "贵州茅台"))
        );

        String result = service.getTopListInfo("600519.SS");

        // 验证标题格式: ## 市场关注度
        assertTrue(result.startsWith("## 市场关注度\n"),
                "输出应以 '## 市场关注度\\n' 开头，实际: " + result);
        // 验证列表行以 '- ' 开头
        String[] lines = result.split("\n");
        assertTrue(lines.length >= 2, "输出应至少有标题和一行数据");
        assertTrue(lines[1].startsWith("- "), "数据行应以 '- ' 开头");
    }

    // ══════════════════════════════════════════
    //  getMarketOverviewWithoutStaleStats — 兼容性测试
    // ══════════════════════════════════════════

    @Test
    void getMarketOverviewWithoutStaleStats_shouldReturnSameAsGetMarketOverview() {
        mockDailyDataWithStats(Map.of(
                "limitUpCount", 45,
                "limitDownCount", 12,
                "avgChange", "0.35"
        ));

        String result = service.getMarketOverviewWithoutStaleStats();

        // getMarketOverviewWithoutStaleStats 已废弃，委托给 getMarketOverview()
        assertTrue(result.contains("涨停"), "应包含'涨停'，实际: " + result);
        assertTrue(result.contains("平均涨跌幅"), "应包含'平均涨跌幅'，实际: " + result);
    }

    // ══════════════════════════════════════════
    //  symbol 后缀处理测试
    // ══════════════════════════════════════════

    @Test
    void getTopListInfo_shouldStripSSSuffix() {
        mockDailyDataWithTopStocks(
                List.of(Map.of("code", "600519", "name", "贵州茅台")),
                List.of(),
                List.of()
        );

        String result = service.getTopListInfo("600519.SS");
        assertTrue(result.contains("涨幅 TOP30"), ".SS 后缀应被正确去除");
    }

    @Test
    void getTopListInfo_shouldStripSZSuffix() {
        mockDailyDataWithTopStocks(
                List.of(),
                List.of(),
                List.of(Map.of("code", "000001", "name", "平安银行"))
        );

        String result = service.getTopListInfo("000001.SZ");
        assertTrue(result.contains("成交额 TOP30"), ".SZ 后缀应被正确去除");
    }
}
