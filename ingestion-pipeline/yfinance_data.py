#!/usr/bin/env python3
"""
金融数据获取脚本 —— 通过 Sina / 东方财富 / yfinance 获取股票行情和技术指标。
供 Java 后端通过 ProcessBuilder 调用，输出 JSON 到 stdout。

用法：
  python3 yfinance_data.py quote <symbol>
  python3 yfinance_data.py history <symbol> <period> <interval>
  python3 yfinance_data.py full <symbol>
"""
import sys
import json
import math
import re
import urllib.request
import urllib.parse

# ═══════════════════════════════════════════════════════════════
#  速率限制 & 缓存（避免被 API 限流）
# ═══════════════════════════════════════════════════════════════

import time as _time
import functools as _functools

class RateLimiter:
    """令牌桶限流器。"""
    def __init__(self, calls_per_minute=60):
        self.min_interval = 60.0 / calls_per_minute
        self.last_call = 0.0

    def wait(self):
        now = _time.time()
        elapsed = now - self.last_call
        if elapsed < self.min_interval:
            _time.sleep(self.min_interval - elapsed)
        self.last_call = _time.time()


class TTLCache:
    """带 TTL 的简单缓存。"""
    def __init__(self, ttl_seconds=30):
        self.cache = {}
        self.ttl = ttl_seconds

    def get(self, key):
        now = _time.time()
        if key in self.cache and now - self.cache[key]["time"] < self.ttl:
            return self.cache[key]["data"]
        return None

    def set(self, key, data):
        self.cache[key] = {"data": data, "time": _time.time()}

    def clear(self):
        self.cache.clear()


# 全局限流器：东方财富 30次/分，新浪 60次/分，yfinance 10次/分
_em_limiter = RateLimiter(30)
_sina_limiter = RateLimiter(60)
_yf_limiter = RateLimiter(10)
_cache = TTLCache(ttl_seconds=20)  # 行情 20 秒缓存


def _rate_limited_request(url, headers, limiter, cache_key=None, timeout=10):
    """带限流和缓存的 HTTP 请求。"""
    if cache_key:
        cached = _cache.get(cache_key)
        if cached is not None:
            return cached

    limiter.wait()
    req = urllib.request.Request(url, headers=headers)
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
        text = resp.read().decode("utf-8")
        if cache_key:
            _cache.set(cache_key, text)
        return text
    except urllib.error.HTTPError as e:
        if e.code == 429:
            _time.sleep(5)  # 被限流，等 5 秒重试
            limiter.wait()
            resp = urllib.request.urlopen(req, timeout=timeout)
            text = resp.read().decode("utf-8")
            return text
        raise


# ═══════════════════════════════════════════════════════════════
#  数据源：Sina Finance
# ═══════════════════════════════════════════════════════════════

def sina_quote(symbol):
    """从新浪财经获取实时报价。"""
    # 符号映射
    sina_symbol = _to_sina_symbol(symbol)
    if not sina_symbol:
        return None

    url = f"http://hq.sinajs.cn/list={sina_symbol}"
    req = urllib.request.Request(url, headers={
        "Referer": "https://finance.sina.com.cn",
        "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
    })
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        text = resp.read().decode("gbk")
    except Exception:
        return None

    # 解析格式: var hq_str_sh600519="name,open,prevClose,current,high,low,..."
    # 或: var hq_str_gb_aapl="name,price,change,datetime,..."
    match = re.search(r'"(.*?)"', text)
    if not match:
        return None

    parts = match.group(1).split(",")
    if len(parts) < 6:
        return None

    # 判断是否为美股格式（以 gb_ 开头）
    is_us = sina_symbol.startswith("gb_")

    if is_us:
        # 美股格式: name,price,change,datetime,chg_pct,open,high,low,prev_close,...
        return {
            "symbol": symbol.upper(),
            "name": parts[0],
            "price": _f(parts, 1),
            "change": _f(parts, 2),
            "changePercent": round((_f(parts, 2) / _f(parts, 5) * 100), 2) if _f(parts, 5) else _f(parts, 4),
            "prevClose": _f(parts, 8),
            "open": _f(parts, 5),
            "dayHigh": _f(parts, 6),
            "dayLow": _f(parts, 7),
            "volume": int(_f(parts, 10)) if len(parts) > 10 else 0,
            "marketCap": int(_f(parts, 14)) if len(parts) > 14 else 0,
            "currency": "USD",
        }
    else:
        # A 股格式: name,open,prevClose,current,high,low,...
        current = _f(parts, 3)
        prev_close = _f(parts, 2)
        change = round(current - prev_close, 2) if current and prev_close else 0
        change_pct = round((change / prev_close) * 100, 2) if prev_close and prev_close != 0 else 0
        return {
            "symbol": symbol.upper(),
            "name": parts[0],
            "price": current,
            "change": change,
            "changePercent": change_pct,
            "prevClose": prev_close,
            "open": _f(parts, 1),
            "dayHigh": _f(parts, 4),
            "dayLow": _f(parts, 5),
            "volume": int(_f(parts, 8)) if _f(parts, 8) else 0,
            "currency": "CNY",
        }


