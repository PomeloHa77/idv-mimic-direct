<#
  第五人格「模仿者看身份」直装版 —— 一键构建流水线

  为什么这么建：官方包 1.88 GB，绝大多数体积是资源（.wpk/.npk，很多是 STORED）。
  所以全程只做「最小改动」——只换 classes.dex、只加 classes13.dex，其余条目按
  原始字节搬运，条目顺序和压缩方式都要保住，否则游戏资源加载/反外挂校验会崩。

  用法：
    pwsh -File build.ps1                       # 完整构建（默认 v1+v2 签名，与原包一致）
    pwsh -File build.ps1 -SkipDecompile        # 复用 work\smali，只重跑编译/打包/签名
    pwsh -File build.ps1 -V2Only               # 只做 v2 签名（安装要求 Android 7+）
#>
[CmdletBinding()]
param(
    [string]$SrcApk = "E:\Dev\workspace\idv\netease_dwrg_20260903.apk",
    [string]$OutName = "",
    [switch]$SkipDecompile,
    [switch]$V2Only,
    # 共存版：把包名改成 com.netease.dwrg.fj，可与官方包同时安装（默认）
    [switch]$OriginalPackage
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$OutputEncoding = [System.Text.Encoding]::UTF8

# ---------------------------------------------------------------- 工具链定位
$Root = $PSScriptRoot
Set-Location $Root

function Find-Tool {
    param([string]$Name, [string[]]$Candidates)
    foreach ($c in $Candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "找不到 $Name，请安装或在脚本顶部补路径"
}

$JavaHome = $env:JAVA_HOME
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    $jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $JavaHome = $jdk.FullName }
}
if (-not $JavaHome) { throw "找不到 JDK（需要 JDK 17）；请设置 JAVA_HOME" }

$Java   = Join-Path $JavaHome 'bin\java.exe'
$Javac  = Join-Path $JavaHome 'bin\javac.exe'
$Keytool = Join-Path $JavaHome 'bin\keytool.exe'

$BuildTools = 'E:\Android\Sdk\build-tools\36.0.0'
if (-not (Test-Path $BuildTools)) {
    $bt = Get-ChildItem 'E:\Android\Sdk\build-tools' -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($bt) { $BuildTools = $bt.FullName }
}
$Aapt2     = Find-Tool 'aapt2'     @(Join-Path $BuildTools 'aapt2.exe')
$D8        = Find-Tool 'd8.bat'    @(Join-Path $BuildTools 'd8.bat')
$Apksigner = Find-Tool 'apksigner.bat' @(Join-Path $BuildTools 'apksigner.bat')
$Zipalign  = Find-Tool 'zipalign'  @(Join-Path $BuildTools 'zipalign.exe')
$Dexdump   = Find-Tool 'dexdump'   @(Join-Path $BuildTools 'dexdump.exe')

$AndroidJar = 'E:\Android\Sdk\platforms\android-35\android.jar'
if (-not (Test-Path $AndroidJar)) { throw "找不到 android.jar：$AndroidJar" }

$Python = 'py'

$ApktoolJar = Join-Path $Root 'libs\apktool_2.9.3.jar'
if (-not (Test-Path $ApktoolJar)) { throw "缺少 $ApktoolJar（见 README 的下载地址与 SHA256）" }
if (-not (Test-Path $SrcApk))     { throw "缺少原始 APK：$SrcApk" }

$Keystore = Join-Path $Root 'libs\direct.keystore'
$KsAlias  = 'fjdirect'
$KsPass   = 'fjdirect'

if (-not $OutName) {
    $OutName = if ($OriginalPackage) { "第五人格-直装版-2026.0828.1653.apk" }
               else { "第五人格-共存版-2026.0828.1653.apk" }
}

$Work = Join-Path $Root 'work'
$Out  = Join-Path $Root 'out'
foreach ($d in @($Work, $Out, (Join-Path $Work 'smali'), (Join-Path $Work 'java-classes'),
                 (Join-Path $Work 'dexout'))) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
}

