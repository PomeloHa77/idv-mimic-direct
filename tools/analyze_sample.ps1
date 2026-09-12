# 样本静态分析流水线：把一个「别人的改包」和官方原包对拍，直接产出报告。
#
# 用法：
#   pwsh -NoProfile -File tools\analyze_sample.ps1 -Official "E:\Dev\workspace\idv\netease_dwrg_20260903.apk" -Sample "E:\Dev\workspace\idv\samples\xxx.apk"
#
# 产物：work\sample-report-<时间戳>\report.md（附各步原始输出）
#
# 只读：不解压包体、不安装、不联网。全部动作都是读 zip 中央目录 + 调 aapt2/apksigner 静态解析。

param(
    [Parameter(Mandatory = $true)][string]$Official,
    [Parameter(Mandatory = $true)][string]$Sample,
    [string]$OutDir = "",
    [string]$BuildTools = "E:\Android\Sdk\build-tools\36.0.0"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent

# 子进程（python）与捕获输出的编码都锁 UTF-8，否则中文进报告会变乱码
$env:PYTHONIOENCODING = "utf-8"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$OutputEncoding = [System.Text.Encoding]::UTF8

if (-not (Test-Path -LiteralPath $Official)) { throw "找不到官方包：$Official" }
if (-not (Test-Path -LiteralPath $Sample)) { throw "找不到样本包：$Sample" }
if (-not $OutDir) { $OutDir = Join-Path $repo ("work\sample-report-" + (Get-Date -Format "yyyyMMdd-HHmmss")) }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$report = Join-Path $OutDir "report.md"
Set-Content -Path $report -Value ("# 样本对照报告`n`n- 官方包：``$Official```n- 样本包：``$Sample```n- 生成时间：" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "`n") -Encoding utf8

function Section([string]$title, [string]$body) {
    Write-Host "`n== $title"
    Add-Content -Path $report -Value ("`n## $title`n`n``````" + "`n" + $body + "`n``````") -Encoding utf8
    if ($body.Length -lt 4000) { Write-Host $body }
}

function RunCapture([string]$exe, [string[]]$argv) {
    try {
        $o = & $exe @argv 2>&1 | Out-String
    } catch { $o = "运行失败：" + $_.Exception.Message }
    return $o.Trim()
}

# ---- 0. 存证 ----
$sha = (Get-FileHash -LiteralPath $Sample -Algorithm SHA256).Hash.ToLower()
$sizeMB = [math]::Round((Get-Item -LiteralPath $Sample).Length / 1MB, 2)
Section "0. 存证" ("sample sha256 = $sha`nsample size   = $sizeMB MB`n（先复制一份只读副本再开工；此哈希写进报告备查）")

# ---- 1. 包身份 / 清单权限 ----
$aapt2 = Join-Path $BuildTools "aapt2.exe"
$badging = RunCapture $aapt2 @("dump", "badging", $Sample)
Set-Content -Path (Join-Path $OutDir "badging.txt") -Value $badging -Encoding utf8
$brief = ($badging -split "`n" | Where-Object { $_ -match "^package:|^application-label:|^uses-permission|^sdkVersion|^targetSdkVersion|^native-code" }) -join "`n"
Section "1. 样本包身份（aapt2 dump badging）" $brief

# ---- 2. 签名证书 ----
$apksigner = Join-Path $BuildTools "apksigner.bat"
if (-not (Test-Path -LiteralPath $apksigner)) { $apksigner = Join-Path $BuildTools "apksigner" }
$sigO = RunCapture $apksigner @("verify", "--print-certs", "-v", $Sample)
$sigA = RunCapture $apksigner @("verify", "--print-certs", "-v", $Official)
Set-Content -Path (Join-Path $OutDir "signature-sample.txt") -Value $sigO -Encoding utf8
Section "2. 签名（样本 / 官方）" ($sigO + "`n`n--- 官方 ---`n" + $sigA)

# ---- 3. 条目级差异 ----
$cmp = RunCapture "python" @((Join-Path $PSScriptRoot "compare_apks.py"), $Official, $Sample, "--json", (Join-Path $OutDir "diff.json"))
Set-Content -Path (Join-Path $OutDir "compare.txt") -Value $cmp -Encoding utf8
Section "3. 条目级差异（compare_apks.py）" $cmp

# ---- 4. 反外挂第一现场：so 集合 + assets 清单 ----
$py = @'
import zipfile, sys, re
official, sample = sys.argv[1], sys.argv[2]
def inv(p):
    z = zipfile.ZipFile(p); d = {}
    for i in z.infolist():
        d[i.filename] = (i.file_size, i.CRC)
    z.close(); return d
a, b = inv(official), inv(sample)
print("-- lib/ 条目集合差异 --")
la = {k: v for k, v in a.items() if k.startswith("lib/")}
lb = {k: v for k, v in b.items() if k.startswith("lib/")}
print("官方 so 数={}  样本 so 数={}".format(len(la), len(lb)))
for k in sorted(set(lb) - set(la)): print("  + {}".format(k))
for k in sorted(set(la) - set(lb)): print("  - {}".format(k))
for k in sorted(set(la) & set(lb)):
    if la[k] != lb[k]: print("  ~ {}  官方={} 样本={}".format(k, la[k], lb[k]))
print("\n-- 反外挂资产（assets）--")
for key in ("assets/ntunisdk_so_uuids", "assets/probeSoMd5Record.txt", "assets/emulatordetector_data"):
    fa, fb = a.get(key), b.get(key)
    if fa is None and fb is None: print("  {} : 两边都没有".format(key)); continue
    if fa is None: print("  {} : 官方没有、样本有 {}".format(key, fb)); continue
    if fb is None: print("  {} : 官方有、样本删了".format(key)); continue
    print("  {} : {} 官方={} 样本={}".format(key, "相同" if fa == fb else "**内容变了**", fa, fb))
print("\n-- 顶层 dex 条目 --")
for k in sorted(set(x for x in a if re.match(r"^classes\d*\.dex$", x)) | set(x for x in b if re.match(r"^classes\d*\.dex$", x))):
    fa, fb = a.get(k), b.get(k)
    tag = "同名同内容" if fa == fb else ("新增" if fa is None else ("删除" if fb is None else "内容变化"))
    print("  {} : {}  官方={} 样本={}".format(k, tag, fa, fb))
'@
$pyPath = Join-Path $OutDir "_libs_assets.py"
Set-Content -Path $pyPath -Value $py -Encoding utf8
$libs = RunCapture "python" @($pyPath, $Official, $Sample)
Section "4. 反外挂第一现场（so 集合 / assets 清单 / dex 条目）" $libs

# ---- 5. 结论占位与后续动作 ----
$todo = @(
    "- [ ] 样本是自签还是官方证书？（看第 2 节）→ 自签 = 与我们的方案同一类；官方证书 = 它没重打包",
    "- [ ] lib/ 里既有 so 有没有被改/被删（尤其 libsec-lib.so / libenvsdk.so）→ 改了说明反外挂本体可以碰",
    "- [ ] assets/ntunisdk_so_uuids、probeSoMd5Record.txt 有没有被改/被删 → 踩了第一现场还活着，说明我们的两个不变量该重写",
    "- [ ] dex 是新增条目还是原地替换同名条目 → 后者对 ZipPrint.getApkHash 影响更小",
    "- [ ] jadx 打开样本，搜 postDeviceData / matchConfigInfo / DiInfo / applicationInfoStr → 上报链有没有被删/被短路",
    "- [ ] jadx 搜 PackageInfo;->signatures → 它的签名回填做了几处（和我们 12 处对照）",
    "- [ ] 结论归到 A（自签+动反外挂/掐上报）/ B（自签但没多动）/ C（根本不是改包）哪一类"
) -join "`n"
Section "5. 后续动作清单" $todo

Write-Host "`n报告：$report"
