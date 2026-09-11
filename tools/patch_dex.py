#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对 baksmali 出来的 smali 树打补丁（幂等，可重复执行）。

四类补丁：
  1) 注入入口：在 UFProxyApplication.attachBaseContext / onCreate 的 super 调用之后，
     插入 z.a.a 的调用（两个方法内部自带去重，类名已中性化）。
  2) 签名回填：全树把 `PackageInfo->signatures` 的读取替换成 z.a.b.a()，
     解决重打包换签名导致网易 SDK/支付/热更新校验失败的问题。
  3) 签名回填（API 28+ 的 SigningInfo 链路）：把 `PackageInfo->signingInfo` +
     `SigningInfo->hasMultipleSigners/getApkContentsSigners/getSigningCertificateHistory`
     这段内联代码换成 z.a.b.a()。漏掉这条链，Android 9+ 上仍会读到我们自己的真实签名。
  4) 签名回填（ngplugin 的反射桥 C.l/C.m/C.r）：这三个 bridge 方法是 mpay/d、
     mpay/login/c$c 读取 SigningInfo 的唯一出口，整体改写为常量。

为什么要两条链都补：`PackageInfo.signatures` 虽被标为 deprecated，API 28 起仍可用；
网易 SDK 在 SDK_INT>=28 时优先走 SigningInfo，低版本才走 signatures。
只补一条等于在另一半机型上直接暴露自签名。

用法：
    python tools/patch_dex.py <smali目录> [--dry-run]
    python tools/patch_dex.py <smali目录> --keep-package   # 不改包名（原包名直装版）