$ToolsBuild = Join-Path $Work 'tools-build'
if (-not (Test-Path $ToolsBuild)) { New-Item -ItemType Directory -Path $ToolsBuild -Force | Out-Null }

function Step($n, $msg) { Write-Host "`n=== [$n] $msg ===" -ForegroundColor Cyan }
function Ok($msg)       { Write-Host "    OK  $msg" -ForegroundColor Green }

# ---------------------------------------------------------- 0. 编译 dex 工具
Step 0 '编译 DexTool（驱动 apktool 内置 smali/baksmali）'
& $Javac -encoding UTF-8 -nowarn -cp $ApktoolJar -d $ToolsBuild (Join-Path $Root 'tools\DexTool.java')
if ($LASTEXITCODE -ne 0) { throw 'DexTool 编译失败' }
$DexClasspath = "$ApktoolJar;$ToolsBuild"
Ok 'DexTool.class'

# ------------------------------------------------------------- 1. 抽取 classes.dex
Step 1 '从原包抽取 classes.dex'
$OrigDex = Join-Path $Work 'classes.dex'
& $Python -X utf8 -c @"
import sys, zipfile
apk, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z, open(dst, 'wb') as f:
    f.write(z.read('classes.dex'))
print('    classes.dex ->', dst)
"@ $SrcApk $OrigDex
if ($LASTEXITCODE -ne 0) { throw '抽取 classes.dex 失败' }

# --------------------------------------------------------- 2. baksmali 反编译
$SmaliDir = Join-Path $Work 'smali'
if ($SkipDecompile -and (Test-Path (Join-Path $SmaliDir 'com'))) {
    Step 2 '反编译（-SkipDecompile：复用已有 smali 树）'
    Ok $SmaliDir
} else {
    Step 2 '反编译 classes.dex -> smali（约 40 s）'
    & $Java -Xmx4g -cp $DexClasspath DexTool d $SrcApk $SmaliDir classes.dex 21
    if ($LASTEXITCODE -ne 0) { throw 'baksmali 失败' }
    Ok $SmaliDir
}

# --------------------------------------------------------------- 3. 打补丁
Step 3 '打补丁（注入入口 + 签名回填 + 共存包名）'
$pkgArgs = @()
if ($OriginalPackage) { $pkgArgs += '--keep-package' }
& $Python -X utf8 (Join-Path $Root 'tools\patch_dex.py') $SmaliDir @pkgArgs
if ($LASTEXITCODE -ne 0) { throw 'patch_dex.py 失败' }

# 共存版必须同时改清单：package 属性 + 全部 authorities + <permission> 声明。
# 只改 dex 没用；只改 manifest 的 package 而不改 authorities 会让第二个包
# 装不上（INSTALL_FAILED_CONFLICTING_PROVIDER）。
$replaceArgs = @()
if (-not $OriginalPackage) {
    Step '3b' '改写 AndroidManifest.xml（共存版包名）'
    $ManifestPatch = Join-Path $Work 'AndroidManifest.patched.xml'
    & $Python -X utf8 (Join-Path $Root 'tools\coexist.py') patch $SrcApk $ManifestPatch
    if ($LASTEXITCODE -ne 0) { throw 'coexist.py 失败' }
    $replaceArgs += '--replace'
    $replaceArgs += "AndroidManifest.xml=$ManifestPatch"
    Ok $ManifestPatch
}

# --------------------------------------------------------- 4. 回编译 classes.dex
Step 4 'smali -> classes.patched.dex'
$PatchedDex = Join-Path $Work 'classes.patched.dex'
& $Java -Xmx4g -cp $DexClasspath DexTool a $SmaliDir $PatchedDex 21
if ($LASTEXITCODE -ne 0) { throw 'smali 回编译失败' }
Ok ((Get-Item $PatchedDex).Length.ToString() + ' B')

