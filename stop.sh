#!/usr/bin/env bash
# ============================================================
# 遇你 · culture 停止脚本
# 用法：
#   ./stop.sh          # 停止后端 + 前端预览服
#   ./stop.sh --all    # 再停止 MySQL + Redis
# ============================================================
cd "$(dirname "$0")"

stop_port () {
  local port="$1" name="$2"
  local pid
  pid=$(netstat -ano 2>/dev/null | grep ":$port .*LISTENING" | head -1 | awk '{print $NF}')
  if [ -n "$pid" ]; then
    echo "停止 $name (端口 $port, PID=$pid) ..."
    powershell -NoProfile -Command "Stop-Process -Id $pid -Force" 2>/dev/null && echo "  已停止"
  else
    echo "$name 未运行（端口 $port）"
  fi
}

stop_port 8081 "后端 API"
stop_port 8080 "前端预览服"

if [ "$1" = "--all" ]; then
  echo "停止 MySQL / Redis ..."
  powershell -NoProfile -Command "Get-Process mysqld,redis-server -ErrorAction SilentlyContinue | Stop-Process -Force" 2>/dev/null
  echo "  已停止"
fi
