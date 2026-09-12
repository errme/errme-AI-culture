#!/usr/bin/env bash
# ============================================================
# 遇你 · culture 备份脚本
#
# 备份内容（内容站的全部资产）：
#   1) MySQL 数据库 culture_v2（结构 + 数据）
#   2) 富文本正文媒体目录 upload/media（图片/视频）
#   3) 头像与文化封面 frontend/static/upload
#
# 用法：
#   ./scripts/backup.sh                 # 备份到 ./backups/yyyyMMdd_HHmmss/
#   BACKUP_DIR=/data/backup ./scripts/backup.sh
#   KEEP_DAYS=30 ./scripts/backup.sh    # 只保留最近 30 天（默认 14）
#
# 建议加到计划任务：
#   Windows: schtasks /create /tn culture-backup /tr "D:\me\AI\culture\scripts\backup.sh" /sc daily /st 03:00
#   Linux:   0 3 * * * /www/wwwroot/culture/scripts/backup.sh >> /var/log/culture-backup.log 2>&1
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

DB_NAME="${DB_NAME:-culture_v2}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-123456}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
MYSQL_BIN="${MYSQL_BIN:-/d/me/SQL/MySQL/MySQL/bin}"
KEEP_DAYS="${KEEP_DAYS:-14}"

STAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP_DIR="${BACKUP_DIR:-$PWD/backups}"
TARGET="$BACKUP_DIR/$STAMP"
mkdir -p "$TARGET"

log() { echo "[$(date '+%F %T')] $*"; }

log "开始备份 -> $TARGET"

# ---------- 1. 数据库 ----------
log "导出数据库 $DB_NAME ..."
"$MYSQL_BIN/mysqldump" --no-defaults \
  -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
  --single-transaction --routines --events --default-character-set=utf8mb4 \
  "$DB_NAME" > "$TARGET/db_${DB_NAME}.sql"
log "  数据库备份完成：$(du -h "$TARGET/db_${DB_NAME}.sql" | cut -f1)"

# ---------- 2. 正文媒体 ----------
if [ -d upload/media ]; then
  log "打包 upload/media ..."
  tar -czf "$TARGET/media.tar.gz" -C upload media
  log "  媒体备份完成：$(du -h "$TARGET/media.tar.gz" | cut -f1)"
fi

# ---------- 3. 头像与封面 ----------
if [ -d frontend/static/upload ]; then
  log "打包 frontend/static/upload ..."
  tar -czf "$TARGET/upload.tar.gz" -C frontend/static upload
  log "  上传目录备份完成：$(du -h "$TARGET/upload.tar.gz" | cut -f1)"
fi

# ---------- 4. 校验与清单 ----------
( cd "$TARGET" && md5sum ./* > MD5SUMS 2>/dev/null || true )
cat > "$TARGET/README.txt" <<EOF
备份时间：$(date '+%F %T')
数据库  ：$DB_NAME @ $DB_HOST:$DB_PORT
恢复数据库： mysql --no-defaults -u$DB_USER -p*** $DB_NAME < db_${DB_NAME}.sql
恢复媒体  ： tar -xzf media.tar.gz  -C <项目根>/upload
恢复上传  ： tar -xzf upload.tar.gz -C <项目根>/frontend/static
EOF

# ---------- 5. 清理过期备份 ----------
if [ -d "$BACKUP_DIR" ]; then
  log "清理 $KEEP_DAYS 天前的备份 ..."
  find "$BACKUP_DIR" -maxdepth 1 -type d -name '20*_*' -mtime +"$KEEP_DAYS" -exec rm -rf {} + 2>/dev/null || true
fi

log "备份完成：$TARGET（保留最近 $KEEP_DAYS 天）"