def _to_sina_symbol(symbol):
    """转换用户输入的符号为新浪格式。"""
    s = symbol.upper().strip()
    # 数字代码 → A股
    if re.match(r"^\d{6}$", s):
        if s.startswith(("6", "9")):
            return f"sh{s}"
        return f"sz{s}"
    # 已知美股/港股
    if s == "AAPL":
        return "gb_aapl"
    if s == "TSLA":
        return "gb_tsla"
    if s == "MSFT":
        return "gb_msft"
    if s == "GOOGL" or s == "GOOG":
        return "gb_goog"
    if s == "AMD":
        return "gb_amd"
    if s == "AMZN":
        return "gb_amzn"
    if s == "BABA":
        return "baba"  # NYSE
    if s == "0700.HK":
        return "hk00700"
    return None


# ═══════════════════════════════════════════════════════════════
#  数据源：东方财富（A 股，可交叉验证）
# ═══════════════════════════════════════════════════════════════

def eastmoney_quote(symbol):
    """从东方财富获取 A 股实时报价（独立于 Sina 的另一个数据源）。"""
    if not re.match(r"^\d{6}$", symbol):
        return None

    market = "1" if symbol.startswith(("6", "9")) else "0"
    url = f"https://push2.eastmoney.com/api/qt/stock/get?secid={market}.{symbol}&fields=f43,f44,f45,f46,f47,f48,f50,f57,f58,f170,f100,f703"

    _em_limiter.wait()
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0",
        "Referer": "https://quote.eastmoney.com",
    })
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        data = json.loads(resp.read().decode("utf-8")).get("data", {})
        if not data:
            return None

        name = data.get("f58", "")
        price = (data.get("f43", 0) or 0) / 100.0  # 东方财富单位是"分"
        high = (data.get("f44", 0) or 0) / 100.0
        low = (data.get("f45", 0) or 0) / 100.0
        open_p = (data.get("f46", 0) or 0) / 100.0
        volume = data.get("f47", 0) or 0  # 手
        amount = data.get("f48", 0) or 0
        change_pct = (data.get("f170", 0) or 0) / 100  # 百分比

        # 估算昨收和涨跌额
        prev_close = round(price / (1 + change_pct / 100), 2) if change_pct != -100 else 0
        change = round(price - prev_close, 2)

        return {
            "symbol": symbol.upper(),
            "name": name,
            "price": price,
            "change": change,
            "changePercent": round(change_pct, 2),
            "prevClose": prev_close,
            "open": open_p,
            "dayHigh": high,
            "dayLow": low,
            "volume": int(volume * 100) if volume else 0,  # 手 → 股
            "amount": amount,
            "currency": "CNY",
            "source": "eastmoney",
        }
    except Exception:
        return None


