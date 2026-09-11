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
    [switch]$OriginalPackage,
    # 排错构建：打开日志（logcat 输出 / 结果落盘）。默认 release 全静默。
    [switch]$DebugBuild,
    # 额外产出「遮挡探针」apk（dev-only，用来实证 NOT_TOUCHABLE 的效果）
    [switch]$Probe
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

# NDK：只用来编译我们自己的 libnrt.so（process_vm_readv 进程内自读，
# 因为 Android 10+ 的 SELinux 不让 app 打开 /proc/self/mem）
$Ndk = $env:ANDROID_NDK_HOME
if (-not $Ndk -or -not (Test-Path $Ndk)) {
    $cand = Get-ChildItem 'E:\Dev\tool\NDK' -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'toolchains\llvm\prebuilt') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($cand) { $Ndk = $cand.FullName }
}
if (-not $Ndk) { throw "找不到 NDK（编译 libnrt.so 需要）；设 ANDROID_NDK_HOME 或放到 E:\Dev\tool\NDK" }
$NdkBin    = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$NdkClang  = Join-Path $NdkBin 'aarch64-linux-android21-clang.cmd'
$NdkNm     = Join-Path $NdkBin 'llvm-nm.exe'
if (-not (Test-Path $NdkClang)) { throw "找不到 NDK clang：$NdkClang" }

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

    # resources.arsc 必须一起改：运行期 Resources.getIdentifier(name, type, getPackageName())
    # 是按**包名**在资源表里查的（AssetManager 用 defPackage 匹配 arsc 的 package 名）。
    # 清单包名改了而 arsc 没改 → 查到 0 → 紧接着 getString(0) 抛
    # Resources$NotFoundException，真机表现就是点开游戏黑屏、Launcher.onCreate 直接挂掉。
    # 包名是定长字段（ResTable_package.name[128]），原地覆盖，长度不变、零副作用。
    Step '3c' '改写 resources.arsc 的包名（定长字段原地覆盖）'
    $ArscPatch = Join-Path $Work 'resources.patched.arsc'
    & $Python -X utf8 (Join-Path $Root 'tools\coexist.py') patch-arsc $SrcApk $ArscPatch
    if ($LASTEXITCODE -ne 0) { throw 'coexist.py patch-arsc 失败' }
    $replaceArgs += '--replace'
    $replaceArgs += "resources.arsc=$ArscPatch"
    Ok $ArscPatch
}

# --------------------------------------------------------- 4. 回编译 classes.dex
Step 4 'smali -> classes.patched.dex'
$PatchedDex = Join-Path $Work 'classes.patched.dex'
& $Java -Xmx4g -cp $DexClasspath DexTool a $SmaliDir $PatchedDex 21
if ($LASTEXITCODE -ne 0) { throw 'smali 回编译失败' }
Ok ((Get-Item $PatchedDex).Length.ToString() + ' B')

