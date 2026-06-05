#!/usr/bin/env python3
"""全量 A 股日频数据采集器 —— 收集可用于 LLM 训练的全市场数据。

每个交易日收盘后运行一次，采集：
  - 5447 只个股快照 (Sina)
  - ETF 基金快照 (Sina + 东财)
  - 大盘指数 (Sina)
  - 涨跌家数 / 涨停跌停 (计算)
  - 行业板块表现 (从逐笔数据累计)
  - TOP 榜单

用法：
  python3 batch_collect.py                # 全量采集（含 ETF）
  python3 batch_collect.py --status       # 采集状态
  python3 batch_collect.py --update-list  # 刷新股票+ETF 清单
  python3 batch_collect.py --no-etf       # 仅采集股票，跳过 ETF

定时任务（交易日 15:30）：
  30 15 * * 1-5 cd /path && python3 batch_collect.py >> collect.log
"""
import sys
import os
import json
import re
import gzip
import time
import urllib.request
from datetime import datetime, date

BASE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "market_data")
META_DIR = os.path.join(BASE_DIR, "meta")
STOCK_LIST_FILE = os.path.join(META_DIR, "stocks_list.json")
ETF_LIST_FILE = os.path.join(META_DIR, "etf_list.json")
HISTORY_INDEX_FILE = os.path.join(META_DIR, "history_index.json")
INDUSTRY_MAP_FILE = os.path.join(META_DIR, "industry_map.json")

# ── 代码发现 ──────────────────────────────────

CODE_RANGES = [
    ("sh", 600000, 606000), ("sh", 688000, 690000),
    ("sz", 0, 5000), ("sz", 300000, 302000),
    ("sh", 830000, 834000), ("sh", 870000, 874000), ("sh", 920000, 921000),
]

# ETF 代码范围
ETF_CODE_RANGES = [
    ("sh", 510000, 520000),   # 上海 ETF: 510xxx-519xxx
    ("sh", 560000, 570000),   # 上海 ETF: 560xxx-569xxx
    ("sh", 580000, 590000),   # 上海 ETF: 580xxx-589xxx
    ("sh", 500000, 503000),   # 上海 LOF: 501xxx-502xxx
    ("sz", 150000, 160000),   # 深圳 ETF: 150xxx-159xxx
    ("sz", 160000, 170000),   # 深圳 LOF: 160xxx-169xxx
]


def discover_stocks():
    """通过新浪批量发现 A 股代码。"""
    print("首次发现 A 股代码...", file=sys.stderr)
    all_stocks = {}
    batch_size = 800
    total = sum(e - s for _, s, e in CODE_RANGES)
    batches = total // batch_size + 1
    bn = 0

    for market, start, end in CODE_RANGES:
        codes = list(range(start, end))
        for i in range(0, len(codes), batch_size):
            batch = codes[i:i + batch_size]
            symbols = ",".join(f"{market}{c:06d}" for c in batch)
            bn += 1

            try:
                req = urllib.request.Request(
                    f"http://hq.sinajs.cn/list={symbols}",
                    headers={"Referer": "https://finance.sina.com.cn", "User-Agent": "Mozilla/5.0"},
                )
                resp = urllib.request.urlopen(req, timeout=10)
                for line in resp.read().decode("gbk").strip().split(";"):
                    m = re.search(r'hq_str_(\w+)="(.*?)"', line)
                    if not m: continue
                    parts = m.group(2).split(",")
                    name = parts[0]
                    code = m.group(1)[2:]
                    if name and name != "?":
                        all_stocks[code] = {
                            "code": code, "name": name,
                            "market": "SH" if m.group(1).startswith("sh") else "SZ",
                        }
            except Exception:
                pass
            if bn % 5 == 0:
                print(f"  [{bn}/{batches}] {len(all_stocks)} 只", file=sys.stderr)
            time.sleep(0.8)

    stocks = sorted(all_stocks.values(), key=lambda x: x["code"])
    print(f"共 {len(stocks)} 只", file=sys.stderr)
    return stocks


