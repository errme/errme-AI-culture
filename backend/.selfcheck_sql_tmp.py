# -*- coding: utf-8 -*-
"""临时自查脚本（跑完即删）：把 CultureMapper/TagMapper 的注解 SELECT 抽出来逐条 EXPLAIN + 实跑。
只读：DML 注解（@Update/@Insert/@Delete）只打印不执行。"""
import re
import subprocess

MYSQL = "D:/me/SQL/MySQL/MySQL/bin/mysql.exe"
ARGS = [MYSQL, "--no-defaults", "-uroot", "-p123456", "culture_v2", "-N", "-B", "-e"]

BIND = {
    "id": "1", "uid": "1", "userid": "1", "bid": "1", "kw": "'%博物馆%'",
    "likeKw": "'%博物馆%'", "offset": "0", "pageSize": "10", "limit": "4",
    "categoryId": "1", "excludeId": "2", "tagId": "1", "status": "1", "name": "'x'",
}


def extract(path):
    src = open(path, encoding="utf-8").read()
    out = []
    for m in re.finditer(
            r'@(Select|Update|Insert|Delete)\((.*?)\)\s*\n\s*(?:@\w+[^\n]*\n\s*)*[\w<>,\[\]\.\s]+?\s+(\w+)\s*\(',
            src, re.S):
        kind, body, name = m.group(1), m.group(2), m.group(3)
        if "foreach" in body:
            continue
        out.append((kind, name, "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', body))))
    return out


def run(sql):
    p = subprocess.run(ARGS + [sql], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    return p.returncode, (p.stdout or "").strip(), (p.stderr or "").strip()


def prepare(sql):
    return re.sub(r'#\{([^}]*)\}', lambda m: BIND.get(m.group(1).strip(), "'x'"), sql)


ok = skipped = failed = 0
for f in ["src/main/java/com/culture/mapper/CultureMapper.java",
          "src/main/java/com/culture/mapper/TagMapper.java"]:
    print("== " + f.split("/")[-1])
    for kind, name, raw in extract(f):
        sql = prepare(raw).strip()
        if kind != "Select" or not sql.lower().startswith("select"):
            print("  [SKIP] %-28s (%s 注解，未执行)" % (name, kind))
            skipped += 1
            continue
        rc1, out1, err1 = run("explain " + sql)
        rc2, out2, err2 = run(sql)
        good = rc1 == 0 and rc2 == 0
        ok += 1 if good else 0
        failed += 0 if good else 1
        rows = len(out2.splitlines()) if out2 else 0
        print("  [%s] %-28s explain=%s rows=%s %s" % (
            "OK  " if good else "FAIL", name, "ok" if rc1 == 0 else "ERR",
            rows, ("" if good else (err1 or err2).replace("\n", " ")[:140])))

print()
print("== XML 语句（XML 里的 whereSql / Seo SQL 按 status=1 展开后的等价 SQL）")
checks = [
    ("findSeoList", "select c.id, c.created_at, c.updated_at from biz_culture c where c.deleted = 0 and c.status = 1 order by c.id desc"),
    ("findSeoLatest", "select c.id, c.name, c.description, c.content, c.cover_url, c.category_id, c.created_at, c.updated_at, u.username as author_name from biz_culture c left join sys_user u on c.creator_id = u.id where c.deleted = 0 and c.status = 1 order by c.created_at desc, c.id desc limit 20"),
    ("findSeoDetail(pub)", "select c.id, c.name from biz_culture c where c.id = 1 and c.deleted = 0 and c.status = 1"),
    ("queryData(status=1)", "select u.id, u.name, u.address, u.description, LEFT(u.content, 300) infoSummary, u.cover_url, u.category_id, u.creator_id, u.view_count, u.like_count, u.created_at, s.id sid, s.username, c.id cid, c.name categoryName from biz_culture u join sys_user s on u.creator_id = s.id join biz_category c on u.category_id = c.id where u.deleted = 0 and u.status = 1 order by u.id desc limit 0,10"),
    ("queryTotal(status=1)", "select count(*) from biz_culture u where u.deleted = 0 and u.status = 1"),
    ("findAllByIds(pub)", "select id, name as cultureName from biz_culture where deleted=0 and status=1 and id in (1,2)"),
    ("tagCount(status=1)", "select count(*) from biz_culture c join biz_culture_tag ct on ct.culture_id = c.id where ct.tag_id = 1 and c.deleted = 0 and c.status = 1"),
    ("tagPage(status=1)", "select c.id, c.name, c.description from biz_culture c join biz_culture_tag ct on ct.culture_id = c.id where ct.tag_id = 1 and c.deleted = 0 and c.status = 1 order by c.id desc limit 0, 10"),
]
for name, sql in checks:
    rc1, out1, err1 = run("explain " + sql)
    rc2, out2, err2 = run("select count(*) from (" + sql + ") t")
    good = rc1 == 0 and rc2 == 0
    ok += 1 if good else 0
    failed += 0 if good else 1
    print("  [%s] %-22s explain=%s matched_rows=%s %s" % (
        "OK  " if good else "FAIL", name, "ok" if rc1 == 0 else "ERR",
        out2.splitlines()[0] if out2 else "-",
        ("" if good else (err1 or err2).replace("\n", " ")[:140])))

print()
print("checked_ok=%d skipped_dml=%d failed=%d" % (ok, skipped, failed))