# ------------------------------------------------- 5/6. 编译我们自己的 dex
Step 5 '字符串密文化（tools/obf_strings.py）'
# 为什么在源码层做：dex 的 string_data_item 是 uleb128 长度 + MUTF-8，原址改字节
# 既改不了长度、又容易写出非法 MUTF-8；改源码则一切由 javac/d8 保证合法，
# 而且 src/ 保持可读 —— 密文化后的副本只落在 work/obf-src。
$ObfSrc   = Join-Path $Work 'obf-src'
$ObfMap   = Join-Path $Work 'obf-strings.txt'
$ObfTest  = Join-Path $Work 'obf-test'
foreach ($d in @($ObfSrc, $ObfTest)) {
    if (Test-Path $d) { Remove-Item -LiteralPath $d -Recurse -Force }
}
& $Python -X utf8 (Join-Path $Root 'tools\obf_strings.py') (Join-Path $Root 'src') $ObfSrc `
    --map $ObfMap --test-src $ObfTest
if ($LASTEXITCODE -ne 0) { throw 'obf_strings.py 失败' }

# release 全静默：把日志门面的开关钉成 false；-DebugBuild 才打开
$LgSrc = Join-Path $ObfSrc 'z\a\i.java'
$lgText = [System.IO.File]::ReadAllText($LgSrc)
$lgNew = if ($DebugBuild) { 'public static final boolean ON = true;' }
         else { 'public static final boolean ON = false;' }
$lgText2 = $lgText -replace 'public static final boolean ON = (true|false);', $lgNew
if ($lgText2 -eq $lgText -and -not $DebugBuild) { Ok '日志开关已是 false' }
[System.IO.File]::WriteAllText($LgSrc, $lgText2, (New-Object System.Text.UTF8Encoding($false)))
$lgState = 'false（release 静默）'
if ($DebugBuild) { $lgState = 'true（Debug 构建，会打日志/写文件）' }
Ok ("日志开关 ON=" + $lgState)

Step '5b' 'javac 编译 work/obf-src（--release 8）'
$JavaClasses = Join-Path $Work 'java-classes'
if (Test-Path $JavaClasses) { Remove-Item -LiteralPath $JavaClasses -Recurse -Force }
$JavaSrc = Get-ChildItem $ObfSrc -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $Javac -encoding UTF-8 --release 8 -nowarn -cp $AndroidJar -d $JavaClasses $JavaSrc
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }
Ok "$($JavaSrc.Count) 个源文件"

Step '5c' '解码自检（JVM 上把每处密文解回来与原文逐条比对）'
# 这一步证明「Python 编码面」与「Java 解码面」100% 对称：任何一处漏改/错改都会在这里炸。
$TestClasses = Join-Path $Work 'obf-test-classes'
if (Test-Path $TestClasses) { Remove-Item -LiteralPath $TestClasses -Recurse -Force }
$TestSrc = Join-Path $ObfTest 'z\a\T.java'
& $Javac -encoding UTF-8 --release 8 -nowarn -cp $JavaClasses -d $TestClasses $TestSrc
if ($LASTEXITCODE -ne 0) { throw '自检类编译失败' }
& $Java -cp "$JavaClasses;$TestClasses" z.a.T
if ($LASTEXITCODE -ne 0) { throw '字符串编解码自检失败' }
Ok 'decode-check OK'

Step 6 'd8 -> classes13.dex'
$DexOut = Join-Path $Work 'dexout'
$Classes = Get-ChildItem $JavaClasses -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $D8 --min-api 21 --lib $AndroidJar --output $DexOut $Classes
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }
$ExtraDex = Join-Path $DexOut 'classes.dex'
Ok ((Get-Item $ExtraDex).Length.ToString() + ' B -> classes13.dex')

# ------------------------------------------------------------- 7. 重打包
Step '6b' 'NDK 编译 libnrt.so（process_vm_readv 进程内自读，免 root）'
# 与上一版的三点区别（都是为了不留下可被静态识别的特征）：
#   1) 文件名换成中性短名 libnrt.so，且不与原包任何 lib 重名；
#   2) 不再导出 Java_包名_类名_方法名（那个符号名本身即自曝），改用
#      JNI_OnLoad + RegisterNatives 动态绑定，动态符号表只剩 JNI_OnLoad；
#   3) -fvisibility=hidden + -Wl,-s，静态符号表也一并去掉。
$NativeDir = Join-Path $Work 'native'
if (-not (Test-Path $NativeDir)) { New-Item -ItemType Directory -Path $NativeDir -Force | Out-Null }
$LibName  = 'libnrt.so'
$MmReadSo = Join-Path $NativeDir $LibName
& $NdkClang -shared -fPIC -O2 -fvisibility=hidden '-Wl,--exclude-libs,ALL' '-Wl,-s' -Wall `
    -o $MmReadSo (Join-Path $Root 'src\native\nrt.c')
if ($LASTEXITCODE -ne 0) { throw "$LibName 编译失败" }
Ok ((Get-Item $MmReadSo).Length.ToString() + ' B')

$dynSyms = & $NdkNm --dynamic --defined-only $MmReadSo
$dynNames = $dynSyms | ForEach-Object { ($_ -split '\s+')[-1] } | Where-Object { $_ }
$badSyms = $dynNames | Where-Object { $_ -ne 'JNI_OnLoad' }
if ($badSyms) { throw ("$LibName 多出导出符号：" + ($badSyms -join ', ')) }
Ok ("导出符号只有 " + ($dynNames -join ', '))
& $Python -X utf8 (Join-Path $Root 'tools\check_stealth.py') so $MmReadSo
if ($LASTEXITCODE -ne 0) { throw "$LibName 字符串检查失败" }

