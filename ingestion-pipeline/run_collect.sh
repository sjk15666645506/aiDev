#!/usr/bin/env bash
# ── A 股全量数据采集脚本 ──────────────────────────
# 用法:
#   ./run_collect.sh daily       # 日常采集（个股+ETF 快照、指数、行业板块）
#   ./run_collect.sh backfill    # K 线全量回填（断点续传，中断后重跑即可）
#   ./run_collect.sh status      # 查看采集状态
#   ./run_collect.sh update      # 刷新股票+ETF 清单
#
set -euo pipefail

cd "$(dirname "$0")"
PY="python3 batch_collect.py"

case "${1:-daily}" in
  daily)
    echo "=== 全量 A 股 + ETF 数据采集 ==="
    echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo ""
    $PY 2>&1 | tee -a collect.log
    ;;

  backfill)
    echo "=== K 线全量回填（断点续传）==="
    echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "已完成的标的自动跳过，无需担心中断"
    echo ""
    $PY --kline-backfill 2>&1 | tee -a collect.log
    ;;

  status)
    $PY --status
    ;;

  update)
    echo "=== 刷新股票+ETF 清单 ==="
    $PY --update-list
    ;;

  *)
    echo "用法: $0 {daily|backfill|status|update}"
    exit 1
    ;;
esac
