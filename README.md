# 第五人格「模仿者看身份」共存版（注入式）—— 免 root + 隐身化改造

把原本需要 root 的内存扫描器**直接注入游戏进程**：扫描器跑在游戏自己的地址空间里，
用 `/proc/self/maps` 取区域、用 `process_vm_readv(getpid(), …)` 读自己的内存。

一句话原理：**别人读你的内存要 root，你自己读自己的内存不用** ——
内核 `process_vm_rw() → mm_access() → ptrace_may_access() → __ptrace_may_access()`
的第一句就是 `if (same_thread_group(task, current)) return 0;`，
「读自己」在 LSM 检查之前就放行了（见 `src/native/nrt.c`）。

本轮（2026-09-11）在上一版基础上做了**隐身化改造**：把能被静态扫描或运行期日志
识别的暴露面全部消除或压低，剩下的都是原理上无法消除的（见第 9 节）。

---

## 0. 三个不变量（任何时候都不许破）

1. **不动任何既有 `.so`**（`libsec-lib.so` / `libenvsdk.so` / `libsecsdk.so` /
   `libybuaxx.so` …），**不动 `assets/`**（尤其 `ntunisdk_so_uuids`、
   `probeSoMd5Record.txt`、`emulatordetector_data`），不动 `resources.arsc` 的资源内容；
2. **不新增任何权限**：`android.permission.SYSTEM_ALERT_WINDOW` 官方包本来就声明了；
3. **只改 `classes.dex`**（2 处注入 + 12 处签名回填 + 9 处 `SigningInfo`；共存版再加 2 条
   包名字符串），共存版另改清单 33 条字符串与 `resources.arsc` 的包名定长字段。

---

## 1. 产物

| 项 | 共存版（默认） | 直装版（`-OriginalPackage`） |
|---|---|---|
| 文件 | `out/第五人格-共存版-2026.0828.1653.apk` | `out/第五人格-直装版-2026.0828.1653.apk` |
| 大小 | 2 012 880 681 B | 同流程产出，清单不延长故略小 |
| SHA-256 | `cbcd315c0b2513ff25bcc7ed158d31c61ad4e40e923fde8fb592e5a7839ec12c` | 见构建输出 |
| 包名 | **`com.netease.dwrg.fj`** | `com.netease.dwrg`（与官方一致） |
| 与官方包共存 | 可以，可同时安装、同时登录 | 不行，必须先卸载官方包 |

两版共同点：`versionCode/versionName = 262401653 / 2026.0828.1653`（与官方一致）、
`minSdk 21 / targetSdk 30`、仅 `arm64-v8a`、**v1 + v2 签名**（自签 `CN=fjdirect`，v3 关闭）、
zip 条目 6075（原包 6073，多 `classes13.dex` 与 `lib/arm64-v8a/libnrt.so`）。

```powershell
pwsh -File build.ps1                  # 共存版（推荐：官方包原样留着，可对照/双开）
pwsh -File build.ps1 -OriginalPackage # 直装版（包名与官方一致，需先卸载官方包）
```

---

## 2. 隐身化改造（为什么改、改完怎么证明真的改了）

### 2.1 暴露面清单 → 处置 → 实证

| # | 原来的暴露面 | 谁看得见 | 处置 | 验证方式 |
|---|---|---|---|---|
| 1 | dex 明文特征：`模仿者`/`第五人格`/`FJDirect`/`com/fj/direct`/`scan.txt`/`狼人`/`侦探团`/`神秘客`/`阵营`/角色名 | 任何把它拖进 jadx 的人 | **中性短类名 + 全部字符串字面量密文化**（2.2 节） | 构建期 `check_stealth dex` 硬断言；剩余可打印串 364 条，全是类名/字段名等结构性内容 |
| 2 | logcat 自曝：`I FJDirect: 悬浮窗已创建 …` | 同一个进程 `logcat -d` 就能读到 | **日志门面 `z.a.i`**，release 下 `ON` 是编译期常量 `false`，`javac` 把整块日志消掉（2.6 节） | 真机 release 跑完启动 + 扫描，logcat **0 行**相关输出 |
| 3 | 结果落盘 `files/scan.txt`（文本、中文、带包名目录） | 任何文件管理器 / 备份 / 云同步 | release **不落盘**；只有 `-DebugBuild` 才写，且改名 `log.txt` | 真机 `ls /sdcard/Android/data/com.netease.dwrg.fj/files/` 无该文件 |
| 4 | 悬浮窗「被遮挡」标记：窗口可触摸 → 下层游戏窗口的触摸事件带 `FLAG_WINDOW_IS_OBSCURED`（Android 12+ 若游戏窗口是 `BLOCK_UNTRUSTED`，触摸甚至被直接丢弃） | 游戏进程自己（`MotionEvent.getFlags()`） | **窗口全程 `FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCHABLE`**：看得见、点不到、不参与命中测试（2.4 节） | `dumpsys input` 里我们的窗口 `inputConfig=NOT_FOCUSABLE \| NOT_TOUCHABLE`、游戏窗口 `inputConfig=0x0`；探针实测游戏侧 `flags=0x100000`（无 bit0） |
| 5 | JNI 符号自曝：`Java_com_fj_direct_MemReader_readSelf` 把包名/类名/方法名直接写进 `.so` | `nm` / `strings libmmread.so` | **`JNI_OnLoad` + `RegisterNatives` 动态绑定**，动态符号表只剩 `JNI_OnLoad`（2.3 节） | 构建期 `llvm-nm --dynamic --defined-only` 断言：多余符号直接让构建失败 |
| 6 | 库名 `libmmread.so` | `unzip -l` / `/proc/self/maps` | 改名 **`libnrt.so`**，且与原包 74 个 `lib/` 条目零重名 | 构建期 `check_stealth libname` 断言 |
| 7 | 新类名与官方 dex 里的类 / 字符串撞车（会 `NoClassDefFoundError`） | —— | 用官方 **12 个 dex 全字节**校验 | 构建期 `check_stealth collide` 断言：0 冲突 |