def _f(parts, idx):
    """安全获取列表元素并转为 float。"""
    try:
        return float(parts[idx]) if idx < len(parts) and parts[idx] else 0.0
    except (ValueError, IndexError):
        return 0.0


# ═══════════════════════════════════════════════════════════════
#  数据源：yfinance（美股 + 历史 K 线，带缓存减轻限流）
# ═══════════════════════════════════════════════════════════════

_cache = {}

def _yf_get(symbol):
    """缓存版 yfinance 获取。"""
    now = int(__import__("time").time())
    if symbol in _cache and now - _cache[symbol]["time"] < 30:
        return _cache[symbol]["data"]
    try:
        import yfinance as yf
        tk = yf.Ticker(symbol)
        info = tk.info or {}
        _cache[symbol] = {"data": info, "time": now}
        return info
    except Exception as e:
        return {"error": str(e)}


def _yf_history(symbol, period="1mo", interval="1d"):
    """缓存版 yfinance 历史数据。"""
    cache_key = f"{symbol}_{period}_{interval}"
    now = int(__import__("time").time())
    if cache_key in _cache and now - _cache[cache_key]["time"] < 120:
        return _cache[cache_key]["data"]

    try:
        import yfinance as yf
        tk = yf.Ticker(symbol)
        df = tk.history(period=period, interval=interval, auto_adjust=True)
        if df.empty:
            return []
        bars = []
        for idx, row in df.iterrows():
            ts = int(idx.timestamp()) if hasattr(idx, "timestamp") else 0
            bars.append({
                "timestamp": ts,
                "date": idx.strftime("%Y-%m-%d"),
                "open": round(float(row.get("Open", 0)), 2),
                "high": round(float(row.get("High", 0)), 2),
                "low": round(float(row.get("Low", 0)), 2),
                "close": round(float(row.get("Close", 0)), 2),
                "volume": int(row.get("Volume", 0)),
            })
        _cache[cache_key] = {"data": bars, "time": now}
        return bars
    except Exception as e:
        return []


# ═══════════════════════════════════════════════════════════════
#  公开接口
# ═══════════════════════════════════════════════════════════════

def get_quote(symbol):
    """获取报价：A股优先东方财富→Sina，美股优先 Sina→yfinance。"""
    # A 股：优先东方财富（更稳定），Sina 备选
    if re.match(r"^\d{6}$", symbol):
        q = eastmoney_quote(symbol)
        if q and q.get("price"):
            return q
        # Sina 备选
        q = sina_quote(symbol)
        if q and q.get("price"):
            q["source"] = "sina"
            return q

    # 美股 / 港股：优先 Sina
    q = sina_quote(symbol)
    if q and q.get("price"):
        return q

    # yfinance fallback
    try:
        import yfinance as yf
        tk = yf.Ticker(symbol)
        info = tk.info or {}
        return {
            "symbol": info.get("symbol", symbol),
            "name": info.get("shortName") or info.get("longName") or "",
            "price": info.get("regularMarketPrice") or info.get("currentPrice") or 0,
            "change": info.get("regularMarketChange") or 0,
            "changePercent": info.get("regularMarketChangePercent") or 0,
            "prevClose": info.get("regularMarketPreviousClose") or info.get("previousClose") or 0,
            "open": info.get("regularMarketOpen") or info.get("open") or 0,
            "dayHigh": info.get("regularMarketDayHigh") or info.get("dayHigh") or 0,
            "dayLow": info.get("regularMarketDayLow") or info.get("dayLow") or 0,
            "volume": info.get("regularMarketVolume") or info.get("volume") or 0,
            "marketCap": info.get("marketCap") or 0,
            "currency": info.get("currency") or "USD",
        }
    except Exception as e:
        return {"error": str(e), "symbol": symbol}


