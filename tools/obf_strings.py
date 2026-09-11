#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Java 源码里的字符串字面量改写成 z.a.h.a(new byte[]{...}) 的密文形式。

为什么在源码层做、而不是直接改 dex：
  dex 的 string_data_item 是 uleb128 长度 + MUTF-8，原址改字节既改不了长度、
  又容易产出非法 MUTF-8；在源码层改写由 javac/d8 保证一切合法，
  而且 src/ 保持可读（改写后的副本只落在 work/obf-src）。

编码：c[i] = p[i] ^ K[(i * 5 + 7) & 15]
密钥 K 从 src/z/a/h.java 里解析（唯一真源，两边不一致构建就自检失败）。

输出：
  <dst>               改写后的源码树（目录结构与 src 一致）
  <map>               明文清单（hex，一行一条，顺序 = 改写顺序）
  <testdir>/z/a/T.java  构建期自检类：把每个密文用 h.a 解回来与原文比对

用法：
  python tools/obf_strings.py <src目录> <dst目录> --map <清单路径> --test-src <自检源码目录>

安全边界：`case "字面量":` 这种位置必须保持编译期常量，一律跳过并打印清单。
"""

import argparse
import glob
import os
import re
import sys

SIMPLE_ESCAPES = {
    'b': '\b', 't': '\t', 'n': '\n', 'f': '\f', 'r': '\r',
    '"': '"', "'": "'", '\\': '\\',
}

KEY_RE = re.compile(r'private\s+static\s+final\s+byte\[\]\s+K\s*=\s*\{(.*?)\}\s*;', re.S)


def fail(msg):
    sys.stderr.write('obf_strings: %s\n' % msg)
    raise SystemExit(2)


def read_key(h_java):
    """从 h.java 里取出 16 字节密钥（唯一真源）。"""
    if not os.path.exists(h_java):
        fail('找不到密钥文件 %s' % h_java)
    text = open(h_java, encoding='utf-8').read()
    m = KEY_RE.search(text)
    if not m:
        fail('%s 里找不到 K 数组' % h_java)
    hexes = re.findall(r'0x([0-9A-Fa-f]{2})', m.group(1))
    if len(hexes) != 16:
        fail('密钥必须是 16 字节，实际解析到 %d 个' % len(hexes))
    return [int(h, 16) for h in hexes]


def decode_java_string(raw, where):
    """把 Java 字符串字面量的「源码形式」（不含两端引号）还原成 Python str。"""
    out = []
    i = 0
    n = len(raw)
    while i < n:
        c = raw[i]
        if c != '\\':
            out.append(c)
            i += 1
            continue
        i += 1
        if i >= n:
            fail('%s：字面量以单个反斜杠结尾' % where)
        e = raw[i]
        if e in SIMPLE_ESCAPES:
            out.append(SIMPLE_ESCAPES[e])
            i += 1
            continue
        if e == 'u':
            h = raw[i + 1:i + 5]
            if not re.fullmatch(r'[0-9A-Fa-f]{4}', h):
                fail('%s：\\u 转义不完整' % where)
            out.append(chr(int(h, 16)))
            i += 5
            continue
        if e in '01234567':
            j = i
            oct_digits = ''
            while j < n and raw[j] in '01234567' and len(oct_digits) < 3:
                oct_digits += raw[j]
                j += 1
            out.append(chr(int(oct_digits, 8)))
            i = j
            continue
        fail('%s：不支持的转义 \\%s（请补进 SIMPLE_ESCAPES）' % (where, e))
    return ''.join(out)


def encode(value, key):
    """明文 -> 密文字节（与 h.a 的解码逐字节对称）。"""
    data = value.encode('utf-8')
    return bytes(b ^ key[(i * 5 + 7) & 0x0F] for i, b in enumerate(data))


def byte_array_literal(enc):
    """密文字节 -> Java 表达式里的数组字面量（十进制有符号，dex 里看不到明文）。"""
    return '{' + ', '.join(str(b - 256 if b > 127 else b) for b in enc) + '}'


def rewrite_text(text, key, path, records, skipped):
    """扫一遍源码，把字符串字面量换成解密调用；注释/字符字面量原样保留。"""
    out = []
    code_tail = ''
    i = 0
    n = len(text)

    def emit(s):
        nonlocal code_tail
        out.append(s)
        code_tail = (code_tail + s)[-80:]

    while i < n:
        c = text[i]
        # 行注释
        if c == '/' and text.startswith('//', i):
            j = text.find('\n', i)
            j = n if j < 0 else j
            emit(text[i:j])
            i = j
            continue
        # 块注释
        if c == '/' and text.startswith('/*', i):
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            emit(text[i:j])
            i = j
            continue
        # 字符字面量：整段跳过（不参与改写）
        if c == "'":
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == "'":
                    j += 1
                    break
                j += 1
            emit(text[i:j])
            i = j
            continue
        # 字符串字面量
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            if j >= n:
                fail('%s：字符串字面量没有闭合' % path)
            raw = text[i + 1:j]
            line = text.count('\n', 0, i) + 1
            value = decode_java_string(raw, '%s:%d' % (path, line))
            # case 标签必须是编译期常量，不能替换成方法调用
            if re.search(r'\bcase\s*$', code_tail):
                skipped.append('%s:%d' % (path, line))
                emit(text[i:j + 1])
                i = j + 1
                continue
            enc = encode(value, key)
            expr = 'z.a.h.a(new byte[]%s)' % byte_array_literal(enc)
            records.append((value, expr))
            emit(expr)
            i = j + 1
            continue
        emit(c)
        i += 1

    return ''.join(out)


def java_string_literal(value):
    """Python str -> Java 字符串字面量（自检类里用来放期望值的 hex）。"""
    return '"' + value.encode('utf-8').hex() + '"'


def write_test_class(test_dir_param, records):
    """生成 JVM 自检类：把每个密文解回来，和明文 hex 逐条比对。"""
    pkg_dir = os.path.join(test_dir_param, 'z', 'a')
    os.makedirs(pkg_dir, exist_ok=True)
    lines = []
    lines.append('package z.a;')
    lines.append('')
    lines.append('/**')
    lines.append(' * 构建期自检（自动生成，勿手改）：把每个密文用 h.a 解出来，')
    lines.append(' * 与源码原文的 UTF-8 hex 逐条比对 —— 编码面(Python)与解码面(Java)必须 100% 对称。')
    lines.append(' */')
    lines.append('public final class T {')
    lines.append('    public static void main(String[] a) {')
    lines.append('        int n = %d;' % len(records))
    lines.append('        byte[][] enc = new byte[n][];')
    lines.append('        String[] want = new String[n];')
    for idx, (value, expr) in enumerate(records):
        arr = expr[expr.index('new byte[]') + len('new byte[]'):-1]
        lines.append('        enc[%d] = new byte[]%s;' % (idx, arr))
        lines.append('        want[%d] = %s;' % (idx, java_string_literal(value)))
    lines.append('        int bad = 0;')
    lines.append('        for (int i = 0; i < n; i++) {')
    lines.append('            String got = hex(h.a(enc[i]));')
    lines.append('            if (!got.equals(want[i])) {')
    lines.append('                bad++;')
    lines.append('                if (bad <= 5) {')
    lines.append('                    System.out.println("MISMATCH #" + i'
                 ' + " want=" + want[i] + " got=" + got);')
    lines.append('                }')
    lines.append('            }')
    lines.append('        }')
    lines.append('        System.out.println("decode-check " + (n - bad) + "/" + n'
                 ' + (bad == 0 ? " OK" : " FAIL"));')
    lines.append('        if (bad != 0) { System.exit(1); }')
    lines.append('    }')
    lines.append('')
    lines.append('    private static String hex(String s) {')
    lines.append('        StringBuilder b = new StringBuilder();')
    lines.append('        try {')
    lines.append('            for (byte x : s.getBytes("UTF-8")) {')
    lines.append('                b.append(String.format("%02x", x & 0xff));')
    lines.append('            }')
    lines.append('        } catch (Throwable t) {')
    lines.append('            return "ERR";')
    lines.append('        }')
    lines.append('        return b.toString();')
    lines.append('    }')
    lines.append('')
    lines.append('    private T() {')
    lines.append('    }')
    lines.append('}')
    open(os.path.join(pkg_dir, 'T.java'), 'w', encoding='utf-8', newline='').write(
        '\n'.join(lines) + '\n')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('src')
    ap.add_argument('dst')
    ap.add_argument('--map', required=True)
    ap.add_argument('--test-src', required=True)
    args = ap.parse_args()

    key = read_key(os.path.join(args.src, 'z', 'a', 'h.java'))
    files = sorted(glob.glob(os.path.join(args.src, '**', '*.java'), recursive=True))
    if not files:
        fail('%s 下没有 .java' % args.src)

    records = []
    skipped = []
    for p in files:
        rel = os.path.relpath(p, args.src)
        text = open(p, encoding='utf-8', newline='').read()
        new_text = rewrite_text(text, key, rel.replace(os.sep, '/'), records, skipped)
        dst = os.path.join(args.dst, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, 'w', encoding='utf-8', newline='').write(new_text)

    with open(args.map, 'w', encoding='utf-8', newline='') as f:
        for value, _ in records:
            f.write(value.encode('utf-8').hex() + '\n')

    write_test_class(args.test_src, records)

    print('    改写 %d 个文件，%d 处字符串密文化' % (len(files), len(records)))
    if skipped:
        print('    跳过（case 标签，必须保持常量）：%s' % ', '.join(skipped))
    print('    密钥 = %s' % ''.join('%02x' % b for b in key))


if __name__ == '__main__':
    main()