> 一句话：**静态看不出「这是什么工具」，运行期不留下「这个进程多做了什么事」的痕迹。**
> 做不到的部分（自签证书、包名、dex 字节差异）在第 9 节逐条列清。

### 2.2 dex：中性命名 + 字符串密文化

**类名映射**（`src/z/a/`，文件名 = 类名；冲突已用官方 12 个 dex 全字节验证为 0）：

| 类 | 原类名 | 职责 | 对外接口 |
|---|---|---|---|
| `z.a.a` | `Boot` | 注入入口：存 Context、判主进程、投递悬浮窗 + 按键钩子 | `public static void a(Context)`、`public static void b()` |
| `z.a.b` | `SigFix` | 返回官方 `Signature[]`（DER 硬编码 base64） | `public static Signature[] a()` |
| `z.a.c` | `MemScanner` | 进程内扫描器 | 包内 |
| `z.a.d` | `MemReader` | 读内存统一入口（native 优先、文件兜底） | 包内 |
| `z.a.e` | `RoleTable` | 角色索引 → 中文名（72 条） | 包内 |
| `z.a.f` | `OverlayWindow` | 悬浮窗（上色、倒计时、复制） | 包内 |
| `z.a.g` | `KeyToggle` | 音量键手势状态机 | 包内 |
| `z.a.h` | —— | 字符串解密 | `public static String a(byte[])` |
| `z.a.i` | —— | 日志门面（release 全静默） | 包内 |

> 必须 `public` 的只有 `z.a.a` / `z.a.b` 及其被 smali 调用的方法：smali 是**跨包调用**，
> 包私有会直接 `IllegalAccessError`。

**字符串密文化**（`tools/obf_strings.py`）：

```
编码（Python 侧）  c[i] = p[i] ^ K[(i * 5 + 7) & 15]
解码（Java 侧）    src/z/a/h.java 的 a(byte[])，与上式逐字节对称
密钥 K             唯一真源是 h.java 里的 16 字节数组，脚本解析它，两边不一致构建直接失败
本次结果           9 个文件、248 处字面量全部密文化（0 处跳过）
```

* 产物只落在 `work/obf-src/`，`src/` 保持可读 —— 改源码由 `javac/d8` 保证 dex 合法，
  比在 dex 里原地改 `string_data_item`（uleb128 长度 + MUTF-8，连长度都改不了）安全得多；
* 构建期自检：把每一处密文在 **JVM 上解回来**与原文逐条比对，`decode-check 248/248 OK`
  才算通过（`build.ps1` Step 5c）—— 证明「Python 编码面」与「Java 解码面」100% 对称；
* 已知边界：`case "字面量":` 这类必须保持编译期常量的位置一律跳过并打印清单
  （本仓库源码里没有，所以是 0 跳过；将来出现也不会静默出错）。

### 2.3 native：`libnrt.so`（改名 + 去 `Java_` 导出）

`src/native/nrt.c`：

* 不导出 `Java_<包名>_<类名>_<方法名>`（那个符号名本身就把「哪个包、哪个类、哪个方法在读内存」
  写进了 `.so`），改成 `JNI_OnLoad` 里 `RegisterNatives` 绑定 `z/a/d` 的 `(J[BII)I`；
* 绑定用的类名字节数组做 XOR 掩码，**掩码变量必须是 `volatile`**：不加时 clang 会把 `xor`
  直接折叠成明文常量塞进 `.rodata`，`strings` 照样能看到 `z/a/`（实测踩过）；
* 编译参数：`-fvisibility=hidden '-Wl,--exclude-libs,ALL' '-Wl,-s'`，
  动态符号表只剩 `JNI_OnLoad`（构建期 `llvm-nm` 硬断言），静态符号表也去掉；
