#!/usr/bin/env bash
# =============================================================================
#  一键构建 + 校验（本地/CI 通用）
#
#  用法：
#    bash scripts/ci.sh              # 构建 + 静态检查 + 端到端回归（需要服务已启动）
#    bash scripts/ci.sh --build-only # 只构建（不跑端到端）
#
#  前置：
#    · 前端：web/ 下 npm 依赖已安装
#    · 后端：Maven 可用；打包前需要先把正在运行的后端停掉（Windows 会锁住 target/*.jar）
#    · 端到端：后端 8081 + 静态预览 8080 已启动（node web/tools/serve-dist.mjs）
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "===== 1/4 前端构建 + 预渲染 ====="
(cd web && npm run build:all)

echo
echo "===== 2/4 后端打包（跳过测试，测试见 3/4）====="
# Windows 下运行中的后端会锁住 target/*.jar，导致 repackage 失败，因此先停掉它
stop_backend_if_running() {
  local pid=""
  if command -v netstat >/dev/null 2>&1; then
    pid="$(netstat -ano 2>/dev/null | grep ':8081' | grep -i LISTENING | head -1 | awk '{print $NF}')"
  fi
  if [ -z "$pid" ] && command -v lsof >/dev/null 2>&1; then
    pid="$(lsof -ti tcp:8081 2>/dev/null | head -1)"
  fi
  if [ -n "$pid" ]; then
    echo "  检测到 8081 正在运行（PID=$pid），先停止以便替换 jar"
    taskkill //PID "$pid" //F >/dev/null 2>&1 || kill "$pid" 2>/dev/null || true
    sleep 2
  fi
}
stop_backend_if_running
(cd backend && mvn -q -DskipTests package)

echo
echo "===== 3/4 后端零依赖自测（离线环境没有 JUnit，用 main + 断言）====="
# SelfTest 依赖 Spring/MyBatis/JJWT 等类，类路径由 Maven 生成（离线也可用，依赖已在本地仓库）
SEP=";"
case "$(uname -s)" in Linux|Darwin) SEP=":" ;; esac
(cd backend   && mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt   && javac -encoding UTF-8 -d target/test-classes -cp "target/classes${SEP}$(cat target/cp.txt)" src/test/java/com/culture/SelfTest.java   && java -Dfile.encoding=UTF-8 -cp "target/classes${SEP}target/test-classes${SEP}$(cat target/cp.txt)" com.culture.SelfTest)

echo
echo "===== 4/4 入口 HTML 检查 + 前端单元测试 + 端到端回归 ====="
(cd web && npm run check:entries)
(cd web && npm test)

if [ "${1:-}" = "--build-only" ]; then
  echo
  echo "构建完成（--build-only，跳过端到端）"
  exit 0
fi

(cd web && node tools/verify.mjs)

echo
echo "全部完成 ✅"
