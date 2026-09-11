<#
  构建「遮挡探针」apk（dev-only，不进发布包、不进仓库）。

  为什么需要它：我们声称「悬浮窗设 FLAG_NOT_TOUCHABLE 后，游戏侧收到的触摸事件不再
  带 FLAG_WINDOW_IS_OBSCURED」。这句话只能实测 —— 探针就是那个实测工具：
  它自己在屏幕中间盖一个悬浮窗，可切换 可触摸 / 不可触摸，然后把收到的
  MotionEvent.flags 打到屏幕和 logcat 上。

  用法：
    pwsh -File tools/build_probe.ps1
    产物：out/probe-overlay.apk
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.Encoding]::UTF8
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$JavaHome = $env:JAVA_HOME
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    $jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $JavaHome = $jdk.FullName }
}
if (-not $JavaHome) { throw '找不到 JDK' }
$Java  = Join-Path $JavaHome 'bin\java.exe'
$Javac = Join-Path $JavaHome 'bin\javac.exe'

$BuildTools = 'E:\Android\Sdk\build-tools\36.0.0'
$Aapt2     = Join-Path $BuildTools 'aapt2.exe'
$D8        = Join-Path $BuildTools 'd8.bat'
$Apksigner = Join-Path $BuildTools 'apksigner.bat'
$Zipalign  = Join-Path $BuildTools 'zipalign.exe'
$AndroidJar = 'E:\Android\Sdk\platforms\android-35\android.jar'
$Keystore  = Join-Path $Root 'libs\direct.keystore'

$Work = Join-Path $Root 'work\probe'
$Out  = Join-Path $Root 'out'
foreach ($d in @($Work, $Out, (Join-Path $Work 'classes'), (Join-Path $Work 'dex'))) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
}

Write-Host "`n=== [1] javac 探针源码 ===" -ForegroundColor Cyan
$src = Get-ChildItem (Join-Path $Root 'tools\probe\src') -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& $Javac -encoding UTF-8 --release 8 -nowarn -cp $AndroidJar -d (Join-Path $Work 'classes') $src
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Write-Host "`n=== [2] d8 -> classes.dex ===" -ForegroundColor Cyan
$cls = Get-ChildItem (Join-Path $Work 'classes') -Recurse -Filter *.class |
    ForEach-Object { $_.FullName }
& $D8 --min-api 21 --lib $AndroidJar --output (Join-Path $Work 'dex') $cls
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Write-Host "`n=== [3] aapt2 link + 塞入 dex ===" -ForegroundColor Cyan
$Unsig = Join-Path $Work 'probe-unsigned.apk'
if (Test-Path $Unsig) { Remove-Item -LiteralPath $Unsig -Force }
& $Aapt2 link --manifest (Join-Path $Root 'tools\probe\AndroidManifest.xml') `
    -I $AndroidJar -o $Unsig --min-sdk-version 21 --target-sdk-version 30 --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }
& py -X utf8 -c @"
import sys, zipfile, shutil
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + '.tmp'
with zipfile.ZipFile(apk) as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    zout.write(dex, 'classes.dex')
shutil.move(tmp, apk)
print('    classes.dex 已写入', apk)
"@ $Unsig (Join-Path $Work 'dex\classes.dex')
if ($LASTEXITCODE -ne 0) { throw '打包 dex 失败' }

Write-Host "`n=== [4] 对齐 + 签名 ===" -ForegroundColor Cyan
$Aligned = Join-Path $Work 'probe-aligned.apk'
& $Zipalign -f -p 4 $Unsig $Aligned 2>$null
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }
$Final = Join-Path $Out 'probe-overlay.apk'
if (Test-Path $Final) { Remove-Item -LiteralPath $Final -Force }
& $Apksigner sign --ks $Keystore --ks-pass 'pass:fjdirect' --key-pass 'pass:fjdirect' `
    --ks-key-alias fjdirect --v1-signing-enabled true --v2-signing-enabled true `
    --v3-signing-enabled false --min-sdk-version 21 --max-sdk-version 36 --out $Final $Aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }
& $Apksigner verify -v $Final | Where-Object { $_ -notmatch '^WARNING' }

Write-Host "`n探针就绪：$Final" -ForegroundColor Green
Write-Host @"
用法（真机）：
  adb install -r "$Final"
  adb shell 'su -c "appops set com.fj.probe SYSTEM_ALERT_WINDOW allow"'
  adb shell am start -n com.fj.probe/.M
  # 点按钮切到「不可触摸」，再点屏幕中间（悬浮窗范围内）：
  adb shell 'su -c "input tap 540 1170"'
  adb logcat -d -s FJPROBE      # 看 flags：无 OBSCURED = 我们的方案成立
"@ -ForegroundColor Green