# ------------------------------------------------- 5/6. 编译我们自己的 dex
Step 5 'javac 编译 src/com/fj/direct（--release 8）'
$JavaClasses = Join-Path $Work 'java-classes'
$JavaSrc = Get-ChildItem (Join-Path $Root 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $Javac -encoding UTF-8 --release 8 -nowarn -cp $AndroidJar -d $JavaClasses $JavaSrc
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }
Ok "$($JavaSrc.Count) 个源文件"

Step 6 'd8 -> classes13.dex'
$DexOut = Join-Path $Work 'dexout'
$Classes = Get-ChildItem $JavaClasses -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $D8 --min-api 21 --lib $AndroidJar --output $DexOut $Classes
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }
$ExtraDex = Join-Path $DexOut 'classes.dex'
Ok ((Get-Item $ExtraDex).Length.ToString() + ' B -> classes13.dex')

# ------------------------------------------------------------- 7. 重打包
Step 7 '重打包（保序保压缩方式，丢弃旧 v1 签名）'
$Unsigned = Join-Path $Work 'app-unsigned.apk'
& $Python -X utf8 (Join-Path $Root 'tools\repack.py') $SrcApk $Unsigned `
    --classes-dex $PatchedDex --extra-dex "classes13.dex=$ExtraDex" --drop-v1-signature @replaceArgs
if ($LASTEXITCODE -ne 0) { throw 'repack 失败' }

# ------------------------------------------------------------- 8. zipalign
Step 8 'zipalign -p 4（resources.arsc 必须 4 字节对齐）'
$Aligned = Join-Path $Work 'app-aligned.apk'
& $Zipalign -f -p 4 $Unsigned $Aligned 2>$null
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }
$check = & $Zipalign -c -v 4 $Aligned 2>$null | Select-String 'Verification successful'
if (-not $check) { throw 'zipalign 校验未通过' }
Ok 'Verification successful'

# --------------------------------------------------------------- 9. 签名
if (-not (Test-Path $Keystore)) {
    Step '9a' '生成签名密钥（仅首次）'
    & $Keytool -genkeypair -v -keystore $Keystore -alias $KsAlias `
        -keyalg RSA -keysize 2048 -validity 10950 `
        -storetype PKCS12 -storepass $KsPass -keypass $KsPass `
        -dname 'CN=fjdirect, OU=fjdirect, O=fjdirect, L=hz, ST=zj, C=cn'
    if ($LASTEXITCODE -ne 0) { throw 'keytool 失败' }
}

Step 9 'apksigner 签名'
$Final = Join-Path $Out $OutName
if (Test-Path $Final) { Remove-Item -LiteralPath $Final -Force }
$v1 = if ($V2Only) { 'false' } else { 'true' }
& $Apksigner sign --ks $Keystore --ks-pass "pass:$KsPass" --key-pass "pass:$KsPass" `
    --ks-key-alias $KsAlias `
    --v1-signing-enabled $v1 --v2-signing-enabled true --v3-signing-enabled false `
    --min-sdk-version 21 --max-sdk-version 36 `
    --out $Final $Aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }
Ok $Final

# --------------------------------------------------------------- 10. 校验
Step 10 '三重校验'
Write-Host '  -- apksigner verify --'
& $Apksigner verify -v --print-certs $Final | Where-Object { $_ -notmatch '^WARNING' }
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify 失败' }

Write-Host '  -- zipalign -c --'
$z = & $Zipalign -c -v 4 $Final 2>$null | Select-String 'Verification successful'
if (-not $z) { throw 'zipalign -c 失败' }
Write-Host '  Verification successful'

