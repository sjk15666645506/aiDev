package com.deepseek.demo.service.tools;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.Tool;
import com.deepseek.demo.annotation.ToolDomain;
import com.deepseek.demo.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * 金融域 mock 工具集。
 * <p>
 * 模拟股票交易、净值查询等金融操作，
 * 所有数据均为随机生成，仅用于演示。
 */
@Component
public class FinanceTools {

    private static final Logger log = LoggerFactory.getLogger(FinanceTools.class);
    private static final Random RANDOM = new Random();

    private static final String[] STOCK_NAMES = {
            "贵州茅台", "中国平安", "招商银行", "宁德时代", "美的集团",
            "五粮液", "恒瑞医药", "格力电器", "迈瑞医疗", "海康威视",
            "东方财富", "中信证券", "伊利股份", "比亚迪", "药明康德"
    };

    private static final String[] FUND_NAMES = {
            "易方达蓝筹精选", "中欧医疗健康", "招商中证白酒",
            "富国天惠", "兴全合润", "景顺长城鼎益", "汇添富价值精选"
    };

    /**
     * 买入股票。
     *
     * @param stockName 股票名称（可选，不传则随机）
     * @param shares    买入股数（可选，不传则随机 100-1000）
     * @return 成交结果
     */
    @Tool(name = "buy_stock",
          description = "买入股票，成交后返回买入详情，包含股票名称、股数、成交价格和总金额",
          domain = ToolDomain.FINANCE,
          capabilities = {"finance:trade"},
          parameters = {
              @ToolParam(name = "stock_name", type = "string",
                         description = "股票名称，如 贵州茅台、中国平安"),
              @ToolParam(name = "shares", type = "integer",
                         description = "买入股数")
          },
          action = ActionType.WRITE)
    public String buyStock(String stockName, Integer shares) {
        String name = (stockName != null && !stockName.isBlank()) ? stockName
                : STOCK_NAMES[RANDOM.nextInt(STOCK_NAMES.length)];
        int shareCount = (shares != null) ? shares
                : (RANDOM.nextInt(10) + 1) * 100;
        double price = 10 + RANDOM.nextDouble() * 200;
        double total = shareCount * price;

        log.info("买入股票: name={}, shares={}, price={:.2f}", name, shareCount, price);
        return String.format("成交！成功买入【%s】%d 股，成交价 %.2f 元/股，总金额 %.2f 元。",
                name, shareCount, price, total);
    }

    /**
     * 卖出股票。
     *
     * @param stockName 股票名称（可选，不传则随机）
     * @param shares    卖出股数（可选，不传则随机 100-500）
     * @return 成交结果
     */
    @Tool(name = "sell_stock",
          description = "卖出持仓股票，返回卖出成交详情",
          domain = ToolDomain.FINANCE,
          capabilities = {"finance:trade"},
          parameters = {
              @ToolParam(name = "stock_name", type = "string",
                         description = "股票名称，如 贵州茅台、中国平安"),
              @ToolParam(name = "shares", type = "integer",
                         description = "卖出股数")
          },
          action = ActionType.WRITE)
    public String sellStock(String stockName, Integer shares) {
        String name = (stockName != null && !stockName.isBlank()) ? stockName
                : STOCK_NAMES[RANDOM.nextInt(STOCK_NAMES.length)];
        int shareCount = (shares != null) ? shares
                : (RANDOM.nextInt(5) + 1) * 100;
        double price = 10 + RANDOM.nextDouble() * 200;

        log.info("卖出股票: name={}, shares={}", name, shareCount);
        return String.format("已卖出【%s】%d 股，成交价 %.2f 元/股，成交金额 %.2f 元。",
                name, shareCount, price, shareCount * price);
    }

