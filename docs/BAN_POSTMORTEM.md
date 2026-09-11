# 封号复盘（2026-09-11）：为什么「改包 + 注入」这条路走不通

## 1. 事件与关键判据

- 现象：朋友的账号在**尚未使用扫描功能**（"玩了几把，还没开始测试"）的情况下被封。
- 判据：行为检测（扫描 / 读内存 / 悬浮窗截图）**必须先在局内触发我们的功能**才会留下行为样本。
  朋友没有触发，仍然被封 → 命中的只能是**与"用过什么功能"无关的检测**，即
  「客户端身份 / 包体完整性 / 设备环境」这一类，它们在**安装完第一次启动、登录**的瞬间就已经跑过。
- 推论：这不是"哪一步操作被抓"的问题，而是**装上去那一刻就已经成立**的事实。

## 2. 实测证据（全部从原包 `netease_dwrg_20260903.apk` 直接提取，不是推测）

### 2.1 好消息：Java 层 12 个签名读取点，我们确实全部回填了

在未打补丁的反编译结果 `work/smali_dbg/` 里，
`Landroid/content/pm/PackageInfo;->signatures:[Landroid/content/pm/Signature;` 一共出现 **12 次**：

| 类 | 次数 |
|---|---|
| `com/netease/ntunisdk/unifix/util/UniFixUtils` | 4 |
| `com/netease/ntunisdk/unifix_hotfix_library/util/l` | 4 |
| `com/netease/mpay/d` | 1 |
| `com/netease/mpay/p` | 1 |
| `com/netease/mpay/login/c$c` | 1 |
| `com/netease/ntunisdk/core/logs/Logger` | 1 |

我们构建后的 `work/smali/`：这 12 处 **残留 0 处**，替换成了 18 处 `Lz/a/b;->a()` 调用。
→ **本地签名自查我们骗过去了**（登录、支付能跑通就是证据）。

### 2.2 坏消息：包体指纹（APK hash）改不掉

`com/netease/ntunisdk/unifix/e`（热更配置匹配）里有一整条 `matchConfigInfo` 检查链，实测字符串：

```
matchConfigInfo: packageName = ...      matchConfigInfo: project = ...
matchConfigInfo: server_name = ...      matchConfigInfo: channelId = ...
matchConfigInfo: signMd5 = ...          matchConfigInfo: signSha1 = ...
matchConfigInfo: oldApkHash not equal = <本地算出来的> vs <服务端配置里的>
matchApkFinish: isEqualsApkMd5 = ...
```

本地这个哈希来自：

```smali
invoke-static {v0, v6}, Lcom/ntes/tools/ZipPrint;->getApkHash(Ljava/lang/String;Z)Ljava/lang/String;
```

`ZipPrint.getApkHash`（unifix / Tinker 同源）算的是 **APK 的 zip 条目指纹**（条目名 + CRC/长度一类）。
我们的重打包必然改变它，而且是**物理必然**：

- `classes.dex` 被改（2 处注入 + 12 处签名回填 + 9 处 `SigningInfo`）→ CRC 变了；
- 多了 `classes13.dex`；
- 多了 `lib/arm64-v8a/libnrt.so`。

→ `oldApkHash` 必然 ≠ 官方值，客户端自己就会得出「这个包不是官方那个包」的结论，
并把 `configInfoStr / applicationInfoStr` 报出去（`matchApkFinish`、`matchCrashMoreThan3VerInfo`
这些字符串就在同一段代码里）。

> **签名可以伪造（证书是数据），包体指纹伪造不了（指纹就是"改过"这件事本身）。**
> 这是这条路的物理上限，不是实现 bug。

### 2.3 native 层：易盾（网易安全）的环境检测，我们一条都没碰

从原包提出来（`work/sec/`）的 `lib/arm64-v8a/libsec-lib.so` = 网易易盾安全检测库，
二进制里能直接读到这些检测项的原文：

| 检测 | 证据字符串 |
|---|---|
| Xposed | `ProcMapsHasXposed`、`StacktraceHasXposed`、`XposedBridge.jar`、`de.robv.android.xposed.*` |
| Substrate | `ProcMapsHasSubstrate`、`StacktraceHasSubstrate`、`com.saurik.substrate` |
| root | `/system/bin/su`、`/system/xbin/su` |
| 双开 / 分身 | `com.excelliance.dualaid`、`com.lbe.parallel` |
| 调试 / 注入 | `TracerPid:`、`ptrace`、`isDebuggerConnected`、`waitingForDebugger` |
| 读进程信息 | `/proc/%d/maps`、`/proc/%d/status` |
| 模拟器 | `goldfish`、`vbox86`、`generic/vbox86p` |

同目录其它库的定性（避免误判）：

- `libenvsdk.so`：几乎无可读字符串 → 加固/加密，属易盾环境 SDK，**它的检测点我们看不到内容**；
- `libmagtsdk.so`：联发科 MAGT（性能调度），**不是**反外挂；
- `libpharos.so`：网易数据通道（`dual.pharos.easebar.com`），**不是**反外挂；
- `libunitrace_dumper.so`：tombstone / crash 采集。

> 注意：`libsec-lib.so` 会读 `/proc/<pid>/maps`。我们新增的 `libnrt.so` 就在它的采集面里
> （名字中性只是降低"命中已知特征"的概率，不等于安全）。

### 2.4 Java 层还有成套的 root / 环境采集与上报

