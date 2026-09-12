#!/usr/bin/env bash
# ============================================================
# 发布后重建静态页（SEO 预渲染）
#
# 流程：构建前端 → 预渲染内容页（每路由一个 index.html） → 重载 Nginx
#
# 为什么需要它：预渲染产物是「内容快照」，后台新增/修改内容后需要重建，
# 爬虫才能抓到最新正文。二选一：
#   A) 手动/脚本触发：后台发布内容后执行本脚本；
#   B) 定时触发（推荐，简单可靠）：
#      Linux   : 0 4 * * *  /www/wwwroot/culture/scripts/rebuild-static.sh >> /var/log/culture-static.log 2>&1
#      Windows : schtasks /create /tn culture-static /tr "D:\me\AI\culture\scripts\rebuild-static.sh" /sc daily /st 04:00
#
# 前置：后端(8081)与前端预览服(8080) 正在运行（脚本会临时用预览服做快照；
#       若你直接用 Nginx 托管 dist，把 BASE_URL 指向 Nginx 地址即可）。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
export BASE_URL

log() { echo "[$(date '+%F %T')] $*"; }

log "1/4 构建前端（4 个入口）..."
(cd web && npm run build)

log "2/4 预渲染内容页（BASE_URL=$BASE_URL）..."
(cd web && node tools/prerender.mjs)

log "3/4 校验产物..."
COUNT=$(find web/dist -name index.html | wc -l | tr -d ' ')
log "    生成静态页 $COUNT 个"
if [ "$COUNT" -lt 2 ]; then
  log "    ⚠️ 静态页数量异常，请检查预览服/后端是否在运行"
  exit 1
fi

log "4/4 重载 Web 服务器..."
if command -v nginx >/dev/null 2>&1; then
  nginx -s reload && log "    Nginx 已重载" || log "    Nginx 重载失败（检查配置或权限）"
else
  log "    未检测到 Nginx：若使用本机预览服（serve-dist），它会直接读取新产物，无需重载"
fi

log "完成。爬虫验收： curl -s $BASE_URL/culture/1 | grep -o '<h1[^>]*>[^<]*'"
