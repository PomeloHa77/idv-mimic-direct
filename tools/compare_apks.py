#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
两个 APK 的「条目级差异」——专门给「外挂样本 / 别人的改包 vs 官方原包」这种对比用。

为什么需要它：判断一条改包路线能不能活，第一现场不是代码，而是**包结构**：
  对方的包名/证书是什么、动了哪些 zip 条目、反外挂相关的 so 和 assets 有没有被碰。
  这些都是 30 秒内能拿到的硬事实，比读代码快得多。

用法：
    python tools/compare_apks.py 官方.apk 样本.apk
    python tools/compare_apks.py 官方.apk 样本.apk --json diff.json

输出：
  1) 条目集合差异：新增 / 删除 / 同名但内容不同（用 CRC32 + 大小 + 压缩方式判定）
  2) 按目录分组统计（lib/、assets/、res/、根目录 …）
  3) 高亮「第一现场」条目：classes*.dex、lib/*.so、assets/ntunisdk_so_uuids、
     assets/probeSoMd5Record.txt、assets/emulatordetector_data
  4) v1 证书（META-INF/*.RSA|*.DSA|*.EC）的 SHA-256 与可读主体串

注意：只读 zip 中央目录，不解压 2 GB 的包体，几秒出结果。
"""

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from collections import defaultdict

# Windows 控制台默认不是 UTF-8，中文输出会变乱码；显式设一次。
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

HOT_PREFIXES = ("lib/", "assets/", "META-INF/")
HOT_EXACT = {
    "assets/ntunisdk_so_uuids",
    "assets/probeSoMd5Record.txt",
    "assets/emulatordetector_data",
}
HOT_RE = re.compile(r"^classes\d*\.dex$|^AndroidManifest\.xml$|^resources\.arsc$")


def inventory(path):
    """返回 {name: info}；只读中央目录。"""
    z = zipfile.ZipFile(path)
    inv = {}
    for i in z.infolist():
        inv[i.filename] = {
            "size": i.file_size,
            "csize": i.compress_size,
            "crc": i.CRC,
            "method": i.compress_type,
        }
    z.close()
    return inv


def same(a, b):
    return (a["size"] == b["size"] and a["crc"] == b["crc"]
            and a["method"] == b["method"])


def cert_info(path):
    """v1 证书：META-INF/*.RSA|DSA|EC 的 SHA-256 + DER 里能读出来的主体串。"""
    out = {}
    z = zipfile.ZipFile(path)
    for name in z.namelist():
        if not name.startswith("META-INF/"):
            continue
        if not re.search(r"\.(RSA|DSA|EC)$", name, re.I):
            continue
        der = z.read(name)
        sha = hashlib.sha256(der).hexdigest()
        strings = [s.decode("ascii", "ignore")
                   for s in re.findall(rb"[\x20-\x7e]{6,}", der)]
        subj = [s for s in strings if re.search(r"(CN=|O=|OU=|C=)", s)][:6]
        out[name] = {"sha256": sha, "len": len(der), "readable": subj}
    z.close()
    return out


def group_of(name):
    if name.startswith("lib/"):
        parts = name.split("/")
        return "lib/" + (parts[1] if len(parts) > 2 else "")
    if "/" in name:
        return name.split("/")[0] + "/"
    return "(root)"


def is_hot(name):
    return (name in HOT_EXACT or bool(HOT_RE.match(name))
            or (name.startswith("lib/") and name.endswith(".so"))
            or name.startswith("assets/"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("official")
    ap.add_argument("sample")
    ap.add_argument("--json")
    ap.add_argument("--max-list", type=int, default=40)
    a = ap.parse_args()

    for p in (a.official, a.sample):
        if not os.path.exists(p):
            print("找不到文件：" + p)
            return 2

    print("A(基准) = " + os.path.basename(a.official))
    print("B(样本) = " + os.path.basename(a.sample))
    print("A size = {:,} B".format(os.path.getsize(a.official)))
    print("B size = {:,} B".format(os.path.getsize(a.sample)))

    ia, ib = inventory(a.official), inventory(a.sample)
    na, nb = set(ia), set(ib)
    added = sorted(nb - na)
    removed = sorted(na - nb)
    changed = sorted(n for n in (na & nb) if not same(ia[n], ib[n]))

    print("\n== 1. 条目差异 ==")
    print("A 条目数 {:,}   B 条目数 {:,}".format(len(ia), len(ib)))
    print("新增 {}   删除 {}   内容变化 {}".format(len(added), len(removed), len(changed)))
    for tag, lst in (("+ 新增", added), ("- 删除", removed), ("~ 变化", changed)):
        if not lst:
            continue
        print("\n  {}（最多列 {} 条）".format(tag, a.max_list))
        for n in lst[:a.max_list]:
            if n in ia and n in ib:
                print("    {}  A: size={:,} crc={:08x} m={}  ->  B: size={:,} crc={:08x} m={}".format(
                    n, ia[n]["size"], ia[n]["crc"], ia[n]["method"],
                    ib[n]["size"], ib[n]["crc"], ib[n]["method"]))
            elif n in ib:
                print("    {}  size={:,} crc={:08x} m={}".format(
                    n, ib[n]["size"], ib[n]["crc"], ib[n]["method"]))
            else:
                print("    {}  size={:,} crc={:08x} m={}".format(
                    n, ia[n]["size"], ia[n]["crc"], ia[n]["method"]))
        if len(lst) > a.max_list:
            print("    …（还有 {} 条）".format(len(lst) - a.max_list))

    print("\n== 2. 按分组统计 ==")
    stat = defaultdict(lambda: [0, 0, 0])
    for n in added:
        stat[group_of(n)][0] += 1
    for n in removed:
        stat[group_of(n)][1] += 1
    for n in changed:
        stat[group_of(n)][2] += 1
    for g in sorted(stat):
        add, rem, chg = stat[g]
        print("    {:28s} +{} -{} ~{}".format(g, add, rem, chg))

    print("\n== 3. 第一现场（dex / so / 反外挂 assets） ==")
    hot = [n for n in sorted(set(added) | set(removed) | set(changed)) if is_hot(n)]
    if not hot:
        print("    （无）")
    for n in hot[:a.max_list]:
        kind = "新增" if n in added else ("删除" if n in removed else "变化")
        print("    [{}] {}".format(kind, n))
    if len(hot) > a.max_list:
        print("    …（还有 {} 条）".format(len(hot) - a.max_list))

    print("\n== 4. v1 证书 ==")
    ca, cb = cert_info(a.official), cert_info(a.sample)
    for tag, c in (("A", ca), ("B", cb)):
        if not c:
            print("    {}: 没有 v1 证书条目（可能只用了 v2/v3）".format(tag))
        for name, info in c.items():
            print("    {} {}  sha256={}  len={}".format(tag, name, info["sha256"], info["len"]))
            for s in info["readable"]:
                print("         {}".format(s))
    if ca and cb and set(ca) & set(cb):
        k = sorted(set(ca) & set(cb))[0]
        print("    证书是否相同：{}".format(ca[k]["sha256"] == cb[k]["sha256"]))

    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump({"official": a.official, "sample": a.sample,
                       "added": added, "removed": removed, "changed": changed,
                       "certs_official": ca, "certs_sample": cb}, f,
                      ensure_ascii=False, indent=2)
        print("\n已写出 " + a.json)
    return 0


if __name__ == "__main__":
    sys.exit(main())