Step 7 '重打包（保序保压缩方式，丢弃旧 v1 签名）'
$Unsigned = Join-Path $Work 'app-unsigned.apk'
$addArgs = @('--add', "lib/arm64-v8a/$LibName=$MmReadSo")
& $Python -X utf8 (Join-Path $Root 'tools\repack.py') $SrcApk $Unsigned `
    --classes-dex $PatchedDex --extra-dex "classes13.dex=$ExtraDex" --drop-v1-signature @replaceArgs @addArgs
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

Write-Host '  -- resources.arsc 包名校验 --'
$arscWant = if ($OriginalPackage) { 'com.netease.dwrg' } else { 'com.netease.dwrg.fj' }
& $Python -X utf8 -c @"
import sys, zipfile
sys.path.insert(0, r'$Root\tools')
import coexist
with zipfile.ZipFile(sys.argv[1]) as z:
    arsc = z.read('resources.arsc')
info = zipfile.ZipFile(sys.argv[1]).getinfo('resources.arsc')
off, name = coexist.find_arsc_package(arsc)
assert name == sys.argv[2], 'arsc 包名 %r != %r' % (name, sys.argv[2])
assert info.compress_type == 0, 'resources.arsc 必须 STORED，实际 method=%d' % info.compress_type
print('    resources.arsc 包名 = %s（STORED, %d B）OK' % (name, len(arsc)))
"@ $Final $arscWant
if ($LASTEXITCODE -ne 0) { throw 'resources.arsc 包名校验失败' }

Write-Host '  -- libnrt.so 校验（ELF + 包内条目）--'
& $Python -X utf8 -c @"
import sys, zipfile
entry = 'lib/arm64-v8a/' + sys.argv[2]
with zipfile.ZipFile(sys.argv[1]) as z:
    i = z.getinfo(entry)
    d = z.read(entry)
assert d[:4] == b'\x7fELF', d[:4]
assert d[18:20] == b'\xb7\x00', d[18:20]   # e_machine = EM_AARCH64(183)
print('    %s OK：%d B（包内 %d B，method=%d）' % (entry, len(d), i.compress_size, i.compress_type))
"@ $Final $LibName
if ($LASTEXITCODE -ne 0) { throw 'libnrt.so 校验失败' }

Write-Host '  -- 隐身化硬断言 --'
# 1) classes13.dex 里不许有任何品牌/玩法明文（字面量全部已密文化）
& $Python -X utf8 (Join-Path $Root 'tools\check_stealth.py') dex $extraCheck
if ($LASTEXITCODE -ne 0) { throw 'classes13.dex 明文特征检查失败' }
# 2) 我们的 9 个类名不许和官方 12 个 dex 里的任何内容撞车（撞了会 NoClassDefFoundError）
& $Python -X utf8 (Join-Path $Root 'tools\check_stealth.py') collide $SrcApk `
    'Lz/a/a;' 'Lz/a/b;' 'Lz/a/c;' 'Lz/a/d;' 'Lz/a/e;' 'Lz/a/f;' 'Lz/a/g;' 'Lz/a/h;' 'Lz/a/i;'
if ($LASTEXITCODE -ne 0) { throw '类名冲突检查失败' }
# 3) 新增的 so 条目不许和原包已有的 lib 重名（覆盖别人的库会直接崩）
& $Python -X utf8 (Join-Path $Root 'tools\check_stealth.py') libname $SrcApk "lib/arm64-v8a/$LibName"
if ($LASTEXITCODE -ne 0) { throw 'lib 重名检查失败' }

Write-Host "`n构建完成：$Final" -ForegroundColor Green
if ($OriginalPackage) {
    Write-Host "安装（与官方包冲突，必须先卸载官方包）：" -ForegroundColor Green
    Write-Host "  adb uninstall com.netease.dwrg" -ForegroundColor Green
    Write-Host "  adb install -r `"$Final`"" -ForegroundColor Green
} else {
    Write-Host "安装（共存版，可与官方包同时安装，无需卸载任何东西）：" -ForegroundColor Green
    Write-Host "  adb install -r `"$Final`"" -ForegroundColor Green
}
if ($DebugBuild) {
    Write-Host "  （这是 -DebugBuild 构建：会打 logcat / 写结果文件，别拿它当发布包）" -ForegroundColor Yellow
}

# --------------------------------------------------------------- 11. 遮挡探针
if ($Probe) {
    Step 11 '构建遮挡探针 apk（dev-only）'
    & pwsh -NoProfile -File (Join-Path $Root 'tools\build_probe.ps1')
    if ($LASTEXITCODE -ne 0) { throw '遮挡探针构建失败' }
}