"""

import argparse
import os
import re
import sys

PROXY_SMALI = os.path.join(
    "com", "netease", "ntunisdk", "unifix_hotfix_library", "proxyApplication",
    "UFProxyApplication.smali",
)

HOOKS = [
    (
        "attachBaseContext",
        re.compile(
            r"^([ \t]*)invoke-super \{p0, p1\}, Landroid/app/Application;->"
            r"attachBaseContext\(Landroid/content/Context;\)V\s*$"
        ),
        "invoke-static {p1}, Lz/a/a;->a(Landroid/content/Context;)V",
    ),
    (
        "onCreate",
        re.compile(
            r"^([ \t]*)invoke-super/range \{p0 \.\. p0\}, Landroid/app/Application;->onCreate\(\)V\s*$"
        ),
        "invoke-static {}, Lz/a/a;->b()V",
    ),
]

SIG_RE = re.compile(
    r"^([ \t]*)iget-object (p[0-9]+|v[0-9]+), (p[0-9]+|v[0-9]+), "
    r"Landroid/content/pm/PackageInfo;->signatures:\[Landroid/content/pm/Signature;\s*$"
)

SIG_MARK = "Lz/a/b;->a()"

# ---- 老版本残留的旧类名（com.fj.direct.*）迁移 -----------------------------------
# 为什么需要：smali 树是复用的（build.ps1 -SkipDecompile 不重新反编译），而补丁
# 本身是幂等的 —— 类名一改，新串在旧树上找不到匹配，旧的注入调用就会留在原地，
# 真机表现是启动即 java.lang.ClassNotFoundException: com.fj.direct.Boot 然后自杀。
# 所以每次打补丁都先做一次「旧 -> 新」的原地迁移。
LEGACY = [
    ("Lcom/fj/direct/Boot;->boot(Landroid/content/Context;)V", "Lz/a/a;->a(Landroid/content/Context;)V"),
    ("Lcom/fj/direct/Boot;->ensure()V", "Lz/a/a;->b()V"),
    ("Lcom/fj/direct/SigFix;->sigs()", "Lz/a/b;->a()"),
]


def migrate_legacy(path, dry_run):
    """把旧版注入的 com.fj.direct.* 调用改成新类名。返回改动条数。"""
    with open(path, "r", encoding="utf-8", newline="") as f:
        data = f.read()
    out = data
    n = 0
    for old, new in LEGACY:
        if old in out:
            n += out.count(old)
            out = out.replace(old, new)
    if n and not dry_run:
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(out)
    return n

# ---- 共存版：包名改名（清单侧由 tools/coexist.py 处理，两侧必须一致）--------------
PKG_OLD = "com.netease.dwrg"
PKG_NEW = "com.netease.dwrg.fj"

# 只改这几个**真正的包名字符串**。为什么不是全树替换前缀：
#   smali 里大量出现 Lcom/netease/dwrg/... 这种**类型描述符**，那是真实类名，
#   改了就会 NoClassDefFoundError。这里只做「带引号的完整字符串」精确替换。
PKG_STRINGS = [
    (PKG_OLD + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
     PKG_NEW + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
    (PKG_OLD + ".permission.ngpush",
     PKG_NEW + ".permission.ngpush"),
]


def patch_package_strings(path, dry_run, keep_package=False):
    if keep_package:
        return 0
    with open(path, "r", encoding="utf-8", newline="") as f:
        data = f.read()
    out = data
    n = 0
    for old, new in PKG_STRINGS:
        cnt = out.count('"' + old + '"')
        if cnt:
            out = out.replace('"' + old + '"', '"' + new + '"')
            n += cnt
    if n and not dry_run:
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(out)
    return n

# ---- API 28+ SigningInfo 链路 -------------------------------------------------
_REG = r"(?:p[0-9]+|v[0-9]+)"
_EOL = r"[ \t]*\r?\n"
# smali 里指令之间通常夹一个空行，_SEP 吃掉「一行结束 + 若干空行 + 下一行缩进」
_SEP = r"[ \t]*\r?\n(?:[ \t]*\r?\n)*[ \t]*"
_SIGNING_INFO_FIELD = (
    r"Landroid/content/pm/PackageInfo;->signingInfo:Landroid/content/pm/SigningInfo;"
)
_SIGS_INVOKE = "invoke-static {}, " + SIG_MARK + "[Landroid/content/pm/Signature;"

# 内联块 1：hasMultipleSigners() -> 常量 0（官方包只有一个签名者）
RE_HAS_MULTI = re.compile(
    r"^([ \t]*)iget-object (" + _REG + r"), " + _REG + r", " + _SIGNING_INFO_FIELD + _SEP
    + r"invoke-virtual \{\2\}, Landroid/content/pm/SigningInfo;->hasMultipleSigners\(\)Z" + _SEP
    + r"[ \t]*move-result \2" + _EOL,
    re.MULTILINE,
)


# 内联块 2/3：getApkContentsSigners() / getSigningCertificateHistory() -> z.a.b.a()
def _inline_signers_re(method):
    return re.compile(
        r"^([ \t]*)iget-object (" + _REG + r"), " + _REG + r", " + _SIGNING_INFO_FIELD + _SEP
        + r"invoke-virtual \{\2\}, Landroid/content/pm/SigningInfo;->" + method
        + r"\(\)\[Landroid/content/pm/Signature;" + _SEP
        + r"[ \t]*move-result-object \2" + _EOL,
        re.MULTILINE,
    )


RE_APK_SIGNERS = _inline_signers_re("getApkContentsSigners")
RE_CERT_HISTORY = _inline_signers_re("getSigningCertificateHistory")

# bridge 方法：ngplugin/common/C.smali 的 l/m/r
RE_BRIDGE = re.compile(
    r"(\.method public static bridge synthetic (?P<name>[lmr])\(Landroid/content/pm/SigningInfo;\)"
    r"(?P<ret>Z|\[Landroid/content/pm/Signature;)[ \t]*\r?\n)"
    + r"(?P<locals>[ \t]*\.locals [0-9]+[ \t]*\r?\n)"
    + r"(?:(?!\.end method).)*?"
    + r"(?P<end>[ \t]*\.end method[ \t]*\r?\n)",
    re.DOTALL,
)


def _rebuild_bridge(m, nl):
    """把 C.l/C.m/C.r 的桥接体整体换成常量返回。"""
    head, locals_line, end = m.group(1), m.group("locals"), m.group("end")
    if m.group("ret") == "Z":
        body = "    const/4 p0, 0x0" + nl + nl + "    return p0" + nl
    else:
        body = (
            "    " + _SIGS_INVOKE + nl + nl
            + "    move-result-object p0" + nl + nl
            + "    return-object p0" + nl
        )
    return head + locals_line + nl + body + end


def patch_signing_info(text, nl):
    """就地改写一段 smali 文本，返回 (新文本, 命中数)。"""
    hits = [0]

    def sub(rx, repl):
        text_new, n = rx.subn(repl, text)
        hits[0] += n
        return text_new

    text = sub(RE_HAS_MULTI, lambda m: m.group(1) + "const/4 " + m.group(2) + ", 0x0" + nl)
    for rx in (RE_APK_SIGNERS, RE_CERT_HISTORY):
        text = sub(
            rx,
            lambda m: m.group(1) + _SIGS_INVOKE + nl + nl
            + m.group(1) + "move-result-object " + m.group(2) + nl,
        )
    text = sub(RE_BRIDGE, lambda m: _rebuild_bridge(m, nl))
    return text, hits[0]


def patch_signing_info_file(path, dry_run):
    with open(path, "r", encoding="utf-8", newline="") as f:
        data = f.read()
    if "Landroid/content/pm/SigningInfo;->" not in data:
        return 0
    nl = "\r\n" if "\r\n" in data else "\n"
    new_data, n = patch_signing_info(data, nl)
    if n and not dry_run:
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(new_data)
    return n



def iter_smali(root):
    for dirpath, _dirnames, filenames in os.walk(root):
        for fn in filenames:
            if fn.endswith(".smali"):
                yield os.path.join(dirpath, fn)


def patch_signatures(path, dry_run):
    with open(path, "r", encoding="utf-8", newline="") as f:
        data = f.read()
    if SIG_MARK in data:
        return 0
    nl = "\r\n" if "\r\n" in data else "\n"
    out = []
    count = 0
    for line in data.splitlines(True):
        stripped = line.rstrip("\r\n")
        m = SIG_RE.match(stripped)
        if m:
            indent, dst = m.group(1), m.group(2)
            out.append(indent + "invoke-static {}, " + SIG_MARK + "[Landroid/content/pm/Signature;" + nl)
            out.append(nl)
            out.append(indent + "move-result-object " + dst + nl)
            count += 1
        else:
            out.append(line)
    if count and not dry_run:
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write("".join(out))
    return count


def patch_hooks(path, dry_run):
    with open(path, "r", encoding="utf-8", newline="") as f:
        data = f.read()
    nl = "\r\n" if "\r\n" in data else "\n"
    lines = data.splitlines(True)
    out = []

    # 幂等性关键：先整体扫一遍文件，判断两个钩子是否已经注入过。
    # 为什么不能边扫边判断：注入点（super 调用）在已注入的那一行**之前**，
    # 边扫边判断会导致第二次运行时在 super 行又插一次（曾产生 3 份重复调用）。
    done = set()
    for name, _rx, call in HOOKS:
        if call in data:
            done.add(name)

    for line in lines:
        out.append(line)
        stripped = line.rstrip("\r\n")
        for name, rx, call in HOOKS:
            if name in done:
                continue
            m = rx.match(stripped)
            if m:
                indent = m.group(1)
                out.append(nl)
                out.append(indent + call + nl)
                done.add(name)
    if len(done) == len(HOOKS) and not dry_run:
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write("".join(out))
    return done


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("smali_dir")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--keep-package", action="store_true",
                    help="不做共存改包名（保留 com.netease.dwrg）")
    args = ap.parse_args()

    root = args.smali_dir
    if not os.path.isdir(root):
        print("smali 目录不存在: " + root)
        return 2

    total_sig = 0
    total_siginfo = 0
    total_pkg = 0
    total_legacy = 0
    touched = 0
    for path in iter_smali(root):
        lg = migrate_legacy(path, args.dry_run)
        if lg:
            total_legacy += lg
            print("  旧类名迁移 %-2d 处 <- %s" % (lg, os.path.relpath(path, root)))
        n = patch_signatures(path, args.dry_run)
        if n:
            total_sig += n
            touched += 1
            rel = os.path.relpath(path, root)
            print("  签名点 %-2d 处 <- %s" % (n, rel))
        m = patch_signing_info_file(path, args.dry_run)
        if m:
            total_siginfo += m
            rel = os.path.relpath(path, root)
            print("  SigningInfo 点 %-2d 处 <- %s" % (m, rel))
        k = patch_package_strings(path, args.dry_run, args.keep_package)
        if k:
            total_pkg += k
            rel = os.path.relpath(path, root)
            print("  包名字符串 %-2d 处 <- %s" % (k, rel))

    proxy = os.path.join(root, PROXY_SMALI)
    hooked = set()
    if os.path.isfile(proxy):
        hooked = patch_hooks(proxy, args.dry_run)
    else:
        print("!! 找不到 " + PROXY_SMALI + "，注入点未打")

    print("签名点替换：%d 处（分布在 %d 个文件）" % (total_sig, touched))
    print("SigningInfo 点替换：%d 处" % total_siginfo)
    print("旧类名迁移：%d 处" % total_legacy)
    print("包名字符串替换：%d 处（%s）" % (total_pkg, "已跳过" if args.keep_package else PKG_OLD + " -> " + PKG_NEW))
    print("注入点：%s" % (", ".join(sorted(hooked)) if hooked else "无"))
    if args.dry_run:
        print("(--dry-run，未写盘)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