def load_stock_list(force=False):
    os.makedirs(META_DIR, exist_ok=True)
    if os.path.exists(STOCK_LIST_FILE) and not force:
        age = time.time() - os.path.getmtime(STOCK_LIST_FILE)
        if age < 30 * 86400:
            return json.load(open(STOCK_LIST_FILE))
    stocks = discover_stocks()
    json.dump(stocks, open(STOCK_LIST_FILE, "w"), ensure_ascii=False)
    return stocks


# ── ETF 发现 ──────────────────────────────────

def discover_etfs():
    """通过新浪批量发现 A 股 ETF 代码。"""
    print("首次发现 A 股 ETF 代码...", file=sys.stderr)
    all_etfs = {}
    batch_size = 800
    total = sum(e - s for _, s, e in ETF_CODE_RANGES)
    batches = total // batch_size + 1
    bn = 0

    for market, start, end in ETF_CODE_RANGES:
        codes = list(range(start, end))
        for i in range(0, len(codes), batch_size):
            batch = codes[i:i + batch_size]
            symbols = ",".join(f"{market}{c:06d}" for c in batch)
            bn += 1

            try:
                req = urllib.request.Request(
                    f"http://hq.sinajs.cn/list={symbols}",
                    headers={"Referer": "https://finance.sina.com.cn", "User-Agent": "Mozilla/5.0"},
                )
                resp = urllib.request.urlopen(req, timeout=10)
                for line in resp.read().decode("gbk").strip().split(";"):
                    m = re.search(r'hq_str_(\w+)="(.*?)"', line)
                    if not m: continue
                    parts = m.group(2).split(",")
                    name = parts[0]
                    code = m.group(1)[2:]
                    if name and name != "?":
                        all_etfs[code] = {
                            "code": code, "name": name,
                            "market": "SH" if m.group(1).startswith("sh") else "SZ",
                            "type": "etf",
                        }
            except Exception:
                pass
            if bn % 5 == 0:
                print(f"  [{bn}/{batches}] {len(all_etfs)} 只 ETF", file=sys.stderr)
            time.sleep(0.8)

    etfs = sorted(all_etfs.values(), key=lambda x: x["code"])
    print(f"共 {len(etfs)} 只 ETF", file=sys.stderr)
    return etfs


def load_etf_list(force=False):
    """加载 ETF 清单，30 天缓存。"""
    os.makedirs(META_DIR, exist_ok=True)
    if os.path.exists(ETF_LIST_FILE) and not force:
        age = time.time() - os.path.getmtime(ETF_LIST_FILE)
        if age < 30 * 86400:
            return json.load(open(ETF_LIST_FILE))
    etfs = discover_etfs()
    json.dump(etfs, open(ETF_LIST_FILE, "w"), ensure_ascii=False)
    return etfs


# ── 行业映射（增量构建）────────────────────

def load_industry_map():
    """加载已有的 stock→industry 映射。"""
    if os.path.exists(INDUSTRY_MAP_FILE):
        return json.load(open(INDUSTRY_MAP_FILE))
    return {}


def save_industry_map(mapping):
    json.dump(mapping, open(INDUSTRY_MAP_FILE, "w"), ensure_ascii=False)


def get_stock_industry_batch(stocks):
    """通过东方财富 F10 公司概况接口，并行获取所有股票的行业分类。"""
    mapping = load_industry_map()
    unknown = [s for s in stocks if s["code"] not in mapping]
    if not unknown or len(unknown) == 0:
        return mapping

    print(f"补充行业映射: {len(unknown)} 只（并行获取）...", file=sys.stderr)

    # 构建市场前缀
    def _mkt_prefix(code):
        if code.startswith(("6", "9")):
            return "SH"
        return "SZ"

    results = {}
    total = len(unknown)
    done = 0

    def fetch_one(code):
        nonlocal done
        prefix = _mkt_prefix(code)
        url = f"https://emweb.securities.eastmoney.com/PC_HSF10/CompanySurvey/CompanySurveyAjax?code={prefix}{code}"
        req = urllib.request.Request(url, headers={
            "User-Agent": "Mozilla/5.0",
            "Referer": "https://emweb.securities.eastmoney.com/",
        })
        try:
            resp = urllib.request.urlopen(req, timeout=15)
            d = json.loads(resp.read().decode("utf-8"))
            jbzl = d.get("jbzl", {})
            industry = jbzl.get("sshy", "") or ""
            region = jbzl.get("qy", "") or ""
            if industry:
                results[code] = {"industry": industry, "region": region}
        except Exception:
            pass
        done += 1
        if done % 500 == 0:
            print(f"  进度: {done}/{total} (已获取 {len(results)} 只)", file=sys.stderr)

    # 并发获取（50 个线程）
    from concurrent.futures import ThreadPoolExecutor, as_completed
    with ThreadPoolExecutor(max_workers=50) as pool:
        futures = [pool.submit(fetch_one, s["code"]) for s in unknown]
        for f in as_completed(futures):
            pass  # fetch_one 内部处理了结果收集

    mapping.update(results)
    save_industry_map(mapping)
    print(f"  ✅ 共 {len(mapping)} 只股票有行业映射（成功 {len(results)}/{total}）",
          file=sys.stderr)
    return mapping