def _eastmoney_history(symbol, limit=60):
    """从东方财富获取 A 股历史 K 线（更稳定的数据源）。"""
    if not re.match(r"^\d{6}$", symbol):
        return []
    market = "1" if symbol.startswith(("6", "9")) else "0"
    url = f"https://push2his.eastmoney.com/api/qt/stock/kline/get?secid={market}.{symbol}&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57&klt=101&fqt=1&end=20260603&lmt={limit}"
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0",
        "Referer": "https://quote.eastmoney.com",
    })
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        data = json.loads(resp.read().decode("utf-8")).get("data", {})
        klines = data.get("klines", [])
        bars = []
        for k in klines:
            parts = k.split(",")
            if len(parts) < 7:
                continue
            bars.append({
                "date": parts[0],
                "open": float(parts[1]),
                "close": float(parts[2]),
                "high": float(parts[3]),
                "low": float(parts[4]),
                "volume": int(float(parts[5]) * 100),  # 手 → 股
            })
        return bars
    except Exception:
        return []


def _sina_history(symbol, datalen=60):
    """从新浪获取 A 股历史 K 线。"""
    sina_symbol = _to_sina_symbol(symbol)
    if not sina_symbol:
        return []

    url = f"http://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData?symbol={sina_symbol}&scale=240&ma=no&datalen={datalen}"
    req = urllib.request.Request(url, headers={
        "Referer": "https://finance.sina.com.cn",
        "User-Agent": "Mozilla/5.0",
    })
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        text = resp.read().decode("utf-8")
        rows = json.loads(text)
        bars = []
        for r in rows:
            bars.append({
                "date": r.get("day", ""),
                "open": float(r.get("open", 0)),
                "high": float(r.get("high", 0)),
                "low": float(r.get("low", 0)),
                "close": float(r.get("close", 0)),
                "volume": int(float(r.get("volume", 0))),
            })
        return bars
    except Exception:
        return []


def get_history(symbol, period="1mo", interval="1d"):
    """获取历史 K 线：A 股用东方财富→Sina，美股用 yfinance。"""
    # A 股：优先东方财富，Sina 备选
    if re.match(r"^\d{6}$", symbol):
        limit = {"1mo": 20, "3mo": 60, "6mo": 120, "1y": 250}.get(period, 60)
        bars = _eastmoney_history(symbol, limit)
        if bars:
            return bars
        datalen = limit
        bars = _sina_history(symbol, datalen)
        if bars:
            return bars

    # 美股：yfinance
    return _yf_history(symbol, period, interval)


# ═══════════════════════════════════════════════════════════════
#  数据源：东财数据中心（A 股 F10 基本面）
# ═══════════════════════════════════════════════════════════════

