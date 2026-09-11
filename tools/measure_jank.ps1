<#
  掉帧 / jank 量尺（dev-only，不进发布包）。

  为什么用 SurfaceFlinger --latency 而不是 gfxinfo：游戏画面是 Unity 的 SurfaceView，
  gfxinfo 只统计 View 层的绘制，看不到游戏自己的帧。--latency 给的是这一层最近
  127 帧的 present 时间戳，正好够算「帧间隔」和「掉了一帧（≈33 ms）」的次数。

  用法（对局里跑最有意义；面板显示 / 收起可以各测一遍做 A/B）：
    pwsh -File tools\measure_jank.ps1 -Seconds 30
    pwsh -File tools\measure_jank.ps1 -Seconds 30 -TriggerScan     # 顺便触发一次扫描
    pwsh -File tools\measure_jank.ps1 -Seconds 30 -Layer m4399     # 测官方客户端做对照

  判读：
    * 「中位帧间隔」≈16.6 ms = 60 fps，≈33.2 ms = 游戏当前锁 30 fps（登录页就是这样）；
    * 「掉帧」= 帧间隔超过 max(25 ms, 1.7 × 中位) 的次数，去重后计数；
    * 30 fps 的档位天然有 33 ms 余量，掉帧会少，要严格对比请在同一档位下测。
#>
param(
  [int]$Seconds = 30,
  [string]$Layer = 'dwrg.fj',
  [switch]$TriggerScan
)
$adb = Join-Path 'E:\Android\Sdk\platform-tools' 'adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }
$layer = (& $adb shell "dumpsys SurfaceFlinger --list | grep -i '$Layer' | grep BLAST" | Select-Object -First 1)
if (-not $layer) { throw "找不到匹配 '$Layer' 的 BLAST 图层，游戏没在前台？" }
$layer = $layer.Trim()
$hitches = @{}
$gaps = New-Object System.Collections.Generic.List[double]
$pid_ = (& $adb shell 'pidof com.netease.dwrg.fj').Trim()
$t0 = Get-Date
if ($TriggerScan) {
  & $adb shell "su -c 'sendevent /dev/input/event4 1 115 1; sendevent /dev/input/event4 0 0 0; sleep 0.15; sendevent /dev/input/event4 1 115 0; sendevent /dev/input/event4 0 0 0'" | Out-Null
  Write-Output '已触发一次扫描（音量加短按）'
}
while (((Get-Date) - $t0).TotalSeconds -lt $Seconds) {
  $raw = & $adb shell "dumpsys SurfaceFlinger --latency `"$layer`""
  $ts = New-Object System.Collections.Generic.List[double]
  foreach ($line in ($raw -split "`n")) {
    $s = $line.Trim(); if ($s -eq '') { continue }
    $p = $s -split '\s+'; if ($p.Length -ne 3) { continue }
    $v = 0.0
    if ([double]::TryParse($p[1], [ref]$v) -and $v -gt 0 -and $v -lt 1e17) { $ts.Add($v) }
  }
  for ($i = 1; $i -lt $ts.Count; $i++) { $gaps.Add(($ts[$i] - $ts[$i - 1]) / 1e6) }
  $sortedNow = $gaps | Sort-Object
  $thr = [Math]::Max(25.0, $sortedNow[[int]($sortedNow.Count / 2)] * 1.7)
  for ($i = 1; $i -lt $ts.Count; $i++) {
    $g = ($ts[$i] - $ts[$i - 1]) / 1e6
    if ($g -gt $thr) { $hitches[[string]$ts[$i]] = $g }
  }
}
$sorted = $gaps | Sort-Object
$med = $sorted[[int]($sorted.Count / 2)]
Write-Output ("图层      : {0}" -f $layer)
Write-Output ("中位帧间隔: {0:N2} ms  (≈{1:N0} fps)   p95={2:N2} ms   最慢={3:N1} ms" -f $med, (1000 / $med), $sorted[[int]($sorted.Count * 0.95)], $sorted[-1])
Write-Output ("掉帧事件  : {0} 次 / {1} s" -f $hitches.Count, $Seconds)
if ($pid_) {
  Write-Output '进程内线程 CPU 前几名:'
  (& $adb shell "top -H -b -n 1 -p $pid_ | tail -6") | ForEach-Object { "  $_" }
}