    /**
     * 查询股票或基金净值。
     *
     * @param code 股票代码或基金代码（可选）
     * @return 净值信息
     */
    @Tool(name = "query_stock_nav",
          description = "查询股票或基金的最新单位净值、累计净值和日涨跌幅",
          domain = ToolDomain.FINANCE,
          capabilities = {"finance:query"},
          parameters = {
              @ToolParam(name = "code", type = "string",
                         description = "股票或基金代码，如 600519、110011")
          },
          action = ActionType.READ)
    public String queryStockNav(String code) {
        String name = RANDOM.nextBoolean()
                ? STOCK_NAMES[RANDOM.nextInt(STOCK_NAMES.length)]
                : FUND_NAMES[RANDOM.nextInt(FUND_NAMES.length)];
        double nav = 1.0 + RANDOM.nextDouble() * 10;
        double totalNav = nav * (1 + RANDOM.nextDouble() * 0.5);
        double changePercent = (RANDOM.nextDouble() - 0.5) * 8;
        String changeSign = changePercent >= 0 ? "+" : "";

        String codeDisplay = (code != null && !code.isBlank()) ? code : "------";

        log.info("查询净值: code={}", codeDisplay);
        return String.format(
                "【%s】（%s）\n单位净值：%.4f 元\n累计净值：%.4f 元\n日涨跌幅：%s%.2f%%\n更新日期：%s",
                name, codeDisplay, nav, totalNav, changeSign, changePercent,
                LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
    }

    /**
     * 查询当前持仓。
     *
     * @return 持仓列表
     */
    @Tool(name = "query_portfolio",
          description = "查询当前账户的股票/基金持仓，包含各品种的持有数量、市值和盈亏情况",
          domain = ToolDomain.FINANCE,
          capabilities = {"finance:query"},
          action = ActionType.READ)
    public String queryPortfolio() {
        int holdingCount = RANDOM.nextInt(5) + 2;
        double totalMarketValue = 0;

        StringBuilder sb = new StringBuilder();
        sb.append("📊 当前持仓（共 ").append(holdingCount).append(" 只）\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        for (int i = 0; i < holdingCount; i++) {
            String name = STOCK_NAMES[RANDOM.nextInt(STOCK_NAMES.length)];
            int shares = (RANDOM.nextInt(20) + 1) * 100;
            double costPrice = 10 + RANDOM.nextDouble() * 150;
            double currentPrice = costPrice * (1 + (RANDOM.nextDouble() - 0.45) * 0.3);
            double marketValue = shares * currentPrice;
            double profitPercent = (currentPrice - costPrice) / costPrice * 100;
            String profitSign = profitPercent >= 0 ? "+" : "";

            totalMarketValue += marketValue;

            sb.append(String.format("%d. %s  %d股\n", i + 1, name, shares));
            sb.append(String.format("   成本 %.2f | 现价 %.2f | 市值 %.2f | 盈亏 %s%.2f%%\n",
                    costPrice, currentPrice, marketValue, profitSign, profitPercent));
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append(String.format("总市值：%.2f 元", totalMarketValue));

        log.info("查询持仓: count={}, totalMarketValue={:.2f}", holdingCount, totalMarketValue);
        return sb.toString();
    }

    /**
     * 查询市场热门股票。
     *
     * @param market 市场类型：A股、港股、美股（可选，默认 A股）
     * @return 热门股票列表
     */
    @Tool(name = "query_hot_stocks",
          description = "查询当前市场热门/行情较好的股票列表，包含股票名称、代码、最新价格和涨跌幅。当用户询问'热门股票''行情''哪些股票好''推荐股票'时使用此工具",
          domain = ToolDomain.FINANCE,
          capabilities = {"finance:query"},
          parameters = {
              @ToolParam(name = "market", type = "string",
                         description = "市场类型，可选值：A股、港股、美股，默认为A股")
          },
          action = ActionType.READ)
    public String queryHotStocks(String market) {
        String mkt = (market != null && !market.isBlank()) ? market : "A股";
        log.info("查询热门股票: market={}", mkt);

        StringBuilder sb = new StringBuilder();
        sb.append("🔥 ").append(mkt).append("热门股票 TOP 5\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        for (int i = 0; i < 5; i++) {
            String name = STOCK_NAMES[RANDOM.nextInt(STOCK_NAMES.length)];
            double price = 10 + RANDOM.nextDouble() * 200;
            double change = (RANDOM.nextDouble() - 0.2) * 10;
            String sign = change >= 0 ? "+" : "";
            String trend = change >= 0 ? "📈" : "📉";

            sb.append(String.format("%d. %s  %s\n", i + 1, trend, name));
            sb.append(String.format("   最新价 %.2f 元  涨跌幅 %s%.2f%%\n", price, sign, change));
        }

        log.info("查询热门股票完成: market={}", mkt);
        return sb.toString();
    }

    /**
     * 查询近期交易记录。
     *
     * @return 交易记录列表
     */
    @Tool(name = "query_trade_history",
          description = "查询最近一段时间的交易记录，包含交易时间、方向、股票名称、数量和价格",
          domain = ToolDomain.FINANCE,
          capabilities = {"finance:query"},
          action = ActionType.READ)
    public String queryTradeHistory() {
        int recordCount = RANDOM.nextInt(6) + 3;

        StringBuilder sb = new StringBuilder();
        sb.append("📝 近期交易记录（共 ").append(recordCount).append(" 笔）\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        LocalDate today = LocalDate.now();
        for (int i = 0; i < recordCount; i++) {
            String name = STOCK_NAMES[RANDOM.nextInt(STOCK_NAMES.length)];
            int shares = (RANDOM.nextInt(10) + 1) * 100;
            double price = 10 + RANDOM.nextDouble() * 200;
            String direction = RANDOM.nextBoolean() ? "买入" : "卖出";
            LocalDate tradeDate = today.minusDays(RANDOM.nextInt(30) + 1);

            sb.append(String.format("%s  %s  %s  %d股  %.2f元\n",
                    tradeDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    direction, name, shares, price));
        }

        log.info("查询交易记录: count={}", recordCount);
        return sb.toString();
    }
}