def get_fundamentals(symbol):
    """获取 A 股财务基本面数据（东财数据中心）。"""
    if not re.match(r"^\d{6}$", symbol):
        return {}

    suffix = ".SH" if symbol.startswith(("6", "9")) else ".SZ"
    secucode = f"{symbol}{suffix}"

    url = (
        f"https://datacenter-web.eastmoney.com/api/data/v1/get"
        f"?reportName=RPT_F10_FINANCE_MAINFINADATA"
        f"&columns=ALL&filter=(SECUCODE=%22{secucode}%22)"
        f"&pageNumber=1&pageSize=4&sortTypes=-1&sortColumns=REPORT_DATE"
    )
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0",
        "Referer": "https://emweb.securities.eastmoney.com/",
    })
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        result = json.loads(resp.read().decode("utf-8"))
        if not result.get("success"):
            return {}

        rows = result.get("result", {}).get("data", [])
        if not rows:
            return {}

        r = rows[0]  # 最新一期

        # 取最近两期用于对比
        prev = rows[1] if len(rows) > 1 else {}

        # 关键财务数据
        eps = float(r.get("EPSJB", 0) or 0)
        bps = float(r.get("BPS", 0) or 0)
        revenue = float(r.get("TOTALOPERATEREVE", 0) or 0)
        profit = float(r.get("PARENTNETPROFIT", 0) or 0)
        gross_margin = float(r.get("XSMLL", 0) or 0)
        net_margin = float(r.get("XSJLL", 0) or 0)
        roe = float(r.get("ROEJQ", 0) or 0)
        total_shares = float(r.get("TOTAL_SHARE", 0) or 0)
        total_assets = float(r.get("TOTAL_ASSETS_PK", 0) or 0)
        total_equity = float(r.get("TOTAL_EQUITY_PK", 0) or 0)
        liability_ratio = float(r.get("ZCFZL", 0) or 0)
        report_date = r.get("REPORT_DATE", "")
        report_type = r.get("REPORT_DATE_NAME", "")

        # 同环比
        revenue_yoy = float(r.get("DJD_TOI_YOY", 0) or 0)
        profit_yoy = float(r.get("DJD_DPNP_YOY", 0) or 0)

        # 简化报告期
        period = report_type if report_type else (report_date[:7] if report_date else "")

        return {
            "reportPeriod": period,
            "eps": round(eps, 2),
            "bps": round(bps, 2),
            "revenue": round(revenue, 2),
            "revenueYoy": round(revenue_yoy, 2),
            "netProfit": round(profit, 2),
            "netProfitYoy": round(profit_yoy, 2),
            "grossMargin": round(gross_margin, 2),
            "netMargin": round(net_margin, 2),
            "roe": round(roe, 2),
            "totalShares": int(total_shares),
            "totalAssets": round(total_assets, 2),
            "netAssets": round(total_equity, 2),
            "liabilityRatio": round(liability_ratio, 2),
            "source": "eastmoney_f10",
        }
    except Exception as e:
        return {"error": str(e)}


