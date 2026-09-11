#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""只做最小改动的 APK 重打包（raw zip 手术）。

为什么不用 zipfile 重写整包：1.88 GB 里绝大多数条目（含 400 MB 级 STORED 资源包）
与本次改动无关，重新压缩既慢又可能改变字节布局。这里直接按中央目录逐条搬运：
  - 未改动的条目：原样复制"本地头 + 压缩数据 + 数据描述符"，一个字节都不动
  - classes.dex：替换成补丁后的 dex（deflate 重压）
  - classes13.dex：新增（紧随 classes12.dex 之后）
  - 结尾重建中央目录 + EOCD，原 APK 的 v1/v2 签名块自然被丢弃（随后由 apksigner 重签）

用法：
    python tools/repack.py <原APK> <输出APK> --classes-dex <补丁后classes.dex> \
        [--extra-dex classes13.dex=<路径>] [--drop-v1-signature]
"""

import argparse
import mmap
import os
import struct
import sys
import zlib

EOCD_SIG = b"PK\x05\x06"
CDH_SIG = b"PK\x01\x02"
LFH_SIG = b"PK\x03\x04"
DD_SIG = b"PK\x07\x08"

CDH_STRUCT = "<IHHHHHHIIIHHHHHII"
LFH_STRUCT = "<IHHHHHIIIHH"


class Entry(object):
    def __init__(self, **kw):
        self.__dict__.update(kw)


def parse_central_directory(mm, size):
    idx = mm.rfind(EOCD_SIG, max(0, size - 66000))
    if idx < 0:
        raise RuntimeError("找不到 EOCD，不是合法 zip")
    eocd = mm[idx:idx + 22]
    (_sig, _disk, _cd_disk, _n_disk, n_total, cd_size, cd_off, comment_len) = struct.unpack(
        "<IHHHHIIH", eocd)
    comment = mm[idx + 22: idx + 22 + comment_len]
    entries = []
    p = cd_off
    for _ in range(n_total):
        if mm[p:p + 4] != CDH_SIG:
            raise RuntimeError("中央目录项损坏 @%d" % p)
        vals = struct.unpack_from(CDH_STRUCT, mm, p)
        (sig, ver_made, ver_need, flags, method, mtime, mdate, crc, csize, usize,
         nlen, elen, clen, disk, iattr, eattr, lho) = vals
        name = bytes(mm[p + 46: p + 46 + nlen])
        extra = bytes(mm[p + 46 + nlen: p + 46 + nlen + elen])
        cment = bytes(mm[p + 46 + nlen + elen: p + 46 + nlen + elen + clen])
        entries.append(Entry(ver_made=ver_made, ver_need=ver_need, flags=flags, method=method,
                             mtime=mtime, mdate=mdate, crc=crc, csize=csize, usize=usize,
                             name=name, extra=extra, comment=cment, iattr=iattr, eattr=eattr,
                             lho=lho))
        p += 46 + nlen + elen + clen
    return entries, comment


def local_record_len(mm, e):
    """本地记录总长：本地头 + 名称 + 扩展 + 压缩数据 + (数据描述符)。"""
    (lsig, _lver, lflags, _lm, _lt, _ld, _lcrc, _lcs, _lus, lnlen, lelen) = struct.unpack_from(
        LFH_STRUCT, mm, e.lho)
    if lsig != 0x04034b50:
        raise RuntimeError("本地头损坏: " + e.name.decode("utf-8", "replace"))
    body = 30 + lnlen + lelen + e.csize
    if lflags & 0x08:
        dstart = e.lho + 30 + lnlen + lelen + e.csize
        tag = bytes(mm[dstart:dstart + 4])
        if tag == DD_SIG:
            body += 16
        else:
            body += 12
        # 极少数 ZIP64 情况：数据描述符为 8 字节长度
        if e.csize == 0xFFFFFFFF or e.usize == 0xFFFFFFFF:
            body += 8
    return body


def write_local(o, e, method, crc, csize, usize, data):
    name = e.name
    extra = e.extra
    o.write(struct.pack("<IHHHHHIIIHH", 0x04034b50, e.ver_need, 0, method, e.mtime, e.mdate,
                        crc, csize, usize, len(name), len(extra)))
    o.write(name)
    o.write(extra)
    o.write(data)


def deflate(data):
    c = zlib.compressobj(6, zlib.DEFLATED, -15)
    out = c.compress(data) + c.flush()
    return out


def write_cd(o, e, method, crc, csize, usize, lho):
    name = e.name
    extra = e.extra
    o.write(struct.pack(CDH_STRUCT, 0x02014b50, e.ver_made, e.ver_need, 0, method, e.mtime,
                        e.mdate, crc, csize, usize, len(name), len(extra), len(e.comment),
                        0, e.iattr, e.eattr, lho))
    o.write(name)
    o.write(extra)
    o.write(e.comment)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--classes-dex", required=True)
    ap.add_argument("--extra-dex", action="append", default=[],
                    help="形如 classes13.dex=路径，可重复")
    ap.add_argument("--replace", action="append", default=[],
                    help="形如 AndroidManifest.xml=路径，用新内容替换该条目（deflate），可重复")
    ap.add_argument("--drop-v1-signature", action="store_true",
                    help="丢弃原包 META-INF 下的 v1 签名文件（MANIFEST.MF/*.SF/*.RSA/*.DSA），"
                         "避免重签后残留旧签名文件导致 v1 校验失败或被识别为多签名者")
    args = ap.parse_args()

    patched = open(args.classes_dex, "rb").read()
    replaces = {"classes.dex": patched}
    for spec in args.replace:
        name, _, path = spec.partition("=")
        replaces[name] = open(path, "rb").read()
    extras = []
    for spec in args.extra_dex:
        name, _, path = spec.partition("=")
        extras.append((name, open(path, "rb").read()))

    src_size = os.path.getsize(args.src)
    with open(args.src, "rb") as f, open(args.dst, "wb") as o:
        mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        try:
            entries, comment = parse_central_directory(mm, src_size)
            if args.drop_v1_signature:
                keep = []
                dropped = []
                for e in entries:
                    nm = e.name.decode("utf-8", "replace").upper()
                    base = nm.rsplit("/", 1)[-1]
                    if nm.startswith("META-INF/") and (
                        base == "MANIFEST.MF"
                        or base.endswith(".SF")
                        or base.endswith(".RSA")
                        or base.endswith(".DSA")
                        or base.endswith(".EC")
                    ):
                        dropped.append(nm)
                        continue
                    keep.append(e)
                entries = keep
                for nm in dropped:
                    print("  丢弃旧 v1 签名条目: " + nm)
            new_cd = []
            replaced = 0
            for e in entries:
                name = e.name.decode("utf-8", "replace")
                if name in replaces:
                    payload = replaces[name]
                    comp = deflate(payload)
                    lho = o.tell()
                    write_local(o, e, 8, zlib.crc32(payload) & 0xffffffff, len(comp), len(payload), comp)
                    new_cd.append((e, 8, zlib.crc32(payload) & 0xffffffff, len(comp), len(payload), lho))
                    replaced += 1
                    if name == "classes.dex":
                        # 附加 dex 紧跟 classes.dex（即 classes12.dex 之后）写入
                        for xname, xdata in extras:
                            xe = Entry(ver_made=e.ver_made, ver_need=e.ver_need, flags=0, method=0,
                                       mtime=e.mtime, mdate=e.mdate, name=xname.encode("utf-8"),
                                       extra=b"", comment=b"", iattr=0, eattr=e.eattr, lho=0)
                            comp2 = deflate(xdata)
                            lho2 = o.tell()
                            write_local(o, xe, 8, zlib.crc32(xdata) & 0xffffffff, len(comp2), len(xdata), comp2)
                            new_cd.append((xe, 8, zlib.crc32(xdata) & 0xffffffff, len(comp2), len(xdata), lho2))
                        extras = []
                else:
                    n = local_record_len(mm, e)
                    lho = o.tell()
                    o.write(mm[e.lho: e.lho + n])
                    new_cd.append((e, e.method, e.crc, e.csize, e.usize, lho))

            if extras:
                raise RuntimeError("没有找到 classes.dex，附加 dex 无处安放")

            cd_off = o.tell()
            for e, method, crc, csize, usize, lho in new_cd:
                write_cd(o, e, method, crc, csize, usize, lho)
            cd_size = o.tell() - cd_off
            o.write(struct.pack("<IHHHHIIH", 0x06054b50, 0, 0, len(new_cd), len(new_cd),
                                cd_size, cd_off, len(comment)))
            o.write(comment)
            print("重打包完成：%s（条目 %d，替换条目 %d 个）" % (args.dst, len(new_cd), replaced))
        finally:
            mm.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
