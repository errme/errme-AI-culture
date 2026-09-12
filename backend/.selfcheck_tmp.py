# -*- coding: utf-8 -*-
"""临时自查脚本（跑完即删）：括号/字符串配平 + mapper 方法与 SQL 一一对应。"""
import re
import sys
import xml.etree.ElementTree as ET

JAVA_FILES = [
    "src/main/java/com/culture/mapper/CultureMapper.java",
    "src/main/java/com/culture/mapper/TagMapper.java",
    "src/main/java/com/culture/service/CultureService.java",
    "src/main/java/com/culture/service/impl/CultureServiceImpl.java",
    "src/main/java/com/culture/api/ApiHomeController.java",
    "src/main/java/com/culture/query/CultureQuery.java",
]

BACKSLASH = chr(92)
QUOTE = chr(34)
SQUOTE = chr(39)


def balance(path):
    src = open(path, encoding="utf-8").read()
    problems = []
    i, n, line = 0, len(src), 1
    state = None
    stack = []
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
        if state is None:
            if c == "/" and nxt == "/":
                state = "line"
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = "block"
                i += 2
                continue
            if c == QUOTE:
                state = "str"
                i += 1
                continue
            if c == SQUOTE:
                state = "char"
                i += 1
                continue
            if c in "([{":
                stack.append((c, line))
            elif c in ")]}":
                if not stack:
                    problems.append("EXTRA %s at line %d" % (c, line))
                else:
                    o, ol = stack.pop()
                    if "([{".index(o) != ")]}".index(c):
                        problems.append("MISMATCH %s(l%d) vs %s(l%d)" % (o, ol, c, line))
            i += 1
            continue
        if state == "line":
            if c == "\n":
                state = None
            i += 1
            continue
        if state == "block":
            if c == "*" and nxt == "/":
                state = None
                i += 2
                continue
            i += 1
            continue
        if state == "str":
            if c == BACKSLASH:
                i += 2
                continue
            if c == QUOTE:
                state = None
            i += 1
            continue
        if state == "char":
            if c == BACKSLASH:
                i += 2
                continue
            if c == SQUOTE:
                state = None
            i += 1
            continue
    if state in ("block", "str", "char"):
        problems.append("UNCLOSED %s at EOF" % state)
    for o, ol in stack:
        problems.append("UNCLOSED %s opened at line %d" % (o, ol))
    print(("OK   " if not problems else "FAIL ") + path)
    for p in problems:
        print("      ", p)
    return not problems


def mapper_pairing():
    """CultureMapper.java 的每个方法：要么有 XML statement，要么有注解 SQL（或两者都不是=纯声明，不可能）。"""
    iface = open(JAVA_FILES[0], encoding="utf-8").read()
    xml = open("src/main/resources/com/culture/mapper/CultureMapper.xml", encoding="utf-8").read()
    xml_ids = set(re.findall(r'<(?:select|insert|update|delete)\s+id="(\w+)"', xml))

    # 逐行解析：注解块内的 SQL 会被跳过（注解参数里不会出现 4 空格缩进的方法声明）
    methods = []           # [(name, has_annotation)]
    pending_annot = False
    depth = 0
    in_annot = False
    for raw in iface.splitlines():
        line = raw.rstrip()
        stripped = line.strip()
        if not in_annot and re.match(r'^@(Select|Update|Insert|Delete)\b', stripped):
            in_annot = True
            depth = stripped.count("(") - stripped.count(")")
            pending_annot = True
            continue
        if in_annot:
            depth += stripped.count("(") - stripped.count(")")
            if depth <= 0:
                in_annot = False
            continue
        m = re.match(r'^    [\w<>,\[\]\.]+(?:\s+[\w<>,\[\]\.]+)*\s+(\w+)\s*\(', line)
        if m:
            methods.append((m.group(1), pending_annot))
            pending_annot = False
    missing = [n for n, a in methods if n not in xml_ids and not a]
    both = [n for n, a in methods if n in xml_ids and a]
    orphan_xml = sorted(xml_ids - set(n for n, _ in methods))
    print("CultureMapper: methods=%d, xml statements=%d" % (len(methods), len(xml_ids)))
    print("  method without any SQL :", missing)
    print("  method with BOTH xml+annotation (ambiguous):", both)
    print("  xml statement without method:", orphan_xml)
    ok = not missing and not both and not orphan_xml
    print(("OK   " if ok else "FAIL ") + "mapper <-> xml pairing")
    return ok


ok = True
for f in JAVA_FILES:
    ok = balance(f) and ok
try:
    ET.parse("src/main/resources/com/culture/mapper/CultureMapper.xml")
    print("OK   CultureMapper.xml well-formed")
except Exception as e:  # noqa
    ok = False
    print("FAIL CultureMapper.xml:", e)
ok = mapper_pairing() and ok
sys.exit(0 if ok else 1)