# ── 大盘指数 ──────────────────────────────────

INDEX_SYMBOLS = {
    "sh000001": "上证指数", "sz399001": "深证成指",
    "sz399006": "创业板指", "sh000688": "科创50", "sh000300": "沪深300",
    "sh000016": "上证50", "sh000905": "中证500",
}


def collect_indices():
    """从新浪获取大盘指数。"""
    symbols = ",".join(INDEX_SYMBOLS.keys())
    req = urllib.request.Request(
        f"http://hq.sinajs.cn/list={symbols}",
        headers={"Referer": "https://finance.sina.com.cn", "User-Agent": "Mozilla/5.0"},
    )
    resp = urllib.request.urlopen(req, timeout=10)
    text = resp.read().decode("gbk")

    indices = {}
    for line in text.strip().split(";"):
        m = re.search(r'hq_str_(\w+)="(.*?)"', line)
        if not m: continue
        parts = m.group(2).split(",")
        if len(parts) < 5: continue
        code = m.group(1)
        name = INDEX_SYMBOLS.get(code, parts[0])
        price = float(parts[3]) if parts[3] else 0
        prev = float(parts[2]) if parts[2] else 0
        change = round(price - prev, 2) if price and prev else 0
        pct = round(change / prev * 100, 2) if prev else 0
        indices[code] = {
            "name": name, "price": price, "change": change,
            "changePercent": pct, "open": float(parts[1]) if parts[1] else 0,
            "high": float(parts[4]) if parts[4] else 0,
            "low": float(parts[5]) if parts[5] else 0,
            "volume": float(parts[8]) if len(parts) > 8 and parts[8] else 0,
        }
    return indices


# ── 个股快照 ──────────────────────────────────

def collect_snapshot(stock_list):
    """采集全市场个股快照。"""
    print(f"采集个股 ({len(stock_list)} 只)...", file=sys.stderr)
    snapshot = {}
    batch_size = 800

    for bs in range(0, len(stock_list), batch_size):
        batch = stock_list[bs:bs + batch_size]
        symbols = ",".join(f"{s['market'].lower()}{s['code']}" for s in batch)
        if bs > 0:
            time.sleep(1.0)

        try:
            req = urllib.request.Request(
                f"http://hq.sinajs.cn/list={symbols}",
                headers={"Referer": "https://finance.sina.com.cn", "User-Agent": "Mozilla/5.0"},
            )
            resp = urllib.request.urlopen(req, timeout=15)
            parsed = 0
            for line in resp.read().decode("gbk").strip().split(";"):
                m = re.search(r'hq_str_(\w+)="(.*?)"', line)
                if not m: continue
                parts = m.group(2).split(",")
                if len(parts) < 30: continue
                name = parts[0]
                code = m.group(1)[2:]
                if not name: continue
                price = _f(parts, 3)
                prev = _f(parts, 2)
                snapshot[code] = {
                    "code": code, "name": name,
                    "price": price, "prevClose": prev,
                    "change": round(price - prev, 2) if price and prev else 0,
                    "open": _f(parts, 1), "high": _f(parts, 4), "low": _f(parts, 5),
                    "volume": int(_f(parts, 8)) if _f(parts, 8) else 0,
                    "amount": _f(parts, 9),
                }
                parsed += 1
            p = min(bs + batch_size, len(stock_list))
            active = sum(1 for s in snapshot.values() if s.get("price", 0) > 0)
            print(f"  [{p}/{len(stock_list)}] +{parsed} | 交易中 {active}", file=sys.stderr)
        except Exception as e:
            print(f"  ⚠️ 批次失败: {e}", file=sys.stderr)

    return snapshot