Write-Host '  -- aapt2 dump badging（包名 / 版本 / 权限）--'
$badging = & $Aapt2 dump badging $Final
$badging | Select-String "^package:|SYSTEM_ALERT_WINDOW|sdkVersion|targetSdkVersion|native-code"
$pkgLine = ($badging | Select-String "^package: name='([^']+)'").Matches.Groups[1].Value
Write-Host ("  实际包名: " + $pkgLine)
if ($OriginalPackage) {
    if ($pkgLine -ne 'com.netease.dwrg') { throw "包名应为 com.netease.dwrg，实际 $pkgLine" }
} else {
    if ($pkgLine -ne 'com.netease.dwrg.fj') { throw "共存版包名应为 com.netease.dwrg.fj，实际 $pkgLine" }
}

# 共存性的硬指标：两个包的 authorities 与自定义 <permission> 不能有交集，
# 否则第二个包会 INSTALL_FAILED_CONFLICTING_PROVIDER / DUPLICATE_PERMISSION。
Write-Host '  -- 与官方包共存性检查 --'
function Get-ManifestFacts([string]$Apk) {
    $tree = & $Aapt2 dump xmltree --file AndroidManifest.xml $Apk
    $auth = New-Object System.Collections.Generic.List[string]
    $perm = New-Object System.Collections.Generic.List[string]
    $pkg = ''
    for ($i = 0; $i -lt $tree.Count; $i++) {
        if ($tree[$i] -match 'android:authorities\(0x01010018\)="([^"]*)"') { $auth.Add($Matches[1]) }
        if ($tree[$i] -match '\bA: package="([^"]*)"') { $pkg = $Matches[1] }
        if ($tree[$i] -match '^ {6}E: permission \(line=') {
            for ($j = $i + 1; $j -lt [Math]::Min($i + 5, $tree.Count); $j++) {
                if ($tree[$j] -match 'android:name\(0x01010003\)="([^"]*)"') { $perm.Add($Matches[1]); break }
            }
        }
    }
    [pscustomobject]@{ Package = $pkg; Authorities = $auth; Permissions = $perm }
}
$nw = Get-ManifestFacts $Final
$og = Get-ManifestFacts $SrcApk
Write-Host ("  官方 authorities {0} 条 / 自定义 permission {1} 条" -f $og.Authorities.Count, $og.Permissions.Count)
Write-Host ("  新包 authorities {0} 条 / 自定义 permission {1} 条" -f $nw.Authorities.Count, $nw.Permissions.Count)
$bad = @()
foreach ($a in $nw.Authorities) { if ($og.Authorities -contains $a) { $bad += "authority 冲突: $a" } }
foreach ($p in $nw.Permissions) { if ($og.Permissions -contains $p) { $bad += "permission 冲突: $p" } }
if ($bad.Count) { $bad | ForEach-Object { Write-Host ("  !! " + $_) }; throw '共存性检查失败' }
Write-Host '  无交集 OK'

Write-Host '  -- dex 校验 --'
$extraCheck = Join-Path $Work 'check-classes13.dex'
& $Python -X utf8 -c @"
import sys, zipfile
apk, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z, open(dst, 'wb') as f:
    f.write(z.read('classes13.dex'))
d = open(dst, 'rb').read()
assert d[:8] == b'dex\n035\x00', d[:8]
print('    classes13.dex magic OK, size=%d' % len(d))
with zipfile.ZipFile(apk) as z:
    print('    条目数 = %d' % len(z.namelist()))
"@ $Final $extraCheck
if ($LASTEXITCODE -ne 0) { throw 'classes13.dex 校验失败' }
& $Dexdump -f $extraCheck | Select-String 'Opened|header' | Select-Object -First 3

Write-Host "`n构建完成：$Final" -ForegroundColor Green
if ($OriginalPackage) {
    Write-Host "安装（与官方包冲突，必须先卸载官方包）：" -ForegroundColor Green
    Write-Host "  adb uninstall com.netease.dwrg" -ForegroundColor Green
    Write-Host "  adb install -r `"$Final`"" -ForegroundColor Green
} else {
    Write-Host "安装（共存版，可与官方包同时安装，无需卸载任何东西）：" -ForegroundColor Green
    Write-Host "  adb install -r `"$Final`"" -ForegroundColor Green
}