* 用 `syscall(__NR_process_vm_readv, …)` 而不是链接 libc 同名函数：bionic 从 API 23 才导出该符号，
  而 minSdk 21；
* 规模：`work/native/libnrt.so` 4 904 B，包内 1 881 B（DEFLATE）。

### 2.4 悬浮窗：全程 `NOT_TOUCHABLE`

窗口 flag 常驻 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_NO_LIMITS`：
**看得见、点不到** —— 窗口不参与输入命中测试，游戏侧永远收不到被遮挡标记。
代价是不可触摸的窗口既点不到按钮也拖不动，所以交互全部改由音量键驱动（2.5 节），
只有「临时解锁」的 20 秒里才能拖动 / 点按钮。

**遮挡实证**（两级）：

1. 静态：`dumpsys input` 里我们的窗口
   `inputConfig=NOT_FOCUSABLE | NOT_TOUCHABLE | PREVENT_SPLITTING`，
   游戏 `com.netease.dwrg.Client` 窗口 `inputConfig=0x0`；
2. 运行期：`tools/probe/`（dev-only 探针 APK，包名 `com.fj.probe`）自己盖一个可切换
   「可触摸 / 不可触摸」的悬浮窗，把收到的 `MotionEvent.getFlags()` 打在屏幕和 logcat 上。
   实测：`NOT_TOUCHABLE` 时下层 Activity 收到 `flags=0x100000`
   （**bit0 `FLAG_WINDOW_IS_OBSCURED` 为 0**）；切成可触摸后，同坐标点击下层 Activity
   **收不到事件**（说明窗口确实参与了命中测试，对照组成立）。

```powershell
pwsh -File build.ps1 -Probe     # 顺带产出 out/probe-overlay.apk（dev-only，别装到别人机器上）
```

### 2.5 交互：音量键手势（短按 / 长按）

窗口不可触摸后，唯一的操作入口就是音量键。`z.a.g` 用 **DOWN/UP 计时**自实现长短按
（不用 `getRepeatCount`：不同 ROM 的连发行为不一致）：

| 键 | 短按 | 长按 |
|---|---|---|
| 音量 **加** | 扫描（结果直接显示在面板上；不在对局时会显示 `命中 0/12`） | 按住 **3.0 s** → 复制结果到剪贴板（Toast「已复制」） |
| 音量 **减** | 显示 / 隐藏悬浮窗 | 按住 **5.0 s** → 解锁触摸 **20 s**（可拖动面板、点按钮；到时自动上锁，再按住 5 s 立即上锁） |

* 音量键事件**全部吞掉**（否则无法区分「短按」和「按住 3 / 5 秒」）。
  **副作用：游戏内音量键被占用，调音量请用系统面板或游戏内设置。**
* 解锁期间标题变成「已解锁 Ns」倒计时，一眼能看出当前是可触摸状态。
* **「扫描 / 复制 / 收起 / ✕」四个按钮平时不显示**：窗口不可触摸时它们点了也没反应，
  摆在那儿只是视觉噪音、还让人误以为能点。所以平时面板上只有标题 + 结果
  （外加一行手势提示），**按住音量减解锁的那一刻按钮才出现**，20 秒倒计时结束自动消失。
  解锁时如果面板处于「收起 / 最小化」状态，会一并还原成完整面板 ——
  「解锁成功但屏幕上什么都没有」比不解锁更让人困惑。
* 怎么拿到按键又不抢焦点：用 `Proxy` 把 Activity 的 `Window.Callback` 包一层，只截
  `dispatchKeyEvent` 里的音量键、**其余调用原样转发**给原 callback（游戏自己的按键行为
  一个字节都不变）。没有把悬浮窗设成可获焦（那样会抢走手柄/键盘/输入法的焦点）。
* 为什么还要反射补捞 Activity：这个包用网易 unifix 热更新代理，manifest 里的
  `UFProxyApplication` 只是代理，`registerActivityLifecycleCallbacks` 真机实测
  **一次都不回调** → 除注册外，每 2 s 反射扫一遍 `ActivityThread.mActivities` 补挂
  （`WeakHashMap` 去重，不会重复包）。
* 兜底通道：`Settings.System` 音量值 + `ContentObserver`（不需权限），只在
  「按键钩子没装上」时起作用，映射仍是显示 / 隐藏。

### 2.6 静默：release 不打日志、不落盘

* `z.a.i` 是唯一日志出口，`ON` 是 `public static final boolean`，`build.ps1` 按构建模式
  把它钉成 `false`（默认 release）或 `true`（`-DebugBuild`）。release 下
  `if (ON) { Log.x(…) }` 整块被 `javac` 消掉 —— dex 里没有日志调用，logcat 一行不写，
  连 tag（`nt`）和中文文案本身也是密文；
* 结果文件只在 `ON == true` 时写，文件名是 `log.txt`（不再是 `scan.txt`）；
* **排错一定要用 `-DebugBuild` 构建**（装机会覆盖，装完记得重新授权悬浮窗）。

```powershell
pwsh -File build.ps1 -DebugBuild      # 打开日志（logcat + log.txt）
pwsh -File build.ps1                  # 发布构建：全静默
```

> 参数名是 `-DebugBuild` 而不是 `-Debug`：PowerShell 的通用参数里已经有 `-Debug`，
> 用它会直接报 `MetadataError`。

---

## 3. 共存版：为什么改包名、改了什么、为什么只改这些

Android 用 **package name 唯一标识一个应用**：同包名的第二个 APK 会被当成「同一个应用」走
升级 / 替换逻辑，签名不同就直接 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。所以「共存」只能换包名。

### 3.1 新包名为什么取 `com.netease.dwrg.fj`（超串）

`classes5.dex` 的 `Client$2.run` 会执行 `top` 解析自身进程行、用
`contains("com.netease.dwrg")` 判断「哪一行是我」并上报 CPU/RSS。取**超串**
`com.netease.dwrg.fj` → 该 `contains` 依旧命中，`classes5.dex` 一行都不用改。改得越少越不容易崩。

### 3.2 清单 33 条改写 / 20 条保留

| 项 | 条数 | 例子 |
|---|---|---|
| `<manifest package>` | 1 | `com.netease.dwrg` → `com.netease.dwrg.fj` |
| provider `android:authorities` | 28 条唯一串（31 处属性） | `com.netease.dwrg.fileprovider` → `…fj.fileprovider` |
| 自定义 `<permission>` | 2 条唯一串（3 处声明） | `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`、`…permission.ngpush` |
| 组件 `android:name`（判定为非类名） | 1 | `com.netease.dwrg.yxapi.YXEntryActivity` |
| 渠道回调标识 | 1 | `comccbpay105330173990048com.netease.dwrg` |
| **合计改写** | **33** | |
| **保留（真实类名）** | **20** | `com.netease.dwrg.Client`、`…Launcher`、`…wxapi.WXPayEntryActivity` |

> 前缀替换是禁区：smali / 清单里的 `Lcom/netease/dwrg/Foo;` 是**类型描述符**，指向真实存在的类，
> 全局替换会造出几千处指向不存在类的引用 → `NoClassDefFoundError`。所以 dex 侧只认
> **带引号、整串相等**的字符串（`patch_dex.py` 的 `PKG_STRINGS`），清单侧用
> **全 dex 类型描述符集合**判定「这是不是类名」。

### 3.3 `resources.arsc` 的包名必须一起改（真机踩过）

第一版没改，结果**点开游戏全程黑屏**：`Resources.getIdentifier(name, type, getPackageName())`
按**包名**匹配 `resources.arsc` 里 `ResTable_package` 的包名，清单改了而 arsc 没改 → 查到 0 →
紧接着 `getString(0)` 抛 `Resources$NotFoundException` → `Launcher.onCreate` 挂、游戏 `System.exit(0)`。

修法（`coexist.py patch-arsc`）：包名是**定长字段**（`uint16_t name[128]`，偏移 12、256 字节），
原地覆盖写新包名 + 补零，**长度、偏移、`assets/res/*.wpk` 索引全都不动**，零副作用。

### 3.4 共存性硬指标

`build.ps1` 第 10 步把官方包与新包都 `aapt2 dump xmltree` 出来逐条比对 authorities 与自定义
`<permission>`，**有交集直接抛异常中断构建**。实测两边 31 / 31、3 / 3，**无交集**。

### 3.5 实测确认「不用改」的部分

| 项 | 结论 | 依据 |
|---|---|---|
| `BuildConfig.APPLICATION_ID` | 不用改 | 12 个 dex 全树零引用 |
| `Lcom/netease/dwrg/...` 类型描述符 | 不用改 | 是类名不是包名 |
| `ApkChanneling` 渠道 | 不用处理 | 原包 v2 块只有标准 `id=0x7109871a`，无 `0xFF163163` 自定义块；重签后仍只有标准块，`getChannel()` 前后都返回 `null` |
| 既有 `.so` / `assets/` / 其余条目 | 一个字节都不动 | zip 逐条对比（只**新增**了 `classes13.dex` 与 `libnrt.so`） |

### 3.6 已知副作用（可接受）

`com.netease.dwrg.yxapi.YXEntryActivity` 会被一起改写（清单里写的是字符串，真实类是
`Lim/yixin/sdk/api/BaseYXEntryActivity;`，过不了类型描述符判定），**易信一键登录这条路失效**；
主流程（账号 / 手机 / 微信 / QQ / 游客登录）不受影响。

---

## 4. 签名回填（重打包后还能登录的关键）

smali 侧把官方读签名点换成 `z.a.b.a()`（返回**官方证书**的 `Signature[]`）：

| 改写 | 处数 | 文件 |
|---|---|---|
| `PackageInfo->signatures` 读取 | 12 | `mpay/d`、`mpay/p`、`mpay/login/c$c`、`ntunisdk/core/logs/Logger`、`ntunisdk/unifix/util/UniFixUtils`(4)、`ntunisdk/unifix_hotfix_library/util/l`(4) |
| `SigningInfo` 链路（API 28+） | 9 | `ntunisdk/ngplugin/common/C`(3)、`UniFixUtils`(3)、`unifix_hotfix_library/util/l`(3) |
| 包名字符串（仅共存版） | 2 | `com/netease/dwrg/Manifest$permission` |
| 注入入口 | 2 | `UFProxyApplication.attachBaseContext` → `Lz/a/a;->a(Landroid/content/Context;)V`；`onCreate` → `Lz/a/a;->b()V` |

* **只补 `signatures` 不够**：API 28 起网易 SDK 会优先走
  `PackageInfo.signingInfo` → `getApkContentsSigners()` / `getSigningCertificateHistory()`，
  不补就等于在 Android 9+ 上把自签名暴露给校验逻辑；
* `patch_dex.py` **幂等**，且每次先做一次「旧类名 → 新类名」迁移（`migrate_legacy()`）——
  否则复用 `work/smali`（`-SkipDecompile`）时会残留旧注入调用，真机表现是启动即
  `ClassNotFoundException: com.fj.direct.Boot` 然后自杀；
* 官方证书（`libs/official_cert.der`，从 `META-INF/H55_KEYS.RSA` 偏移 60、长度 845 提取）：

```
Subject/Issuer: CN=dwrg, OU=dwrg, O=dwrg, L=hz, ST=zj, C=cn
序列号 758d51d5   SHA256withRSA   有效期 2017-11-13 → 2072-08-16
SHA-256: 918e39b4e77e4e1e03a7c0236c6f473037851069c67b5ebecf60e9b9744e4dc9
SHA-1  : b7cb8a61d0b7e0bbdcd8f4a5b12710544cd1c14e
```

---

## 5. 读内存通道

| 通道 | 可用范围 | 说明 |
|---|---|---|
| `process_vm_readv`（默认，native `libnrt.so`） | **Android 10+ 免 root 可用** | 读「自己」在内核里走 `same_thread_group` 快速放行、不经过 LSM |
| `/proc/self/mem`（降级） | Android 9 及以下 | Android 10+ 被 SELinux 直接拒：`open failed: EACCES (Permission denied)`，Java 侧无解 |

native 加载失败（换 ABI 等）会自动降级到文件路径，不会让扫描器直接崩；结果行会标出用的哪条通道。

---

## 6. 扫描器细节（与 root 版 `模仿者遍历.cpp` 行为对齐）

```
特征码： [i]==105(i) [i+1]==100(d) [i+2]==120(x) [i+5]==99(c) [i+6]==97(a) [i+14]==105 [i+23]==105
+0x3    内存编号 0-11（对外 +1 → 1-12）
+0xC    阵营 1=侦探团 2=狼人 3=神秘客
+0x19   起 6 字节 identity，按阵营取第 (camp-1) 字节作为单字节角色索引
守卫：   阵营必须 ∈{1,2,3}；编号 ≤11；侦探团 / 神秘客的索引 0 视为未初始化跳过，狼人的 0 合法
```

相比原版修掉的两个问题：跨块漏命中（改为每块读 `CHUNK + 32` 字节、只扫前 `CHUNK` 个起点，
块间重叠 32 字节）、越界读（原版 `Name[i+23]` 在缓冲区尾部越界，这里用 `limit = n - 32` 收敛）。

工程化处理：只扫 `rw` + `anon` 区域；读失败（未驻留页 → `EIO`）按 4096 页步进跳过并计数；
单次扫描上限 8 GB（真机实测读 3.5 GB 约 18 s，原定 3 GB 上限会在扫完前被截断）；
**按区域分组取最优**（区域内按编号去重取首个命中，最后采信命中编号最多的区域，
对齐 root 版按 `roleCount` 降序取模块的策略）；没有任何区域到 5 个编号时退回
「全局按编号去重取首个命中」；全程后台线程，耗时显示在面板上。

---

## 7. 构建

### 7.1 依赖

| 依赖 | 路径 / 下载 |
|---|---|
| JDK 17 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` |
| Android build-tools 36.0.0 | `E:\Android\Sdk\build-tools\36.0.0`（`aapt2/d8/apksigner/zipalign/dexdump`） |
| `android.jar` | `E:\Android\Sdk\platforms\android-35\android.jar` |
| apktool 2.9.3 | `libs/apktool_2.9.3.jar`，23 254 968 B，SHA-256 `7956eb04194300ce0d0a84ad18771eebc94b89fb8d1ddcce8ea4c056818646f4`，来源 `https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar` |
| NDK r27d | `E:\Dev\tool\NDK\android-ndk-r27d`（只用 `toolchains\llvm\prebuilt\windows-x86_64\bin\` 下的 `aarch64-linux-android21-clang.cmd` 与 `llvm-nm.exe`），也可用环境变量 `ANDROID_NDK_HOME` 指定 |
| 原始 APK | `E:\Dev\workspace\idv\netease_dwrg_20260903.apk`，2 012 889 355 B，SHA-256 `0683dd40388bb6fb1d58d4111f80909dc5a2d7a0af07a703f0de73e7e271a5c3` |

> 为什么驱动 apktool 的 jar 而不是 smali / baksmali：Maven Central 上的 `com.android.tools.smali`
> 已下架；apktool 2.9.3 内部打包了 `SmaliDecoder` / `SmaliBuilder`，`tools/DexTool.java` 用反射驱动，
> 效果等价（`com.android.tools.smali.smali.Main` 没有 main 方法，不能直接 `java -cp … Main`）。

### 7.2 用法

```powershell
pwsh -File build.ps1                  # 完整构建（默认 = 共存版，包名 com.netease.dwrg.fj，release 静默）
pwsh -File build.ps1 -OriginalPackage # 直装版（包名与官方一致，需先卸载官方包）
pwsh -File build.ps1 -SkipDecompile   # 复用 work\smali，只重跑编译/打包/签名（补丁仍会重打 + 迁移旧类名）
pwsh -File build.ps1 -DebugBuild      # 打开日志（logcat + log.txt），排错用
pwsh -File build.ps1 -V2Only          # 只做 v2 签名
pwsh -File build.ps1 -Probe           # 顺带构建遮挡探针 out/probe-overlay.apk（dev-only）
pwsh -File build.ps1 -OutName x.apk   # 自定义产物名
```

### 7.3 流水线

1. `javac` 编译 `tools/DexTool.java`；
2. 从原包抽出 `classes.dex`；
3. `baksmali` 反编译 → `work/smali`（约 40 s）；
4. `tools/patch_dex.py`：旧类名迁移 → 2 处注入 + 12 处签名点 + 9 处 `SigningInfo`
   （共存版再加 2 条包名字符串）；
5. （仅共存版）`tools/coexist.py patch` 改清单、`patch-arsc` 改 arsc 包名；
6. `smali --api 21` 回编译 → `work/classes.patched.dex`；
7. `tools/obf_strings.py` 生成 `work/obf-src/`（248 处密文化）→ 按构建模式钉 `z.a.i.ON`
   → `javac --release 8 -encoding UTF-8` → **Step 5c 解码自检**（`decode-check 248/248 OK`）
   → `d8 --min-api 21` → `classes13.dex`；
8. NDK 编译 `src/native/nrt.c` → `work/native/libnrt.so`，断言动态符号只剩 `JNI_OnLoad`；
9. `tools/repack.py` 流式重打包：换 `classes.dex`、插入 `classes13.dex`、
   `--add lib/arm64-v8a/libnrt.so`，其余条目**保序保压缩方式**逐字节搬运，
   `--drop-v1-signature` 丢掉旧 `H55_KEYS.*`；
10. `zipalign -f -p 4`（`resources.arsc` 是 STORED，必须 4 字节对齐）；
11. `apksigner sign --v1 true --v2 true --v3 false --min-sdk-version 21`；
12. 校验（全自动断言，任何一条不过就中断）：`apksigner verify -v`、`zipalign -c -v 4`、
    `aapt2 dump badging` 包名断言、authorities / permission 无交集、
    `resources.arsc` 包名与 STORED 断言、`classes13.dex` magic + `dexdump -f`、
    `libnrt.so` ELF machine + 包内条目，以及 **`check_stealth` 的四条：`dex`（无禁用明文）、
    `so`（无 `Java_` / 旧库名 / 品牌字样 / 绑定类名）、`collide`（9 个类名与官方 12 dex 无冲突）、
    `libname`（不与原包 lib 重名）**。

> `zipalign` 会往 stderr 刷上千行 `WARNING: header mismatch`（Android zip 库对原包自解压条目风格的抱怨），
> 是已知噪音，最后仍会打印 `Verification successful`；`build.ps1` 已重定向掉。

### 7.4 密钥

`libs/direct.keystore`（`alias=fjdirect`，`storepass=keypass=fjdirect`，PKCS12），首次运行由
`build.ps1` 用 `keytool` 生成，证书
`SHA-256 = 80651bc5397a0bc12688c2f3e45ebe8872f98c3c499ac69860f22d98bde59a953`。
**本轮 keystore 与上一版保持一致**（同一个 dname），所以覆盖安装不丢数据。

---

## 8. 验收

### 8.1 静态（`build.ps1` 第 12 步自动断言，全部通过）

| 检查 | 结果 |
|---|---|
| `apksigner verify -v` | v1 `true` / v2 `true` / v3 `false`，单签名者 `CN=fjdirect` |
| `zipalign -c -v 4` | `Verification successful` |
| `aapt2 dump badging` | `com.netease.dwrg.fj` / `262401653` / `2026.0828.1653` / `minSdk 21` / `targetSdk 30` / 含 `SYSTEM_ALERT_WINDOW` / `native-code: 'arm64-v8a'` |
| `resources.arsc` | 包名 `com.netease.dwrg.fj`，STORED，3 788 696 B 不变 |
| `classes13.dex` | magic `dex\n035`，46 888 B，`dexdump -f` 正常 |
| `libnrt.so` | aarch64 ELF、4 904 B、动态符号只有 `JNI_OnLoad` |
| 共存性 | authorities 31 / 31、自定义 permission 3 / 3，**无交集** |
| `check_stealth dex` | 无禁用明文；剩余可打印串 364 条（类名 / 字段名等结构性内容） |
| `check_stealth collide` | 9 个类名与官方 12 个 dex 无冲突 |
| `check_stealth libname` / `so` | 不与原包 lib 重名；无 `Java_` / 旧库名 / 品牌字样 / 绑定类名 |
| 字符串自检 | `decode-check 248/248 OK` |

### 8.2 真机（release 构建，Redmi K20 Pro / Android 13 / MIUI，2026-09-11）

```powershell
# MIUI 会拦 adb install，走 root 装（约 95 s）
adb push "out\第五人格-共存版-2026.0828.1653.apk" /data/local/tmp/idv_fj.apk
adb shell 'su -c "pm install -r /data/local/tmp/idv_fj.apk"'
# MIUI 每次覆盖安装都会把悬浮窗权限重置成 ignore，装完必须重新授权
adb shell 'su -c "appops set com.netease.dwrg.fj SYSTEM_ALERT_WINDOW allow"'
```

| 项 | 结果 |
|---|---|
| 安装 / 启动 | `Success`；登录页正常弹出、无黑屏（签名回填生效） |
| **logcat 静默** | 检索 `FJDirect` / `z.a.` / `模仿者` / `悬浮窗` —— **0 行**；本进程只剩游戏 / ART 自己的日志 |
| **不落盘** | `/sdcard/Android/data/com.netease.dwrg.fj/files/` 下**没有** `log.txt` / `scan.txt` |
| 窗口 flag | `dumpsys window`：`ty=APPLICATION_OVERLAY`、`fl=NOT_FOCUSABLE NOT_TOUCHABLE LAYOUT_NO_LIMITS`、`alpha=0.8`、`appop=SYSTEM_ALERT_WINDOW` |
| 遮挡 | `dumpsys input`：我们的窗口 `inputConfig=NOT_FOCUSABLE \| NOT_TOUCHABLE \| PREVENT_SPLITTING`；游戏 `com.netease.dwrg.Client` 窗口 `inputConfig=0x0` |
| 音量加 短按 | 面板出现「扫描中…」→ 扫描完成（Toast「扫描完成：0/12（18 602 ms）」）；**音乐音量 10 → 10 不变**（按键确实被我们吃掉） |
| 音量加 按住 3 s | Toast「已复制」→ 剪贴板拿到结果文本 |
| 音量减 短按 | 面板隐藏 / 恢复（窗口仍在 WindowManager 里，只是 1×1 占位，`mDrawState=HAS_DRAWN`） |
| 音量减 按住 5 s | 标题变「已解锁 20s」并逐秒倒计时；`dumpsys window` 的 `fl=` 去掉 `NOT_TOUCHABLE`；**20 s 后自动恢复** `NOT_TOUCHABLE` |
| 按钮可见性 | 锁定态：面板只有「模仿者·直装」+ 结果，**没有按钮**；解锁态：出现「已解锁 18s」+ ✕ + 扫描 / 复制 / 收起；自动上锁后按钮自动消失；锁定态下音量加短按照样能扫描（面板显示「扫描中…」） |
| 扫描（不在对局） | `耗时 18 602 ms ｜ 通道 process_vm_readv ｜ 区域 1280 ｜ 读取 3 526 MB ｜ 命中 0/12`（登录页当然没有角色数据，符合预期） |
| 探针实证 | `NOT_TOUCHABLE` 时下层 Activity 收到 `flags=0x100000`（bit0 = 0，无遮挡标记）；切可触摸后同坐标收不到事件 |

### 8.3 真机功能验收（需要在「模仿者」对局里做）

进对局 → 音量加短按 → 期望：① 编号 1–12 各出现一次 ② 阵营配色正确
（侦探团蓝 `#4FA8FF` / 狼人红 `#FF5A5A` / 神秘客黄 `#FFC93C`）③ 耗时正常（数百 ms–十几秒）
④ 与 root 版 `模仿者遍历.cpp` 同一局的结果一致。

### 8.4 失败定位判据

| 现象 | 判据 |
|---|---|
| 装不上 | 对齐 / 签名 → 重跑第 10 / 11 步；MIUI 用 root `pm install -r` |
| 闪退 | `adb logcat` 看 `avc:`（SELinux）/ ART 错误 |
| 启动即 `ClassNotFoundException: com.fj.direct.Boot` | `work/smali` 是旧的且迁移没跑到 → 删掉 `work/smali` 全量构建 |
| 登录报「应用校验失败」 | 签名点没补全 → 按第 4 节的表补点（先看 `mpay` 与 `unifix` 两族） |
| 结果为空 | 看结果行的「通道」应为 `process_vm_readv`；若显示 `/proc/self/mem` 且 EACCES，说明 `libnrt.so` 没加载成功 |
| 悬浮窗没出现 | 没授权 `SYSTEM_ALERT_WINDOW`（MIUI 覆盖安装后会重置）→ `appops set … allow` 后重开游戏 |
| 想排错但没有任何日志 | 装的是 release 包 → 换 `-DebugBuild` 构建 |

---

## 9. 已知边界与残余风险（消除不掉的部分，写清楚）

| # | 残余 | 为什么消不掉 |
|---|---|---|
| 1 | **自签证书** `CN=fjdirect` | 没有官方私钥，无法伪造官方签名；只能靠第 4 节的回填骗过 SDK 的**自查**，瞒不过服务端按证书哈希核对 |
| 2 | **包名 `com.netease.dwrg.fj`** ≠ 官方 | 共存版的定义就是换包名；直装版可保持官方包名，但要卸载官方包 |
| 3 | **`classes.dex` 与官方字节不同** | 注入入口与签名回填必须改它；只能做到「改动最小」（共存版 23 处 / 直装版 21 处） |
| 4 | **APK 多 2 个条目** | `classes13.dex`（我们的全部逻辑）、`lib/arm64-v8a/libnrt.so`（进程内自读）；包体因此多约 25 KB |
| 5 | **`/proc/self/maps` 里多一条 `libnrt.so` 映射** | 只要用 native 通道就必然存在；已做到「库名中性、路径与其他 lib 同形」（原包 `extractNativeLibs="true"`，所有 so 都解压到 `/data/app/.../lib/arm64/`） |
| 6 | **音量键被占用** | 短按 / 长按全靠它区分；调音量改用系统面板或游戏内设置 |
| 7 | **解锁的 20 s 内窗口可触摸** | 这段窗口期内游戏侧触摸会带 `FLAG_WINDOW_IS_OBSCURED`；对局中保持上锁即可（标题显示倒计时，一眼可见） |
| 8 | 服务端按证书哈希 / 包名 / 包体完整性核对 | 原理性限制，无解 |

> 结论：本轮把**可消除的静态特征（dex 明文、JNI 符号、库名）、运行期痕迹（logcat、落盘）、
> 以及交互副作用（被遮挡标记）**都处理掉了；剩下的 1–5 是「注入式方案 + 重打包」的
> 结构性代价，只能减小、不能消除。

---

## 10. 仓库结构

```
idv-mimic-direct/
├── build.ps1                     # 一键流水线（默认共存版 release；-DebugBuild / -OriginalPackage / -Probe）
├── src/
│   ├── native/nrt.c              # process_vm_readv 自读 + JNI_OnLoad/RegisterNatives
│   └── z/a/                      # 中性短类名（见 2.2 的映射表）
│       ├── a.java                # 注入入口
│       ├── b.java                # 官方签名回填
│       ├── c.java                # 进程内扫描器
│       ├── d.java                # 读内存入口（native 优先、文件兜底）
│       ├── e.java                # 角色表（72 条）
│       ├── f.java                # 悬浮窗（NOT_TOUCHABLE + 倒计时 + 复制）
│       ├── g.java                # 音量键手势状态机
│       ├── h.java                # 字符串解密（密钥唯一真源）
│       └── i.java                # 日志门面（release 全静默）
├── tools/
│   ├── DexTool.java              # 反射驱动 apktool 的 SmaliDecoder/SmaliBuilder
│   ├── patch_dex.py              # smali 补丁（幂等 + 旧类名迁移）
│   ├── obf_strings.py            # 字符串密文化（生成 work/obf-src + 自检类）
│   ├── check_stealth.py          # 四条隐身断言（dex / so / collide / libname）
│   ├── coexist.py                # 共存版：清单字符串池 + arsc 包名字段
│   ├── repack.py                 # 保序保压缩方式的流式重打包
│   ├── build_probe.ps1           # 遮挡探针构建（dev-only）
│   └── probe/                    # 探针源码（dev-only，不随发布包分发）
└── libs/
    ├── apktool_2.9.3.jar         # 不入库（见 7.1 的下载地址与 SHA-256）
    ├── official_cert.der/.b64    # 从 META-INF/H55_KEYS.RSA 提取的官方 X.509
    └── direct.keystore           # 不入库
```

---

## 11. 许可与声明

仅供本人对**自有设备上的游戏客户端**做内存结构研究之用。
仓库不包含游戏原始 APK 与官方密钥，构建需要自备原包与合法授权。