# ── ETF 快照 ──────────────────────────────────

def collect_etf_snapshot(etf_list):
    """采集全市场 ETF 快照（含基金净值信息）。"""
    print(f"采集 ETF ({len(etf_list)} 只)...", file=sys.stderr)
    snapshot = {}
    batch_size = 800

    for bs in range(0, len(etf_list), batch_size):
        batch = etf_list[bs:bs + batch_size]
        symbols = ",".join(f"{e['market'].lower()}{e['code']}" for e in batch)
        if bs > 0:
            time.sleep(1.0)

        try:
            req = urllib.request.Request(
                f"http://hq.sinajs.cn/list={symbols}",
                headers={"Referer": "https://finance.sina.com.cn", "User-Agent": "Mozilla/5.0"},
            )
            resp = urllib.request.urlopen(req, timeout=15)
            parsed = 0
            for line in resp.read().decode("gbk").strip().split(";"):
                m = re.search(r'hq_str_(\w+)="(.*?)"', line)
                if not m: continue
                parts = m.group(2).split(",")
                if len(parts) < 30: continue
                name = parts[0]
                code = m.group(1)[2:]
                if not name: continue
                price = _f(parts, 3)
                prev = _f(parts, 2)
                snapshot[code] = {
                    "code": code, "name": name,
                    "price": price, "prevClose": prev,
                    "change": round(price - prev, 2) if price and prev else 0,
                    "open": _f(parts, 1), "high": _f(parts, 4), "low": _f(parts, 5),
                    "volume": int(_f(parts, 8)) if _f(parts, 8) else 0,
                    "amount": _f(parts, 9),
                    "type": "etf",
                }
                parsed += 1
            p = min(bs + batch_size, len(etf_list))
            active = sum(1 for s in snapshot.values() if s.get("price", 0) > 0)
            print(f"  [{p}/{len(etf_list)}] +{parsed} | 交易中 {active}", file=sys.stderr)
        except Exception as e:
            print(f"  ⚠️ ETF 批次失败: {e}", file=sys.stderr)

    # 补充净值信息（并行获取，限流）
    trading_etfs = {code: s for code, s in snapshot.items() if s.get("price", 0) > 0}
    if trading_etfs:
        _supplement_etf_nav(trading_etfs)

    return snapshot


def _supplement_etf_nav(etf_snapshot):
    """为交易中的 ETF 补充净值数据（从东方财富基金估值接口获取）。"""
    from concurrent.futures import ThreadPoolExecutor, as_completed

    print(f"  补充净值信息 ({len(etf_snapshot)} 只)...", file=sys.stderr)
    done = 0
    total = len(etf_snapshot)

    def fetch_nav(code):
        nonlocal done
        url = f"https://fundgz.1234567.com.cn/js/{code}.js"
        req = urllib.request.Request(url, headers={
            "User-Agent": "Mozilla/5.0",
            "Referer": "https://fund.eastmoney.com/",
        })
        try:
            resp = urllib.request.urlopen(req, timeout=8)
            text = resp.read().decode("utf-8")
            m = re.search(r'jsonpgz\((.*?)\)', text)
            if m:
                d = json.loads(m.group(1))
                dwjz = float(d.get("dwjz", 0) or 0)
                gsz = float(d.get("gsz", 0) or 0)
                gszzl = float(d.get("gszzl", 0) or 0)
                if dwjz:
                    etf_snapshot[code]["nav"] = round(dwjz, 4)
                if gsz:
                    etf_snapshot[code]["navRealtime"] = round(gsz, 4)
                if gszzl:
                    etf_snapshot[code]["navChangePercent"] = round(gszzl, 2)
                # 计算溢价率：优先用盘中实时估值，盘后用确认净值
                nav_for_premium = gsz or dwjz
                price = etf_snapshot[code].get("price", 0)
                if nav_for_premium and price and nav_for_premium > 0:
                    etf_snapshot[code]["premiumRate"] = round((price - nav_for_premium) / nav_for_premium * 100, 2)
        except Exception:
            pass
        done += 1
        if done % 200 == 0:
            print(f"    净值进度: {done}/{total}", file=sys.stderr)

    with ThreadPoolExecutor(max_workers=20) as pool:
        futures = [pool.submit(fetch_nav, code) for code in etf_snapshot]
        for f in as_completed(futures):
            pass

    nav_count = sum(1 for s in etf_snapshot.values() if "nav" in s)
    print(f"  净值: {nav_count}/{total} 只", file=sys.stderr)


