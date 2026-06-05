# A 股 + ETF 数据采集 — 每日操作说明

> 每个交易日收盘后运行，采集全市场数据用于行情分析和时序模型训练。
> 采集完成约需 **15 秒**，总数据量 **~250KB/天**。
> 含个股 + ETF 数据，ETF 额外提供净值和溢价率。

---

## 一、定时任务（推荐）

设置 crontab，交易日 15:30 自动运行：

```bash
crontab -e

# 添加一行（注意替换为你的实际路径）：
30 15 * * 1-5 cd /Users/sunjiakai/localProject/AIDev/AIDev/ingestion-pipeline && python3 batch_collect.py >> collect.log 2>&1
```

**跳过 ETF**：如仅需股票数据，加 `--no-etf`：
```bash
python3 batch_collect.py --no-etf
```

**解释**：
- `30 15` → 每天 15:30（A 股 15:00 收盘，等 30 分钟确保数据稳定）
- `* * 1-5` → 周一到周五（交易日）
- `>> collect.log` → 日志追加，方便排查问题

### 验证定时任务

```bash
# 查看已设置的定时任务
crontab -l

# 查看最近一次采集日志
tail -20 ~/localProject/AIDev/AIDev/ingestion-pipeline/collect.log
```

---

## 二、手动采集

### 日常采集

```bash
cd ~/localProject/AIDev/AIDev/ingestion-pipeline
python3 batch_collect.py
```

正常输出示例：
```
股票: 5488 只
行业映射: 1423 只
指数: 上证指数, 深证成指, 创业板指, 科创50, 沪深300, 上证50, 中证500
采集个股 (5488 只)...
  [800/5488] +800 | 交易中 760
  [1600/5488] +792 | 交易中 1531
  [2400/5488] +789 | 交易中 2313
  [3200/5488] +792 | 交易中 3104
  [4000/5488] +800 | 交易中 3865
  [4800/5488] +800 | 交易中 4663
  [5488/5488] +688 | 交易中 5350
✅ 20260603
   个股: 5461 (交易中 5350) | 快照 169KB
   涨跌: ↑1663 ↓3511 (比 0.47) | 涨停84 跌停16
   上证: 4083.974 (+0.22%)
   板块: 52 个 | 最强 白酒 +2.3%
```

### 查看采集状态

```bash
python3 batch_collect.py --status
```

输出示例：
```
📊 全量 A 股数据采集
   天数: 15 | 20260603 ~ 20260623
   20260603: 交易5350 ↑1663↓3511 上证4083.97 (+0.22%)
   20260604: 交易5321 ↑2893↓2388 上证4123.56 (+0.97%)
   ...
   总存储: 2.55 MB
```

### 刷新股票清单

```bash
# 每 30 天自动刷新一次，想手动刷新：
python3 batch_collect.py --update-list
```

---

## 三、数据存在哪

```
~/localProject/AIDev/AIDev/ingestion-pipeline/market_data/
├── meta/                              ← 元数据（不用管，自动维护）
│   ├── stocks_list.json               ← A 股清单（5488 只）
│   ├── etf_list.json                  ← ETF 清单（~1000 只）
│   ├── industry_map.json              ← 行业映射（增量累积）
│   └── history_index.json             ← 采集历史索引
├── 20260603/                          ← 每天一个目录
│   ├── snapshot.json.gz               ← 全市场快照（~170KB gzip）
│   ├── etf_snapshot.json.gz           ← ETF 快照+净值（~30KB gzip）
│   ├── indices.json                   ← 大盘指数（7 个）
│   ├── market_stats.json              ← 涨跌统计
│   ├── etf_stats.json                 ← ETF 统计（净值/溢价率/TOP ETF）
│   ├── sectors.json                   ← 行业板块排行
│   ├── top_stocks.json                ← TOP 榜单
│   └── training.json                  ← 汇总训练样本
└── 20260604/
    └── ...
```

---

## 四、磁盘占用预估

| 时间 | 数据量 |
|------|--------|
| 每天 | ~250 KB |
| 一个月（~22 个交易日） | ~5.5 MB |
| 一年（~250 个交易日） | ~62 MB |
| 十年 | ~620 MB |

**无需手动清理。** 如果需要可以删除历史数据：

```bash
# 删除指定日期的数据
rm -rf ~/localProject/AIDev/AIDev/ingestion-pipeline/market_data/20260601/

# 删除某个月之前的所有数据
find ~/localProject/AIDev/AIDev/ingestion-pipeline/market_data/ -maxdepth 1 -type d -name "2026*" | sort | head -n -30 | xargs rm -rf
# （保留最近 30 个交易日的数据）
```

---

## 五、首次运行

如果是**第一次运行**，会自动做一次全量股票发现（~25 秒）：

```bash
cd ~/localProject/AIDev/AIDev/ingestion-pipeline
python3 batch_collect.py

# 输出示例（首次）：
# 首次发现 A 股代码...
# 共 5488 只 A 股
# 采集个股 (5488 只)...
# ...（后续和日常采集一样）
```

以后每次运行就只需要 **~10 秒**（股票清单已缓存）。

---

## 六、单独查询 ETF

除了批量采集，也可用 `yfinance_data.py` 单独查询 ETF：

```bash
# A 股 ETF 报价（510050 = 上证50ETF）
python3 yfinance_data.py quote 510050

# A 股 ETF 历史 K 线
python3 yfinance_data.py history 510050 1mo 1d

# A 股 ETF 全量分析（含净值、溢价率、技术指标）
python3 yfinance_data.py etf-full 510050

# 美股 ETF 全量分析（SPY = 标普500 ETF）
python3 yfinance_data.py etf-full SPY
```

**A 股 ETF 代码规则**：
- 上海 ETF：510xxx-519xxx, 560xxx-569xxx, 580xxx-589xxx
- 深圳 ETF：159xxx
- 深圳 LOF：150xxx, 164xxx-166xxx
- 上海 LOF：501xxx-502xxx

**常用 A 股 ETF**：
| 代码 | 名称 | 跟踪指数 |
|------|------|----------|
| 510050 | 上证50ETF | 上证50 |
| 510300 | 沪深300ETF | 沪深300 |
| 510500 | 中证500ETF | 中证500 |
| 159919 | 沪深300ETF | 沪深300 |
| 159901 | 深100ETF | 深证100 |
| 588000 | 科创50ETF | 科创50 |

---

## 七、注意事项

| 场景 | 说明 |
|------|------|
| **周末/节假日** | 定时任务不会触发（`1-5` 只周一到周五），但可以手动跑（返回空数据，不影响） |
| **采集失败** | 检查 `collect.log` 中的错误信息。网络问题通常重试一次就好 |
| **行业映射不全** | 首次只映射了部分股票，行业板块会随着每次使用逐步覆盖。不影响其他数据 |
| **跨天数据** | 每天第一次运行会创建新目录，不会覆盖之前的数据 |
| **股票清单更新** | 每 30 天自动刷新，IPO 新股会自动加入 |
| **ETF 净值** | 部分新发 ETF 可能无净值数据（东方财富基金估值接口延迟），不影响行情数据 |
| **ETF 代码** | 首次运行会自动发现 ETF 代码（~20 秒），30 天缓存 |
| **溢价率** | 交易价格与基金净值的偏离，正值=溢价（买贵了），负值=折价（买便宜了） |
