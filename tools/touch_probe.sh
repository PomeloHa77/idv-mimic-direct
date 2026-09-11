#!/system/bin/sh
# 第五人格「模仿者看身份」共存版 · 触摸命中探针（dev-only，需 root）
#
# 目的：证明「常态（锁定）下悬浮窗不抢游戏触摸」，以及「解锁触摸的 10 s 内会抢」。
# 原理：dumpsys input 的 TouchStatesByDisplay 段会列出这一次触摸实际命中了哪些窗口
#       （InputTarget 列表 + 每个窗口的 targetFlags）。
#
# 用法（在设备上执行，需要 root）：
#   adb push tools/touch_probe.sh /data/local/tmp/
#   adb shell 'su -c "sh /data/local/tmp/touch_probe.sh state"'     # 只打印悬浮窗的 inputConfig/frame
#   adb shell 'su -c "sh /data/local/tmp/touch_probe.sh locked"'    # 锁定态：面板矩形内长按一下
#   adb shell 'su -c "sh /data/local/tmp/touch_probe.sh drag"'      # 锁定态：从面板内拖到面板外
#   adb shell 'su -c "sh /data/local/tmp/touch_probe.sh unlocked"'  # 解锁触摸 10 s 内点面板矩形内
#
# 键位（Redmi K20 Pro / MIUI 14 / Android 13 实测；换机型请先用 getevent -pl 找）：
#   音量加 = /dev/input/event4 键码 115
#   音量减 = /dev/input/event0 键码 114
#
# 判读：
#   - 命中 `<hex> com.netease.dwrg.fj`（**没有**斜杠）= 命中我们的悬浮窗 → 这一次游戏收不到触摸（= 抢）
#   - 命中 `.../com.netease.dwrg.Client` / `...MpayLoginActivity` = 命中游戏 → 没抢
#   - 对照组：同版本官方客户端 com.netease.dwrg.m4399 在**没有任何悬浮窗**时，
#     游戏窗口的 targetFlags 与带面板时逐位一致（本机 0x105）→ 我们没给游戏侧触摸打遮挡标记。

PKG=com.netease.dwrg.fj
UP=/dev/input/event4
DN=/dev/input/event0
UPKEY=115
DNKEY=114
X=${X:-546}          # 面板矩形内的点（本机面板在左上角 688×89 / 解锁时 688×967）
Y=${Y:-368}

volup() { /system/bin/sendevent $UP 1 $UPKEY $1; /system/bin/sendevent $UP 0 0 0; }
voldn() { /system/bin/sendevent $DN 1 $DNKEY $1; /system/bin/sendevent $DN 0 0 0; }

ovl() {
  dumpsys input | grep "name='[0-9a-f]* $PKG'," \
      | sed 's/.*inputConfig=/ovl inputConfig=/;s/, globalScale.*//'
}

hit() {
  /system/bin/input swipe $1 $2 $3 $4 4000 >/dev/null 2>&1 &
  SW=$!
  sleep 1.0
  dumpsys input | sed -n '/TouchStatesByDisplay/,/Display: 0/p' \
      | grep -E "down=|name=" | sed 's/^ */  /'
  wait $SW
}

case "$1" in
  state)
    ovl
    ;;
  unlocked)
    voldn 1; sleep 6; voldn 0; sleep 0.7
    ovl; echo "--- 解锁期内，在面板矩形内长按："
    hit $X $Y $X $Y
    ;;
  drag)
    ovl; echo "--- 锁定态，从面板内拖到面板外："
    hit $X $Y 1500 700
    ;;
  *)
    ovl; echo "--- 锁定态，面板矩形内长按："
    hit $X $Y $X $Y
    ;;
esac