def _f(parts, idx):
    try: return float(parts[idx]) if idx < len(parts) and parts[idx] else 0.0
    except: return 0.0


# ── 统计分析 ──────────────────────────────────

def compute_market_stats(snapshot, indices):
    """计算全市场统计指标。"""
    trading = [s for s in snapshot.values() if s.get("price", 0) > 0]
    total = len(trading)

    up = [s for s in trading if s["change"] > 0]
    down = [s for s in trading if s["change"] < 0]
    flat = [s for s in trading if s["change"] == 0]

    # 计算涨跌幅
    for s in trading:
        prev = s.get("prevClose", 1)
        s["pctChange"] = round((s["price"] - prev) / prev * 100, 2) if prev else 0
        # 判断所属板块（用于涨跌停阈值）
        code = s.get("code", "")
        if code.startswith("688") or code.startswith("300"):
            s["board"] = "star"      # 科创/创业板 ±20%
        elif code.startswith("8"):
            s["board"] = "bj"        # 北交所 ±30%
        else:
            s["board"] = "main"      # 主板 ±10%

    def _limit(s):
        """判断是否涨停/跌停（考虑板块差异）。"""
        pct = abs(s["pctChange"])
        b = s.get("board", "main")
        threshold = 9.4 if b == "main" else (19.4 if b == "star" else 29.4)
        return pct >= threshold

    limit_up = [s for s in trading if _limit(s) and s["pctChange"] > 0]
    limit_down = [s for s in trading if _limit(s) and s["pctChange"] < 0]

    # 涨跌分布
    dist = {"<-5%": 0, "-5~-3%": 0, "-3~-1%": 0, "-1~0%": 0,
            "0~1%": 0, "1~3%": 0, "3~5%": 0, ">5%": 0}
    for s in trading:
        p = s["pctChange"]
        if p <= -5: dist["<-5%"] += 1
        elif p <= -3: dist["-5~-3%"] += 1
        elif p <= -1: dist["-3~-1%"] += 1
        elif p < 0: dist["-1~0%"] += 1
        elif p < 1: dist["0~1%"] += 1
        elif p < 3: dist["1~3%"] += 1
        elif p < 5: dist["3~5%"] += 1
        else: dist[">5%"] += 1

    avg_change = round(sum(s["pctChange"] for s in trading) / len(trading), 2) if trading else 0
    median_vol = sorted(s.get("amount", 0) for s in trading)[len(trading)//2] if trading else 0

    stats = {
        "totalStocks": len(snapshot),
        "tradingStocks": total,
        "upCount": len(up),
        "downCount": len(down),
        "flatCount": len(flat),
        "upDownRatio": round(len(up) / len(down), 2) if down else 0,
        "limitUpCount": len(limit_up),
        "limitDownCount": len(limit_down),
        "avgChange": avg_change,
        "medianVolume": round(median_vol, 2),
        "distribution": dist,
    }
    return stats


def build_top_lists(snapshot):
    """构建 TOP 榜单。"""
    stocks = [s for s in snapshot.values() if s.get("price", 0) > 0]
    for s in stocks:
        prev = s.get("prevClose", 1)
        s["pctChange"] = round((s["price"] - prev) / prev * 100, 2) if prev else 0

    def pick(items, sort_key, keys, n=30, reverse=True):
        return [{k: s[k] for k in keys} for s in sorted(items, key=lambda x: x.get(sort_key, 0), reverse=reverse)[:n]]

    return {
        "topGainers": pick(stocks, "pctChange", ["code", "name", "price", "pctChange", "volume"]),
        "topLosers": pick(stocks, "pctChange", ["code", "name", "price", "pctChange", "volume"], reverse=False),
        "topVolume": pick(stocks, "amount", ["code", "name", "price", "amount", "pctChange"]),
    }


# ── ETF 统计分析 ─────────────────────────────

def compute_etf_stats(etf_snapshot):
    """计算 ETF 市场统计指标。"""
    if not etf_snapshot:
        return None

    trading = [s for s in etf_snapshot.values() if s.get("price", 0) > 0]
    total = len(trading)
    if total == 0:
        return {"totalEtfs": len(etf_snapshot), "tradingEtfs": 0}

    up = [s for s in trading if s["change"] > 0]
    down = [s for s in trading if s["change"] < 0]
    flat = [s for s in trading if s["change"] == 0]

    # 计算涨跌幅
    for s in trading:
        prev = s.get("prevClose", 1)
        s["pctChange"] = round((s["price"] - prev) / prev * 100, 2) if prev else 0

    avg_change = round(sum(s["pctChange"] for s in trading) / total, 2)

    # 净值/溢价率统计
    with_nav = [s for s in trading if "nav" in s]
    nav_count = len(with_nav)
    premium_rates = [s["premiumRate"] for s in with_nav if "premiumRate" in s]
    median_premium = sorted(premium_rates)[len(premium_rates)//2] if premium_rates else None

    # 溢价 ETF（溢价率 > 0.5% 的值得注意）
    premium_etfs = [s for s in with_nav if s.get("premiumRate", 0) > 0.5]
    discount_etfs = [s for s in with_nav if s.get("premiumRate", 0) < -0.5]

    # ETF TOP 榜单
    def pick(items, sort_key, keys, n=20, reverse=True):
        return [{k: s[k] for k in keys if k in s} for s in sorted(items, key=lambda x: x.get(sort_key, 0), reverse=reverse)[:n]]

    top_etfs = {
        "topGainers": pick(trading, "pctChange", ["code", "name", "price", "pctChange", "volume"]),
        "topLosers": pick(trading, "pctChange", ["code", "name", "price", "pctChange", "volume"], reverse=False),
        "topVolume": pick(trading, "amount", ["code", "name", "price", "amount", "pctChange"]),
        "topPremium": pick(with_nav, "premiumRate", ["code", "name", "price", "nav", "premiumRate"]) if with_nav else [],
        "topDiscount": pick(with_nav, "premiumRate", ["code", "name", "price", "nav", "premiumRate"], reverse=False) if with_nav else [],
    }

    stats = {
        "totalEtfs": len(etf_snapshot),
        "tradingEtfs": total,
        "upCount": len(up),
        "downCount": len(down),
        "flatCount": len(flat),
        "upDownRatio": round(len(up) / len(down), 2) if down else 0,
        "avgChange": avg_change,
        "navCount": nav_count,
        "medianPremiumRate": round(median_premium, 2) if median_premium is not None else None,
        "premiumEtfsCount": len(premium_etfs),
        "discountEtfsCount": len(discount_etfs),
        "topEtfs": top_etfs,
    }
    return stats


# ── 行业板块（从映射推导）───────────────────

def compute_sector_performance(snapshot, industry_map):
    """用 stock→industry 映射计算行业板块涨跌。"""
    # 按行业分组
    sector_stocks = {}
    for code, s in snapshot.items():
        ind_info = industry_map.get(code, {})
        industry = ind_info.get("industry", "") if isinstance(ind_info, dict) else ""
        if not industry:
            continue
        if industry not in sector_stocks:
            sector_stocks[industry] = []
        sector_stocks[industry].append(s)

    sectors = []
    for ind_name, stocks in sector_stocks.items():
        trading = [s for s in stocks if s.get("price", 0) > 0]
        if len(trading) < 3:  # 少于 3 只的不算板块
            continue
        avg_pct = round(sum(
            (s["price"] - s["prevClose"]) / s["prevClose"] * 100
            for s in trading if s["prevClose"]
        ) / len(trading), 2)
        sectors.append({
            "name": ind_name,
            "stockCount": len(trading),
            "avgChangePercent": avg_pct,
        })

    sectors.sort(key=lambda x: x["avgChangePercent"], reverse=True)
    return {
        "topSectors": sectors[:20],
        "bottomSectors": sectors[-20:] if len(sectors) >= 20 else [],
        "totalSectors": len(sectors),
    }


# ── 存储 ──────────────────────────────────────

def save_daily(snapshot, indices, stats, sectors_info, tops, etf_snapshot=None, target_date=None):
    """保存每日全量数据。"""
    if target_date is None:
        target_date = date.today().strftime("%Y%m%d")
    day_dir = os.path.join(BASE_DIR, target_date)
    os.makedirs(day_dir, exist_ok=True)

    # 精简快照（压缩）
    snap_compact = {}
    for code, s in snapshot.items():
        snap_compact[code] = {k: s[k] for k in ["name", "price", "change", "prevClose", "volume", "amount"]}

    with gzip.open(os.path.join(day_dir, "snapshot.json.gz"), "wt", encoding="utf-8") as f:
        json.dump(snap_compact, f, ensure_ascii=False)

    # ETF 快照（含净值）
    if etf_snapshot:
        etf_compact = {}
        for code, s in etf_snapshot.items():
            etf_compact[code] = {k: s[k] for k in ["name", "price", "change", "prevClose", "volume", "amount", "nav", "premiumRate"] if k in s}
        with gzip.open(os.path.join(day_dir, "etf_snapshot.json.gz"), "wt", encoding="utf-8") as f:
            json.dump(etf_compact, f, ensure_ascii=False)

    # 各维度数据
    json.dump(indices, open(os.path.join(day_dir, "indices.json"), "w"), ensure_ascii=False)
    json.dump(stats, open(os.path.join(day_dir, "market_stats.json"), "w"), ensure_ascii=False)
    json.dump(sectors_info, open(os.path.join(day_dir, "sectors.json"), "w"), ensure_ascii=False)
    json.dump(tops, open(os.path.join(day_dir, "top_stocks.json"), "w"), ensure_ascii=False)

    # ETF 统计 + TOP ETF
    etf_stats = compute_etf_stats(etf_snapshot) if etf_snapshot else None
    if etf_stats:
        json.dump(etf_stats, open(os.path.join(day_dir, "etf_stats.json"), "w"), ensure_ascii=False)

    # 汇总训练样本（合并为一个完整记录）
    training_sample = {
        "date": target_date,
        "indices": indices,
        "marketStats": stats,
        "sectors": sectors_info,
        "topStocks": {
            "topGainers": tops["topGainers"][:10],
            "topLosers": tops["topLosers"][:10],
            "topVolume": tops["topVolume"][:10],
        },
    }
    if etf_stats:
        training_sample["etfStats"] = etf_stats
    json.dump(training_sample, open(os.path.join(day_dir, "training.json"), "w"), ensure_ascii=False)

    # 历史索引
    os.makedirs(META_DIR, exist_ok=True)
    index = json.load(open(HISTORY_INDEX_FILE)) if os.path.exists(HISTORY_INDEX_FILE) else []
    if target_date not in index:
        index.append(target_date)
        index.sort()
    json.dump(index, open(HISTORY_INDEX_FILE, "w"))

    # 打印摘要
    snap_size = os.path.getsize(os.path.join(day_dir, "snapshot.json.gz"))
    print(f"✅ {target_date}", file=sys.stderr)
    print(f"   个股: {len(snapshot)} (交易中 {stats['tradingStocks']}) | 快照 {snap_size/1024:.0f}KB", file=sys.stderr)
    print(f"   涨跌: ↑{stats['upCount']} ↓{stats['downCount']} (比 {stats['upDownRatio']}) | 涨停{stats['limitUpCount']} 跌停{stats['limitDownCount']}", file=sys.stderr)
    if etf_snapshot and etf_stats:
        etf_size = os.path.getsize(os.path.join(day_dir, "etf_snapshot.json.gz")) if os.path.exists(os.path.join(day_dir, "etf_snapshot.json.gz")) else 0
        print(f"   ETF: {len(etf_snapshot)} (交易中 {etf_stats['tradingEtfs']}) | 快照 {etf_size/1024:.0f}KB", file=sys.stderr)
        nav_count = etf_stats.get('navCount', 0)
        if nav_count:
            print(f"   ETF 净值: {nav_count} 只 | 溢价率中位数 {etf_stats.get('medianPremiumRate', '?')}%", file=sys.stderr)
    if indices:
        sh = indices.get("sh000001", {})
        print(f"   上证: {sh.get('price','?')} ({sh.get('changePercent','?'):+.2f}%)", file=sys.stderr)
    if sectors_info:
        print(f"   板块: {sectors_info['totalSectors']} 个 | 最强 {sectors_info['topSectors'][0]['name'] if sectors_info['topSectors'] else '?'}", file=sys.stderr)


# ── 状态 ──────────────────────────────────────

def show_status():
    if not os.path.exists(HISTORY_INDEX_FILE):
        print("暂无数据（首次运行后可用）")
        return
    dates = json.load(open(HISTORY_INDEX_FILE))
    print(f"📊 全量 A 股 + ETF 数据采集")
    print(f"   天数: {len(dates)} | {dates[0]} ~ {dates[-1]}")
    total_size = 0
    for d in dates:
        f = os.path.join(BASE_DIR, d, "snapshot.json.gz")
        if os.path.exists(f):
            total_size += os.path.getsize(f)
        ef = os.path.join(BASE_DIR, d, "etf_snapshot.json.gz")
        if os.path.exists(ef):
            total_size += os.path.getsize(ef)
        tf = os.path.join(BASE_DIR, d, "training.json")
        if os.path.exists(tf):
            s = json.load(open(tf))
            stats = s.get("marketStats", {})
            idxs = s.get("indices", {})
            etf_s = s.get("etfStats", {})
            sh = idxs.get("sh000001", {})
            line = (f"   {d}: 交易{stats.get('tradingStocks','?')} "
                    f"↑{stats.get('upCount','?')}↓{stats.get('downCount','?')} "
                    f"上证{sh.get('price','?'):} ({sh.get('changePercent','?'):+.2f}%)")
            if etf_s:
                line += f" | ETF {etf_s.get('tradingEtfs','?')}只"
            print(line)
    print(f"\n   总存储: {total_size/1024/1024:.2f} MB")


# ── 入口 ──────────────────────────────────────

if __name__ == "__main__":
    if "--status" in sys.argv:
        show_status()
        sys.exit(0)
    force_update = "--update-list" in sys.argv
    no_etf = "--no-etf" in sys.argv

    stocks = load_stock_list(force=force_update)
    print(f"股票: {len(stocks)} 只", file=sys.stderr)

    # ETF 清单
    etf_list = []
    if not no_etf:
        etf_list = load_etf_list(force=force_update)
        print(f"ETF: {len(etf_list)} 只", file=sys.stderr)

    # 行业映射（增量，从东方财富补充）
    industry_map = get_stock_industry_batch(stocks)
    print(f"行业映射: {len(industry_map)} 只", file=sys.stderr)

    # 大盘指数
    indices = collect_indices()
    index_names = [v["name"] for v in indices.values()]
    print(f"指数: {', '.join(index_names)}", file=sys.stderr)

    # 个股快照
    snapshot = collect_snapshot(stocks)
    if not snapshot:
        print("❌ 未采集到数据", file=sys.stderr)
        sys.exit(1)

    # ETF 快照
    etf_snapshot = None
    if etf_list:
        etf_snapshot = collect_etf_snapshot(etf_list)

    # 计算
    stats = compute_market_stats(snapshot, indices)
    tops = build_top_lists(snapshot)
    sectors_info = compute_sector_performance(snapshot, industry_map)

    # 保存
    save_daily(snapshot, indices, stats, sectors_info, tops, etf_snapshot=etf_snapshot)

    # 行业映射持续累积
    if industry_map:
        save_industry_map(industry_map)
