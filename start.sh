#!/usr/bin/env bash
# ============================================================
# 遇你 · culture 一键启动脚本（Git Bash / Linux）
#
# 架构：前端 Vue3 SPA（web/dist，Nginx 或本机预览服托管）
#       后端 Spring Boot 8081（纯 REST API + 设计资源 + 上传媒体）
#
# 用法：
#   ./start.sh            # 启动 MySQL + Redis + 后端 + 前端预览服(8080)
#   ./start.sh --no-db    # 只启动后端与前端（数据库已在运行）
#   ./start.sh --no-web   # 只启动后端（前端交给 Nginx）
# ============================================================
set -e
cd "$(dirname "$0")"

# ===== 可修改的配置（按本机路径） =====
MYSQLD="${MYSQLD:-/d/me/SQL/MySQL/MySQL/bin/mysqld}"
MYSQL_INI="${MYSQL_INI:-/d/me/SQL/MySQL/MySQL/my.ini}"
REDIS_SERVER="${REDIS_SERVER:-/d/me/SQL/Redis/redis-server.exe}"
REDIS_CONF="${REDIS_CONF:-/d/me/SQL/Redis/redis.windows.conf}"
JAR="backend/target/culture-0.0.1-SNAPSHOT.jar"
PORT=8081           # 后端（API）
WEB_PORT=8080       # 前端预览服（等价 Nginx）

db=1
web=1
for arg in "$@"; do
  [ "$arg" = "--no-db" ] && db=0
  [ "$arg" = "--no-web" ] && web=0
done

echo "========================================"
echo "  遇你 · culture 一键启动"
echo "========================================"

# ---------- 1. MySQL ----------
if [ $db -eq 1 ]; then
  if (echo > /dev/tcp/127.0.0.1/3306) 2>/dev/null; then
    echo "[1/4] MySQL 已在运行"
  else
    echo "[1/4] 启动 MySQL ..."
    nohup "$MYSQLD" --defaults-file="$MYSQL_INI" > /tmp/mysqld.log 2>&1 &
    for i in $(seq 1 15); do sleep 1; (echo > /dev/tcp/127.0.0.1/3306) 2>/dev/null && break; done
    if (echo > /dev/tcp/127.0.0.1/3306) 2>/dev/null; then echo "      MySQL OK"; else echo "      MySQL 启动失败，查看 /tmp/mysqld.log"; exit 1; fi
  fi

  # ---------- 2. Redis ----------
  if (echo > /dev/tcp/127.0.0.1/6379) 2>/dev/null; then
    echo "[2/4] Redis 已在运行"
  else
    echo "[2/4] 启动 Redis ..."
    nohup "$REDIS_SERVER" "$REDIS_CONF" > /tmp/redis.log 2>&1 &
    sleep 3
    if (echo > /dev/tcp/127.0.0.1/6379) 2>/dev/null; then echo "      Redis OK"; else echo "      Redis 启动失败，查看 /tmp/redis.log"; fi
  fi
else
  echo "[1/4] 跳过数据库（--no-db）"
  echo "[2/4] 跳过数据库（--no-db）"
fi

# ---------- 3. 后端（纯 REST API） ----------
if netstat -ano 2>/dev/null | grep -q ":$PORT .*LISTENING"; then
  echo "[3/4] 后端已在运行（端口 $PORT），如需重启先执行 ./stop.sh"
else
  if [ ! -f "$JAR" ]; then
    echo "[3/4] 未找到 jar，先构建（mvn package）..."
    (cd backend && mvn -q -DskipTests package)
  fi
  echo "[3/4] 启动后端 ..."
  nohup java -jar "$JAR" > /tmp/culture.log 2>&1 &
  for i in $(seq 1 40); do
    sleep 1
    if curl -s -o /dev/null "http://localhost:$PORT/api/home" 2>/dev/null; then break; fi
  done
fi

# ---------- 4. 前端（Vue3 构建产物 + 本机预览服） ----------
if [ $web -eq 1 ]; then
  if [ ! -d web/node_modules ]; then
    echo "[4/4] 安装前端依赖（npm install）..."
    (cd web && npm install --no-audit --no-fund)
  fi
  if [ ! -f web/dist/front.html ]; then
    echo "[4/4] 构建前端（npm run build）..."
    (cd web && npm run build)
  fi
  if netstat -ano 2>/dev/null | grep -q ":$WEB_PORT .*LISTENING"; then
    echo "[4/4] 前端预览服已在运行（端口 $WEB_PORT）"
  else
    echo "[4/4] 启动前端预览服（等价 Nginx）..."
    (cd web && nohup node tools/serve-dist.mjs > /tmp/serve-dist.log 2>&1 &)
    sleep 2
  fi
else
  echo "[4/4] 跳过前端（--no-web，交由 Nginx 托管 web/dist）"
fi

echo ""
echo "========================================"
echo "  ✅ 启动完成"
echo "     前台   http://localhost:$WEB_PORT/"
echo "     前台登录 http://localhost:$WEB_PORT/auth/login"
echo "     后台   http://localhost:$WEB_PORT/admin  （admin / 123456）"
echo "     后台登录 http://localhost:$WEB_PORT/admin/login"
echo "     接口   http://localhost:$PORT/api/home"
echo "     日志   tail -f /tmp/culture.log   /tmp/serve-dist.log"
echo "     生产   Nginx 配置见 web/nginx.conf.example（静态托管 web/dist）"
echo "========================================"
( cmd //c start "" "http://localhost:$WEB_PORT/" >/dev/null 2>&1 ) || true
