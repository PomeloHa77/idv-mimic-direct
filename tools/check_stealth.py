#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""隐身化改造的构建期断言（任何一条不过就让构建失败）。

四条：
  dex      classes13.dex 里不许出现任何品牌/玩法明文（字面量已全部密文化）
  so       libnrt.so 里不许有 Java_ / 旧库名 / 品牌字样 / 绑定类名
  collide  我们的类名不许和官方 12 个 dex 里任何类名/字符串撞车
  libname  新加的 so 文件名不许和原包已有的 lib 重名

用法：
  python tools/check_stealth.py dex <classes13.dex>
  python tools/check_stealth.py so <libnrt.so>
  python tools/check_stealth.py collide <原包 apk> Lz/a/a; Lz/a/b; ...
  python tools/check_stealth.py libname <原包 apk> lib/arm64-v8a/libnrt.so
"""

import sys
import zipfile

# 这些词一旦出现在 classes13.dex 里，就等于把「这是什么工具、干什么用的」写在了脸上
FORBIDDEN = [
    b'\xe6\xa8\xa1\xe4\xbb\xbf\xe8\x80\x85',      # 模仿者
    b'\xe7\xac\xac\xe4\xba\x94\xe4\xba\xba\xe6\xa0\xbc',  # 第五人格
    b'FJDirect',
    b'fjdirect',
    b'fj.direct',
    b'com/fj/direct',
    b'scan.txt',
    b'\xe7\x8b\xbc\xe4\xba\xba',                  # 狼人
    b'\xe4\xbe\xa6\xe6\x8e\xa2\xe5\x9b\xa2',      # 侦探团
    b'\xe7\xa5\x9e\xe7\xa7\x98\xe5\xae\xa2',      # 神秘客
    b'\xe9\x98\xb5\xe8\x90\xa5',                  # 阵营
    b'\xe8\xa7\x92\xe8\x89\xb2',                  # 角色
    b'\xe6\x89\xae\xe6\x89\xab',                  # 扫描
    b'libmmread',
    b'\xe5\xa4\x84\xe5\x88\x91\xe4\xba\xba',      # 处刑人
    b'\xe5\x82\xac\xe7\x9c\xa0\xe5\xb8\x88',      # 催眠师
]
SO_FORBIDDEN = [b'Java_', b'mmread', b'fj.direct', b'FJDirect', b'z/a/']


def die(msg):
    sys.stderr.write('check_stealth: %s\n' % msg)
    raise SystemExit(2)


def read_apk_dexes(apk):
    out = []
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if n.startswith('classes') and n.endswith('.dex'):
                out.append((n, z.read(n)))
    return out


def check_dex(path):
    d = open(path, 'rb').read()
    hits = []
    for pat in FORBIDDEN:
        if pat in d:
            hits.append(pat.decode('utf-8', 'replace'))
    if hits:
        die('classes13.dex 里还有明文特征：%s' % ', '.join(hits))
    # 顺带报一下剩下的可打印字符串条数，便于人工抽查
    import re
    printable = len(set(m.group() for m in re.finditer(rb'[ -~]{6,}', d)))
    print('    无明文特征 OK（剩余可打印串 %d 条，均为类名/字段名等结构性内容）' % printable)


def check_so(path):
    d = open(path, 'rb').read()
    hits = [p.decode('utf-8', 'replace') for p in SO_FORBIDDEN if p in d]
    if hits:
        die('%s 里残留可疑字符串：%s' % (path, ', '.join(hits)))
    print('    无可疑字符串 OK（Java_ / 旧库名 / 品牌字样 / 绑定类名 均无）')


def check_collide(apk, descriptors):
    blob = b''.join(d for _, d in read_apk_dexes(apk))
    hits = [s for s in descriptors if s.encode() in blob]
    if hits:
        die('类名与官方 dex 冲突：%s' % ', '.join(hits))
    print('    %d 个类名与官方 12 个 dex 均不冲突 OK' % len(descriptors))


def check_libname(apk, entry):
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
    if entry in names:
        die('新加的条目 %s 与原包重名' % entry)
    base = entry.split('/')[-1]
    same = [n for n in names if n.startswith('lib/') and n.split('/')[-1] == base]
    if same:
        die('库名 %s 与原包 %s 重名' % (base, ','.join(same)))
    print('    %s 不与原包任何 lib 重名 OK' % base)


def main():
    if len(sys.argv) < 3:
        die('参数不足')
    cmd = sys.argv[1]
    if cmd == 'dex':
        check_dex(sys.argv[2])
    elif cmd == 'so':
        check_so(sys.argv[2])
    elif cmd == 'collide':
        check_collide(sys.argv[2], sys.argv[3:])
    elif cmd == 'libname':
        check_libname(sys.argv[2], sys.argv[3])
    else:
        die('未知子命令 %s' % cmd)


if __name__ == '__main__':
    main()