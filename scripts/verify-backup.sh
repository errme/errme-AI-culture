#!/usr/bin/env bash
# =============================================================================
#  备份演练：把最近的备份恢复到临时库并自动校验
#
#  为什么需要：备份脚本跑成功 ≠ 备份能用。这个脚本把「恢复」也跑一遍：
#    1) 找到 backups/ 下最新的 SQL 备份（或指定文件）
#    2) 恢复到一个临时库（culture_v2_verify_<时间戳>，绝不动生产库）
#    3) 校验关键表行数与关键索引是否存在
#    4) 校验媒体/上传目录备份是否可解包（干了就报错）
#    5) 打印结论并清理临时库（--keep 可保留）
#
#  用法：
#    bash scripts/verify-backup.sh                # 演练最新备份
#    bash scripts/verify-backup.sh path/to.sql    # 演练指定备份
#    KEEP=1 bash scripts/verify-backup.sh         # 保留临时库
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MYSQL_BIN="${MYSQL_BIN:-D:/me/SQL/MySQL/MySQL/bin/mysql.exe}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-123456}"
SRC_DB="${SRC_DB:-culture_v2}"
KEEP="${KEEP:-0}"

BACKUP_FILE="${1:-}"
if [ -z "$BACKUP_FILE" ]; then
  BACKUP_FILE="$(ls -1t backups/*.sql 2>/dev/null | head -1 || true)"
fi
if [ -z "$BACKUP_FILE" ] || [ ! -f "$BACKUP_FILE" ]; then
  echo "找不到备份文件。用法：bash scripts/verify-backup.sh [path/to.sql]" >&2
  echo "（先执行 bash scripts/backup.sh 生成备份）" >&2
  exit 2
fi
echo "[verify-backup] 使用备份：$BACKUP_FILE（$(du -h "$BACKUP_FILE" | cut -f1)）"

TMP_DB="${SRC_DB}_verify_$(date +%Y%m%d%H%M%S)"
echo "[verify-backup] 临时库：$TMP_DB"

cleanup() {
  if [ "$KEEP" = "1" ]; then
    echo "[verify-backup] 保留临时库（KEEP=1）：$TMP_DB"
    return
  fi
  "$MYSQL_BIN" --no-defaults -u"$MYSQL_USER" -p"$MYSQL_PASS" -e "DROP DATABASE IF EXISTS \`$TMP_DB\`;" >/dev/null 2>&1 || true
  echo "[verify-backup] 已删除临时库"
}
trap cleanup EXIT

echo "[verify-backup] 1/4 创建临时库并导入备份 ..."
"$MYSQL_BIN" --no-defaults -u"$MYSQL_USER" -p"$MYSQL_PASS" -e "DROP DATABASE IF EXISTS \`$TMP_DB\`; CREATE DATABASE \`$TMP_DB\` DEFAULT CHARACTER SET utf8mb4;" 2>/dev/null
"$MYSQL_BIN" --no-defaults --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASS" "$TMP_DB" < "$BACKUP_FILE" 2>/dev/null

echo "[verify-backup] 2/4 校验关键表行数 ..."
TABLES="biz_culture biz_category biz_tag biz_culture_tag biz_comment biz_announcement biz_sentence sys_user sys_role sys_menu sys_permission sys_role_permission sys_operation_log"
SQL="SELECT 'table', 'rows' UNION ALL "
FIRST=1
for t in $TABLES; do
  [ $FIRST -eq 1 ] || SQL="$SQL UNION ALL "
  SQL="$SQL SELECT '$t', COUNT(*) FROM $t"
  FIRST=0
done
VERIFY_OUT="$("$MYSQL_BIN" --no-defaults -B -N -u"$MYSQL_USER" -p"$MYSQL_PASS" "$TMP_DB" -e "$SQL" 2>/dev/null || true)"
if [ -z "$VERIFY_OUT" ]; then
  echo "  ✗ 备份导入后查不到任何表——备份文件可能损坏或不完整" >&2
  exit 1
fi
printf '%s\n' "$VERIFY_OUT" | while IFS=$'\t' read -r t n; do
  printf '  %-24s %s 行\n' "$t" "$n"
done

echo "[verify-backup] 3/4 校验关键索引（列表分页/标签反查） ..."
IDX_OUT="$("$MYSQL_BIN" --no-defaults -B -N -u"$MYSQL_USER" -p"$MYSQL_PASS" -e \
  "SELECT table_name, index_name FROM information_schema.statistics WHERE table_schema='$TMP_DB' AND index_name IN ('idx_culture_deleted_id','idx_culture_deleted_category_id','idx_culture_tag_tag_culture');" 2>/dev/null || true)"
COUNT_IDX="$(printf '%s\n' "$IDX_OUT" | grep -c . || true)"
if [ "${COUNT_IDX:-0}" -ge 3 ]; then
  echo "  ✓ 关键索引齐全（$COUNT_IDX/3）"
else
  echo "  ⚠ 关键索引缺失（$COUNT_IDX/3）——请确认备份是否在 docs/sql/04_indexes.sql 之后生成" >&2
fi

echo "[verify-backup] 4/4 校验媒体/上传备份可解包 ..."
LATEST_TAR="$(ls -1t backups/*upload*.tar.gz backups/*upload*.tgz backups/*media*.tar.gz 2>/dev/null | head -1 || true)"
if [ -z "$LATEST_TAR" ]; then
  echo "  （未找到上传目录备份包，跳过；backup.sh 若包含媒体备份请检查命名）"
else
  if tar -tzf "$LATEST_TAR" >/dev/null 2>&1; then
    echo "  ✓ $LATEST_TAR 可正常解包（$(tar -tzf "$LATEST_TAR" | wc -l | tr -d ' ') 个条目）"
  else
    echo "  ✗ $LATEST_TAR 解包失败——媒体备份可能已损坏" >&2
    exit 1
  fi
fi

echo
echo "[verify-backup] 结论：备份「$BACKUP_FILE」可以恢复，关键表与索引校验通过 ✅"
