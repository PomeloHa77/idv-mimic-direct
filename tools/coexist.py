#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""共存版：把 APK 的应用包名改掉，使它能与官方包同时安装。

为什么必须改包名：Android 用包名唯一标识应用，同包名的两个包无法共存。

改动面（全部经离线核对，见 README「共存版」一节）：
  1) AndroidManifest.xml 的 package 属性
  2) 所有 provider/authorities、以及 <permission> 声明
     —— authorities 撞车会导致第二个包直接 INSTALL_FAILED_CONFLICTING_PROVIDER；
        <permission> 同名但签名不同会 INSTALL_FAILED_DUPLICATE_PERMISSION
  3) dex 里对应这几个名字的字符串常量（Manifest$permission 的静态字段）

刻意不改的：
  * 组件 android:name —— 那是**真实类名**（com.netease.dwrg.Launcher 等），
    清单里的名字是全限定类名，不需要跟包名前缀一致；
  * resources.arsc —— 里面的 arsc 包名只是构建期标识，运行期资源定位靠 packageId(0x7f)。

包名怎么选：新包名取官方包的**超串**（com.netease.dwrg → com.netease.dwrg.fj）。
这样所有「用自己的包名做子串匹配」的逻辑（实测 classes5.dex 里 Client$2.run 就是这么
匹配自己进程做 CPU/RSS 上报的）在改包名后依旧命中，不需要动 classes5.dex。

用法：
    python tools/coexist.py plan  <apk>                       # 只打印改动计划
    python tools/coexist.py patch <apk> <输出axml> [--plan 文件]