| 出处 | 证据字符串 | 说明 |
|---|---|---|
| `classes4.dex` | `/proc/self/maps`、`/proc/self/mountinfo`、`/proc/self/status`、`/sbin/su`、`/su/bin/su`、`/system/bin/conbb`、`/system/bin/cufaevdd` | root 检测，后两个是 **Magisk/Zygisk 挂载名特征** |
| `classes8.dex` | `DiInfo [isRooted] start`、`DiInfo [putDiInfo]`、`DiInfo [isCPUInfo64] /proc/cpuinfo` | 设备信息采集队列 |
| `classes.dex` | `DataCenter [isRooted]`、`DataCenter [postDeviceData] result=`、`DataCenter [isLibc64] /system/lib64/libc.so is 64bit` | **采集后上报** |
| `classes7.dex` | `OpenUi, validateAppSignatureForPackage`、`validateAppSignature signature is null` | **又一条独立签名校验**，不在我们回填的 12 处里 |

## 3. 结论

1. 免 root 想读另一个进程的内存，在 Android 沙箱下只有**注入 / 改包**一条实现路径，
   而它必然改变「包体指纹 + 证书指纹」——这正是厂商**最便宜、最可靠**的判定依据：
   不需要任何行为样本，登录时就能判。
2. 所以「没使用功能也被封」不是意外，而是必然：**检测点在"装完第一次启动"那一刻就已经触发。**
3. 本次实现没有偷工：本地 12 个签名读取点全部回填（登录、支付都过）。
   但 `ZipPrint.getApkHash` 的包体指纹与自签证书这两条**改不掉**；
   再加上 native 侧易盾环境检测（我们连内容都看不到），这条路的上限已经到顶。
4. **判定：直装 / 共存（改包 + 注入）路线不可行，停止投入。**

## 4. 归因实验（把"难以排查"变成 A/B/C 三步）

**全程只用新小号，不要再用主账号。**

| 实验 | 目的 | 做法 | 判读 |
|---|---|---|---|
| A | 排除设备环境 | 朋友那台机器 + **官方原包** + 新小号，玩 2–3 把 | A 也封 → 是设备环境（root / Magisk / 框架 / 双开）在吃，我们的包未必是主因 |
| B | 归因到改包 | **干净非 root 机** + 我们的包 + 新小号，玩 2–3 把 | B 封 → 改包本身可识别（与本文分析一致） |
| C | 排除账号历史 | 干净机 + 官方包 + 该账号 | 用于区分"账号早已被标记" |

### 实测结果（2026-09-11）

**实验 B 成立：干净非 root 机 + 新账号 + 本包 → 玩几把后仍然被封。**

这一步把三种解释一次性排掉：

- 不是**账号历史**（用的是新号）；
- 不是**设备环境**（用的是干净机，没有 Magisk / 框架 / 双开）；
- 不是**行为**（没有使用过扫描功能）。

→ **剩下唯一的共同项就是"包被改过"本身**，与本文第 2.2 节推出的结论完全一致
（`ZipPrint.getApkHash` 的包体指纹 + 自签证书，都是改包不可规避的产物）。

**推论**：直装版与共存版改的是同一批东西（2 处注入 + 12 处签名回填 + 9 处 `SigningInfo`
+ `classes13.dex` + `libnrt.so`），只是包名不同 —— 因此**直装版同样必封，不必再花账号验证**。
`out/` 下的两个 APK 请勿再安装、勿再分发。

### 顺带排除的一条低成本路径（免 root 直读落盘数据）

实测官方客户端的外部存储（非 root 可读）：

```
/sdcard/Android/data/com.netease.dwrg.m4399/files/
├── ccmini/logs/*.log     ← 全是语音 SDK(WebRTC/Wwise) 启动日志，无对局数据
├── orbit/  operate/  db/ ← 统计/运营配置
└── DeviceId.txt
```

**没有任何对局 / 身份数据落盘** → 「读游戏自己写出来的文件」这条免 root 捷径不存在。

## 5. 如果还要继续：唯一还有余地的方向

- **root + 完全不改客户端**：外部进程用 `process_vm_readv` 读游戏内存
  （`process_vm_readv` **不会**在目标进程里留下 `TracerPid`，比 `ptrace` 干净），
  不注入、不 hook、不加 so、不改资源、不改 dex。
- 纪律：进程名中性；读完立即退出、不常驻；不写游戏目录；
  别用 Magisk 模块把东西塞进游戏进程；别用 Xposed / LSPosed / Frida。
- 必须承认的代价：这条路**失去了"免 root"这个唯一卖点**；
  而且仍存在"可疑进程列表 / 命令行比对"这一层暴露面。

**两条路的账很清楚：改包 = 免 root 但必死；外部读 = 需要 root 但暴露面小一个数量级。**

## 6. 止损清单

```powershell
adb uninstall com.netease.dwrg.fj        # 共存版
adb uninstall com.netease.dwrg           # 直装版（装回官方包）
adb shell rm -f /data/local/tmp/touch_probe.sh
```

- 出事的设备与账号不要再用于测试；后续只用一次性小号。
- 本文与 `tools/` 里的测试脚本都留在仓库里，不要删——下次换方向时这是唯一的基线。

## 7. 待回填信息（贴回来可以进一步定位到具体那一条）

| 项 | 值 |
|---|---|
| 封号通知原文 / 时长（3 天 / 7 天 / 永久） | `<粘贴>` |
| 朋友那台设备：是否 root、是否装过 Magisk / Zygisk / LSPosed / Frida、是否双开或模拟器 | `<填>` |
| 用的是哪个包（直装 / 共存）、安装时间、首次登录时间、封号时间 | `<填>` |
| 该账号历史（是否借人、是否用过其它辅助 / 脚本） | `<填>` |