def get_full(symbol):
    """全量分析：报价 + 技术指标 + 基本面（A 股）。"""
    quote = get_quote(symbol)
    if "error" in quote:
        return quote

    # 基本面（A 股）
    fundamentals = get_fundamentals(symbol)

    # 多周期 K 线
    d1m = get_history(symbol, "1mo", "1d")
    d3m = get_history(symbol, "3mo", "1d")
    d1y = get_history(symbol, "1y", "1d")

    p1m = [b["close"] for b in d1m]
    p3m = [b["close"] for b in d3m]
    p1y = [b["close"] for b in d1y]

    def _sma(p, n):
        return round(sum(p[-n:]) / n, 2) if len(p) >= n else (p[-1] if p else 0)

    def _rsi(p, n=14):
        if len(p) < n + 1:
            return 50
        gains = losses = 0
        for i in range(-n, 0):
            d = p[i] - p[i - 1]
            if d > 0:
                gains += d
            else:
                losses -= d
        gains /= n
        losses /= n
        return 100 if losses == 0 else round(100 - 100 / (1 + gains / losses), 2)

    def _ema(p, n):
        """计算指定长度的 EMA 序列。"""
        if len(p) < n:
            return p[-1]
        k = 2 / (n + 1)
        ema = sum(p[-n:]) / n  # SMA 初始化
        for v in p[-(n - 1):]:
            ema = (v - ema) * k + ema
        return ema

    def _macd(p):
        if len(p) < 35:
            return {"macdLine": 0, "signalLine": 0, "histogram": 0}
        # 计算 EMA12 和 EMA26 的历史序列（取最近 40 个交易日）
        work = p[-40:] if len(p) > 40 else p
        k12, k26, k9 = 2 / 13, 2 / 27, 2 / 10

        # 初始化 EMA 用 SMA
        e12 = sum(work[:12]) / 12 if len(work) >= 12 else sum(work) / len(work)
        e26 = sum(work[:26]) / 26 if len(work) >= 26 else sum(work) / len(work)

        # 遍历计算 EMA
        macd_series = []
        for idx, v in enumerate(work):
            if idx >= 12:
                e12 = (v - e12) * k12 + e12
            if idx >= 26:
                e26 = (v - e26) * k26 + e26
            if idx >= 26:
                macd_series.append(e12 - e26)

        if not macd_series:
            return {"macdLine": 0, "signalLine": 0, "histogram": 0}

        macd_line = macd_series[-1]

        # 信号线 = 对 MACD 序列做 EMA9
        signal = sum(macd_series[:9]) / 9 if len(macd_series) >= 9 else macd_series[-1]
        for v in macd_series:
            signal = (v - signal) * k9 + signal

        return {
            "macdLine": round(macd_line, 2),
            "signalLine": round(signal, 2),
            "histogram": round(macd_line - signal, 2),
        }

    def _volatility(p, n=20):
        if len(p) < n + 1:
            return 0
        returns = [(p[i + 1] - p[i]) / p[i] for i in range(-n, 0)]
        mean = sum(returns) / n
        var = sum((r - mean) ** 2 for r in returns) / n
        return round(math.sqrt(var) * math.sqrt(252) * 100, 2)

    def _sr(p, look=10):
        pmin, pmax = float("inf"), float("-inf")
        for i in range(look, len(p) - look):
            is_min = all(p[j] >= p[i] for j in range(i - look, i + look + 1) if 0 <= j < len(p))
            is_max = all(p[j] <= p[i] for j in range(i - look, i + look + 1) if 0 <= j < len(p))
            if is_min:
                pmin = min(pmin, p[i])
            if is_max:
                pmax = max(pmax, p[i])
        last_p = p[-1]
        return [
            round(pmin if pmin != float("inf") else last_p * 0.95, 2),
            round(pmax if pmax != float("-inf") else last_p * 1.05, 2),
        ]

    cur_price = quote.get("price", 0)
    ind = {}
    ind["dailyChange"] = round(quote.get("changePercent", 0), 2)
    if len(p1y) >= 5:   ind["MA5"] = _sma(p1y, 5)
    if len(p1y) >= 10:  ind["MA10"] = _sma(p1y, 10)
    if len(p1y) >= 20:  ind["MA20"] = _sma(p1y, 20)
    if len(p1y) >= 60:  ind["MA60"] = _sma(p1y, 60)
    if len(p3m) >= 15:  ind["RSI_14"] = _rsi(p3m, 14)
    if len(p1y) >= 35:
        ind.update(_macd(p1y))
    if len(p1y) >= 21:
        ind["volatility_20d"] = _volatility(p1y, 20)
    if len(p1y) >= 40:
        sr = _sr(p1y, 10)
        ind["support_level"] = sr[0]
        ind["resistance_level"] = sr[1]
    if len(p1m) >= 5:
        ind["change_5d"] = round((cur_price - p1m[-5]) / p1m[-5] * 100, 2)
    if len(p1m) >= 20:
        ind["change_20d"] = round((cur_price - p1m[0]) / p1m[0] * 100, 2)
    if len(p3m) >= 60:
        ind["change_60d"] = round((cur_price - p3m[0]) / p3m[0] * 100, 2)

    # 最近 K 线（最多 20 条）
    kline = d1m[-20:] if len(d1m) > 20 else d1m

    return {
        "quote": quote,
        "indicators": ind,
        "fundamentals": fundamentals if fundamentals else None,
        "marketData": {"dailyKline": kline, "dataSource": "sina+yfinance+eastmoney"},
    }


# ═══════════════════════════════════════════════════════════════
#  入口
# ═══════════════════════════════════════════════════════════════

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(json.dumps({"error": "用法: yfinance_data.py <quote|history|full> <symbol> [args...]"}))
        sys.exit(1)

    cmd, symbol = sys.argv[1], sys.argv[2]

    try:
        if cmd == "quote":
            data = get_quote(symbol)
        elif cmd == "history":
            period = sys.argv[3] if len(sys.argv) > 3 else "1mo"
            interval = sys.argv[4] if len(sys.argv) > 4 else "1d"
            data = get_history(symbol, period, interval)
        elif cmd == "full":
            data = get_full(symbol)
        else:
            data = {"error": f"未知命令: {cmd}"}

        print(json.dumps(data, ensure_ascii=False, default=str))
    except Exception as e:
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.exit(1)