"""

import argparse
import os
import re
import struct
import sys
import zipfile

OLD_PKG = "com.netease.dwrg"
NEW_PKG = "com.netease.dwrg.fj"

UTF8_FLAG = 0x00000100
SORTED_FLAG = 0x00000001

AXML_MAGIC = b"\x03\x00"
POOL_TYPE = b"\x01\x00"


# --------------------------------------------------------------------- dex 类型表
def uleb128(buf, off):
    r = 0
    s = 0
    while True:
        b = buf[off]
        off += 1
        r |= (b & 0x7F) << s
        if not (b & 0x80):
            break
        s += 7
    return r, off


def dex_type_descriptors(data):
    """返回该 dex 的全部类型描述符（形如 Lcom/netease/dwrg/Launcher;）。"""
    str_size, str_off = struct.unpack_from("<II", data, 0x38)
    type_size, type_off = struct.unpack_from("<II", data, 0x40)

    def s_at(i):
        (o,) = struct.unpack_from("<I", data, str_off + 4 * i)
        _n, p = uleb128(data, o)
        return data[p:data.index(b"\x00", p)].decode("utf-8", "replace")

    out = set()
    for i in range(type_size):
        (si,) = struct.unpack_from("<I", data, type_off + 4 * i)
        out.add(s_at(si))
    return out


def all_type_descriptors(apk):
    out = set()
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if re.match(r"^classes\d*\.dex$", n):
                out |= dex_type_descriptors(z.read(n))
    return out


# --------------------------------------------------------------------- AXML 字符串池
def read_pool(data):
    off = 8
    if data[off:off + 2] != POOL_TYPE:
        raise RuntimeError("第一个 chunk 不是字符串池")
    size, = struct.unpack_from("<I", data, off + 4)
    str_count, style_count, flags = struct.unpack_from("<III", data, off + 8)
    strings_start, styles_start = struct.unpack_from("<II", data, off + 20)
    offsets = struct.unpack_from("<%dI" % str_count, data, off + 28)
    return dict(off=off, size=size, str_count=str_count, style_count=style_count,
                flags=flags, strings_start=strings_start, styles_start=styles_start,
                offsets=offsets)


def decode_strings(data, pool):
    base = pool["off"] + pool["strings_start"]
    utf8 = bool(pool["flags"] & UTF8_FLAG)
    out = []
    for o in pool["offsets"]:
        p = base + o
        if utf8:
            _u, p = uleb128(data, p)
            n, p = uleb128(data, p)
            out.append(data[p:p + n].decode("utf-8", "replace"))
        else:
            (n,) = struct.unpack_from("<H", data, p)
            if n & 0x8000:
                (hi,) = struct.unpack_from("<H", data, p + 2)
                n = ((n & 0x7FFF) << 16) | hi
                p += 2
            p += 2
            out.append(data[p:p + 2 * n].decode("utf-16-le", "replace"))
    return out, utf8


def encode_pool(strings, utf8, style_count, style_blob):
    """按原编码方式重新编码字符串区，返回 (offsets, blob)。"""
    offsets = []
    buf = bytearray()
    for s in strings:
        # 4 字节对齐（aapt 对 UTF-8 强制对齐；UTF-16 也按同样规则更保险，
        # 因为读者只按偏移取串，多余填充会被忽略）
        if len(buf) % 4:
            buf.extend(b"\x00" * (4 - len(buf) % 4))
        offsets.append(len(buf))
        if utf8:
            raw = s.encode("utf-8")
            buf.extend(uleb128_bytes(len(s)) + uleb128_bytes(len(raw)) + raw + b"\x00")
        else:
            raw = s.encode("utf-16-le")
            n = len(s)
            if n > 0x7FFF:
                buf.extend(struct.pack("<HH", 0x8000 | (n >> 16), n & 0xFFFF))
            else:
                buf.extend(struct.pack("<H", n))
            buf.extend(raw)
            buf.extend(b"\x00\x00")
    if len(buf) % 4:
        buf.extend(b"\x00" * (4 - len(buf) % 4))
    return offsets, bytes(buf)


def uleb128_bytes(v):
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def patch_axml(data, mapping, verbose=True):
    """把 AXML 里等于 mapping 键的字符串换成对应值，返回 (新字节, 改动列表)。"""
    pool = read_pool(data)
    strings, utf8 = decode_strings(data, pool)

    changed = []
    new_strings = []
    for s in strings:
        if s in mapping:
            new_strings.append(mapping[s])
            changed.append((s, mapping[s]))
        else:
            new_strings.append(s)
    if not changed:
        return data, []

    # 原 style 区（一般不存在；存在就原样搬运）
    old_blob_end = pool["off"] + pool["size"]
    if pool["style_count"]:
        style_blob = bytes(data[pool["off"] + pool["styles_start"]:old_blob_end])
    else:
        style_blob = b""

    offsets, blob = encode_pool(new_strings, utf8, pool["style_count"], style_blob)

    # 字符串池头部 28 字节 + 偏移表 + style 偏移表，之后是新的字符串区
    header_size = 28 + 4 * pool["str_count"] + 4 * pool["style_count"]
    if pool["strings_start"] != header_size:
        # 原文件在偏移表和字符串区之间有额外填充，这里补上
        pad = pool["strings_start"] - header_size
    else:
        pad = 0
    new_strings_start = pool["strings_start"]
    new_styles_start = new_strings_start + len(blob) if pool["style_count"] else 0
    new_size = header_size + pad + len(blob) + len(style_blob)

    flags = pool["flags"]
    # 重命名可能破坏「已排序」不变量：池里若声明有序，索引查找会走二分。
    # 稳妥做法是把有序标志清掉，读者会退回线性扫描（只是慢一点，不影响正确性）。
    if flags & SORTED_FLAG:
        if [s for s in new_strings] != sorted(strings, key=lambda x: x.encode("utf-8")):
            flags &= ~SORTED_FLAG
            if verbose:
                print("    (字符串池原有 SORTED 标志，重命名后已清除该标志)")

    head = bytearray()
    head.extend(POOL_TYPE)
    head.extend(struct.pack("<H", 28))
    head.extend(struct.pack("<I", new_size))
    head.extend(struct.pack("<II", pool["str_count"], pool["style_count"]))
    head.extend(struct.pack("<I", flags))
    head.extend(struct.pack("<II", new_strings_start, new_styles_start))
    head.extend(struct.pack("<%dI" % pool["str_count"], *offsets))
    if pool["style_count"]:
        head.extend(data[pool["off"] + 28 + 4 * pool["str_count"]:
                         pool["off"] + 28 + 4 * pool["str_count"] + 4 * pool["style_count"]])

    body = bytes(head) + b"\x00" * pad + blob + style_blob
    assert len(body) == new_size, (len(body), new_size)

    out = bytearray(data[:pool["off"]])
    out.extend(body)
    out.extend(data[old_blob_end:])
    struct.pack_into("<I", out, 4, len(out))
    return bytes(out), changed


# --------------------------------------------------------------------- 改动计划
def build_mapping(apk, old_pkg=OLD_PKG, new_pkg=NEW_PKG, verbose=True):
    """得出需要改写的字符串映射。"""
    types = all_type_descriptors(apk)
    with zipfile.ZipFile(apk) as z:
        strs, utf8 = decode_strings(z.read("AndroidManifest.xml"),
                                   read_pool(z.read("AndroidManifest.xml")))
    mapping = {}
    keep_classes = []
    for s in sorted(set(strs)):
        if old_pkg not in s:
            continue
        if s == old_pkg:
            mapping[s] = new_pkg
            continue
        descriptor = "L" + s.replace(".", "/") + ";"
        if descriptor in types:
            keep_classes.append(s)          # 真实类名，不能改
            continue
        mapping[s] = s.replace(old_pkg, new_pkg)
    if verbose:
        print("== 要改写的清单字符串（%d 条）==" % len(mapping))
        for k in sorted(mapping):
            print("   %-72s -> %s" % (k, mapping[k]))
        print()
        print("== 保留不变的真实类名（%d 条）==" % len(keep_classes))
        for k in keep_classes:
            print("   " + k)
    return mapping, keep_classes


def cmd_plan(args):
    mapping, _keep = build_mapping(args.apk)
    print("\n共 %d 条改动（--dry-run，未写盘）" % len(mapping))
    return 0


def cmd_patch(args):
    mapping, _keep = build_mapping(args.apk, verbose=False)
    with zipfile.ZipFile(args.apk) as z:
        data = z.read("AndroidManifest.xml")
    new_data, changed = patch_axml(data, mapping)
    if not changed:
        print("没有命中任何字符串，清单未改动")
        return 1
    with open(args.out, "wb") as f:
        f.write(new_data)
    print("清单已改写：%d 处，%d -> %d 字节" % (len(changed), len(data), len(new_data)))
    for a, b in changed:
        print("   %-72s -> %s" % (a, b))
    return 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p1 = sub.add_parser("plan")
    p1.add_argument("apk")
    p1.set_defaults(func=cmd_plan)
    p2 = sub.add_parser("patch")
    p2.add_argument("apk")
    p2.add_argument("out")
    p2.set_defaults(func=cmd_patch)
    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
