# 第五人格「模仿者看身份」免 root 直装版 / 共存版（注入式）

把原本需要 root 的内存扫描器**直接注入游戏进程**，让扫描器跑在游戏自己的地址空间里，
用 `/proc/self/maps` 取区域、用 `process_vm_readv` 读自己的内存
（Android 10+ 上 `/proc/self/mem` 被 SELinux 拒，细节见 4.4 节）。因此：

* **不需要 root**，不需要 `process_vm_readv`、不需要 `/proc/pid/pagemap`；
* **不新增、不修改任何 `.so`** —— 反外挂会核对 `assets/ntunisdk_so_uuids` 与
  `assets/probeSoMd5Record.txt`，纯 Java 实现天然绕开这一层；
* **不新增任何权限** —— 官方包**本来就声明了** `android.permission.SYSTEM_ALERT_WINDOW`，
  悬浮窗直接可用；
* **只改必要的字符串** —— 直装版只动 `classes.dex`；共存版额外改 33 条清单字符串
  与 2 条 dex 字符串（见第 2 节），组件、权限、`resources.arsc`、`.so` 全都不动。

一句话原理：**别人读你的内存要 root，你自己读自己的内存不用。**

两个产物：**共存版**（默认，包名 `com.netease.dwrg.fj`，可与官方客户端同时安装、同时登录）
和**直装版**（`-OriginalPackage`，包名与官方一致，需先卸载官方包）。见第 1 节。

---

## 1. 产物

两个模式，产物互不冲突：

| 项 | 共存版（默认） | 直装版（`-OriginalPackage`） |
|---|---|---|
| 文件 | `out/第五人格-共存版-2026.0828.1653.apk` | `out/第五人格-直装版-2026.0828.1653.apk` |
| 大小 | 2 012 868 396 B | 2 012 868 324 B |
| SHA-256 | `a0875e325f78c7c64e14ec93febbc458343c3f647476dfe009cffe4714072720` | `fb3cb387911084dd3319e3b4721c398c0a509503bb430c415b080f765fc42844` |
| 包名 | **`com.netease.dwrg.fj`** | `com.netease.dwrg`（与官方一致） |
| 与官方包共存 | 可以，可同时安装、同时登录 | 不行，必须先卸载官方包 |
| 安装命令 | `adb install -r "out\第五人格-共存版-2026.0828.1653.apk"` | `adb uninstall com.netease.dwrg` 后再 `adb install -r "out\第五人格-直装版-2026.0828.1653.apk"` |

两版共同点：

| 项 | 值 |
|---|---|
| versionCode / versionName | `262401653` / `2026.0828.1653`（与官方一致） |
| minSdk / targetSdk | 21 / 30 |
| ABI | `arm64-v8a`（官方包就只有这一套） |
| 签名 | v1 + v2（自签名 `CN=fjdirect`，与原包同样的方案组合，v3 关闭） |
| zip 条目数 | 共存版 6075（原包 6073） |

> 为什么两个都留着：**共存版**能在同一台机器上和官方客户端并排跑（对照、双开，
> 官方包继续用于支付/客服等场景）；**直装版**包名与官方完全相同，任何按 package name
> 硬编码的第三方回调（渠道统计、微信/QQ 分享回包里的 `package` 字段）都不会有偏差，
> 代价是必须先卸载官方包。
>
> 共存版**不需要**卸载任何东西，官方包与新包的本地数据也互不干扰
> （各自 `/data/data/<包名>` 与 `/sdcard/Android/data/<包名>`）。

---

## 2. 共存版：为什么改包名、改了什么、为什么只改这些

### 2.1 为什么必须改包名

Android 用 **package name 唯一标识一个应用**：同包名的第二个 APK 会被当成「同一个应用」，
走升级/替换逻辑，签名不同就直接 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
所以「共存」没有别的办法，只能换包名。

但换包名会连带一串必须一起换的东西 —— 凡是参与**系统级唯一性**或**进程自识别**的
字符串都要跟着改，否则轻则装不上、重则运行期行为错乱：

| 对象 | 不改的后果 |
|---|---|
| `<manifest package>` | 等于什么都没改 |
| `provider android:authorities` | `INSTALL_FAILED_CONFLICTING_PROVIDER`（authorities 全系统唯一） |
| 自定义 `<permission android:name>` | `INSTALL_FAILED_DUPLICATE_PERMISSION`（同名 permission 的定义可能不同） |
| `Manifest$permission.*` 常量 | 运行期用错 permission 名，动态注册的 receiver 收不到广播 |
| 进程名自匹配字符串 | 进程内统计/上报逻辑认不出自己 |

### 2.2 新包名为什么取 `com.netease.dwrg.fj`（超串）

`classes5.dex` 的 `Client$2.run` 会执行 `top` 命令解析自身进程行，用
`contains("com.netease.dwrg")` 判断「哪一行是我」，据此上报 CPU/RSS。

* 取**超串** `com.netease.dwrg.fj` → 该 `contains` **依旧命中**，`classes5.dex` 一行都不用改；
* 若取 `com.fj.dwrg` 之类 → 必须再动 `classes5.dex`，多一个改动面、多一份风险。

改得越少 = 越不容易崩，所以选超串。

### 2.3 清单：33 条改写 / 20 条保留

`python tools\coexist.py plan <原包>` 可复现下面这张表：

| 项 | 条数 | 例子 |
|---|---|---|
| `<manifest package>` | 1 | `com.netease.dwrg` → `com.netease.dwrg.fj` |
| provider `android:authorities` | 28 条唯一串（共 31 处属性） | `com.netease.dwrg.fileprovider` → `com.netease.dwrg.fj.fileprovider` |
| 自定义 `<permission>` | 2 条唯一串（共 3 处声明） | `com.netease.dwrg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`、`com.netease.dwrg.permission.ngpush` |
| 组件 `android:name`（判定为非类名） | 1 | `com.netease.dwrg.yxapi.YXEntryActivity` |
| 渠道回调标识 | 1 | `comccbpay105330173990048com.netease.dwrg` |
| **合计改写** | **33** | |
| **保留（真实类名）** | **20** | `com.netease.dwrg.Client`、`...Launcher`、`...wxapi.WXPayEntryActivity` 等 |

### 2.4 dex：只改 2 条字符串，绝不做前缀替换

```
com/netease/dwrg/Manifest$permission.smali
  DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION  "com.netease.dwrg"                  -> "com.netease.dwrg.fj"
  ngpush                                    "com.netease.dwrg.permission.ngpush" -> "com.netease.dwrg.fj.permission.ngpush"
```

> **为什么绝不能全树前缀替换**：smali 里 `Lcom/netease/dwrg/Foo;` 是**类型描述符**，
> 指向真实存在的类；全局替换会造出 5000+ 处指向不存在类的引用，直接
> `NoClassDefFoundError`。所以 dex 侧只认**带引号、整串相等**的字符串
> （`patch_dex.py` 的 `PKG_STRINGS`），清单侧的「是不是类名」则用
> **全 dex 类型描述符集合**判定：`"L" + s.replace(".", "/") + ";"` 在集合里 → 保留原样。

### 2.5 `resources.arsc` 里的包名**必须一起改**（真机踩过这个坑）

第一版共存版没改 `resources.arsc`，结果**点开游戏全程黑屏、进不去**。真机 logcat：

```
E netease.dwrg.fj: Invalid ID 0x00000000.
E AndroidRuntime: Process: com.netease.dwrg.fj, PID: 6992
E AndroidRuntime: java.lang.RuntimeException: Unable to start activity
    ComponentInfo{com.netease.dwrg.fj/com.netease.dwrg.Launcher}:
    android.content.res.Resources$NotFoundException: String resource ID #0x0
E AndroidRuntime: 	at com.netease.dwrg.Launcher.onCreate(Launcher.java:280)
I netease.dwrg.fj: System.exit called, status: 0
```

**原因**：`Resources.getIdentifier(name, type, defPackage)` 是按**包名**在资源表里查的
（`AssetManager.getResourceIdentifier()` 拿 `defPackage` 去匹配 `resources.arsc` 里
`ResTable_package` 的包名）。游戏里大量 SDK 代码写的是
`getIdentifier(xxx, "string", getPackageName())`，清单包名改成 `com.netease.dwrg.fj`
而 arsc 里还写着 `com.netease.dwrg` → 查不到 → 返回 **0** → 紧接着 `getString(0)`
抛 `Resources$NotFoundException` → `Launcher.onCreate` 直接挂，游戏 `System.exit(0)`。

**修法**（`coexist.py patch-arsc`）：`ResTable_package` 的包名是**定长字段**
（`uint16_t name[128]`，偏移 12、256 字节 UTF-16LE），所以原地覆盖写新包名 + 补零即可，
**不改任何长度、不动任何偏移、不碰 `assets/res/*.wpk` 的索引**，零副作用。
实测该 arsc 的全局字符串池里**含包名的字符串 0 条**（16216 条里一条都没有），
所以除了这个定长字段之外没有别处要改。

> 顺带记下这个教训：**「运行期靠 packageId 不靠包名」只对资源定位成立，
> 对 `getIdentifier` 这种按名字查的 API 不成立**。凡是改包名，arsc 的 package 名就得跟着改。

### 2.6 实测确认「不用改」的部分

| 项 | 结论 | 依据 |
|---|---|---|
| `BuildConfig.APPLICATION_ID` | 不用改 | 12 个 dex 全树零引用 |
| `Lcom/netease/dwrg/...` 类型描述符 | 不用改 | 是类名不是包名 |
| `"com.netease"` 前缀判断 | 不存在 | 精确匹配 `"com.netease"` 的字符串 **0 处**；170 处 `com.netease.X` 全是无关类名 |
| `ApkChanneling` 渠道 | 不用处理 | 原包 v2 块只有标准 `id=0x7109871a`，无 `0xFF163163` 自定义渠道块，`getChannel()` 前后都返回 `null` |
| 既有 `.so` / `assets/` / 其余条目 | 一个字节都不动 | 见第 4.1 节的 zip 逐条对比（只**新增**了我们自己的 `libmmread.so`，见第 4.4 节） |
| `resources.arsc` 的包名字段 | **必须改** | 见 2.5 节：不改会黑屏 |

### 2.7 共存性硬指标：authorities / permission 必须无交集

`build.ps1` 第 10 步会把官方包与新包都 `aapt2 dump xmltree` 出来逐条比对，有交集就
**直接抛异常中断构建**（不是「人工看一眼」）：

```
官方 authorities 31 条 / 自定义 permission 3 条
新包 authorities 31 条 / 自定义 permission 3 条
无交集 OK
```

一旦有交集，第二个包就会装不上（`CONFLICTING_PROVIDER` / `DUPLICATE_PERMISSION`）。

### 2.8 已知副作用（可接受）

1. `com.netease.dwrg.yxapi.YXEntryActivity` 会被一起改写。清单里写的是这个字符串，
   但真实类是 `Lim/yixin/sdk/api/BaseYXEntryActivity;`，过不了「类型描述符」判定，
   于是被当成包名字符串改写。该组件只响应 `yxapp://` scheme 拉起（易信一键登录），
   **改后这条路径失效**。权衡：不改则两包组件名完全相同（不同包名下同名组件本身不冲突，
   但会留下「两包组件全同」的隐患），所以选择改写。
   主流程（账号/手机/微信/QQ/游客登录）不受影响。
2. 两包共用同一个签名证书 `CN=fjdirect`（同一个 keystore 签两个包名），这是刻意的：
   以后要发新版本，用同一个 keystore 才能覆盖安装。

---

## 3. 对外接口（只有两个）

| 接口 | 用途 |
|---|---|
| `com.fj.direct.Boot.boot(Landroid/content/Context;)V` | smali 在 `attachBaseContext` 里调用，保存 Context 并投递悬浮窗创建 |
| `com.fj.direct.SigFix.sigs()[Landroid/content/pm/Signature;` | smali 替换官方读签名点，返回**官方证书** |

外加 `Boot.ensure()V`（在 `onCreate` 里兜底重试，无参无返回）。

---

## 4. 改动一览（改了什么、为什么）

### 4.1 清单 / 资源 / so：只改字符串与 arsc 包名字段，其余零改动

逐条对比原包与**共存版**新包的 zip 中央目录（6073 → 6075 条）：

```
added   : META-INF/FJDIRECT.RSA, META-INF/FJDIRECT.SF, classes13.dex,
          lib/arm64-v8a/libmmread.so
removed : META-INF/H55_KEYS.RSA, META-INF/H55_KEYS.SF
内容有差异的条目(4): AndroidManifest.xml, classes.dex, resources.arsc, META-INF/MANIFEST.MF
```

原有条目**顺序与字节完全一致**（`assets/res/*.wpk` 那 400 MB 级
STORED 资源包原样搬运，否则资源加载会崩），只有末尾旧的 `H55_KEYS.*` 被丢弃、
新的 `FJDIRECT.*` 由 `apksigner` 追加。

* 既有 `.so`、全部 `assets/` —— **一个字节都没动**；新增了一个我们自己的
  `lib/arm64-v8a/libmmread.so`（4 408 B，见 4.4 节）；
* `AndroidManifest.xml` —— **只在共存版有改动**（33 条字符串，见第 2.3 节）；直装版保持原字节；
* `resources.arsc` —— **只在共存版有改动**（只覆盖 256 字节的包名字段，文件长度不变，见 2.5 节）；
* `classes.dex` —— 两版都有改动（见 4.2 节）；`classes13.dex` —— 两版都是新增。

### 4.2 `classes.dex`：smali 级最小改动（共存版 23 处 / 直装版 21 处）

**a) 注入入口 2 处** —— `com/netease/ntunisdk/unifix_hotfix_library/proxyApplication/UFProxyApplication`

```smali
# attachBaseContext 的 super 调用之后
invoke-super {p0, p1}, Landroid/app/Application;->attachBaseContext(Landroid/content/Context;)V
+ invoke-static {p1}, Lcom/fj/direct/Boot;->boot(Landroid/content/Context;)V

# onCreate 的 super 调用之后
invoke-super/range {p0 .. p0}, Landroid/app/Application;->onCreate()V
+ invoke-static {}, Lcom/fj/direct/Boot;->ensure()V
```

选这个类是因为它是 SDK 的 **proxy Application**，一定早于游戏主逻辑执行。
`Boot` 内部用静态 flag 保证只初始化一次，所以即使注入点被多次执行也安全
（`patch_dex.py` 也做成了幂等，重复运行不会重复插入）。

**b) 签名回填 12 处** —— 把 `PackageInfo->signatures` 的读取换成 `SigFix.sigs()`

```smali
- iget-object vX, vY, Landroid/content/pm/PackageInfo;->signatures:[Landroid/content/pm/Signature;
+ invoke-static {}, Lcom/fj/direct/SigFix;->sigs()[Landroid/content/pm/Signature;
+ move-result-object vX
```

| 文件 | 处数 |
|---|---|
| `com/netease/mpay/d.smali` | 1 |
| `com/netease/mpay/p.smali` | 1 |
| `com/netease/mpay/login/c$c.smali` | 1 |
| `com/netease/ntunisdk/core/logs/Logger.smali` | 1 |
| `com/netease/ntunisdk/unifix/util/UniFixUtils.smali` | 4 |
| `com/netease/ntunisdk/unifix_hotfix_library/util/l.smali` | 4 |
| **合计** | **12** |

**c) 签名回填 9 处（API 28+ 的 `SigningInfo` 链路）** —— 这条链**必须一起补**。
`PackageInfo.signatures` 虽然被标 deprecated，但 API 28 起网易 SDK 会优先走
`PackageInfo.signingInfo` → `getApkContentsSigners()` / `getSigningCertificateHistory()`。
只补 `signatures` 等于在 Android 9+ 上直接把自签名暴露给校验逻辑。补了 9 处：

| 文件 | 改写方式 |
|---|---|
| `com/netease/ntunisdk/ngplugin/common/C.smali` | `l()`→`const/4 p0,0x0`；`m()`/`r()`→返回 `SigFix.sigs()` |
| `com/netease/ntunisdk/unifix/util/UniFixUtils.smali` | 两处内联 `getApkContentsSigners`/`getSigningCertificateHistory` → `SigFix.sigs()`；`hasMultipleSigners` → 常量 0 |
| `com/netease/ntunisdk/unifix_hotfix_library/util/l.smali` | 同上 |

`C.l/C.m/C.r` 是 `mpay/d`、`mpay/login/c$c` 读 `SigningInfo` 的唯一出口，改这三处即可覆盖支付/登录。

**d) 包名字符串 2 处（仅共存版）** —— `com/netease/dwrg/Manifest$permission.smali` 的两个静态字段：

```smali
- .field public static final DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION:Ljava/lang/String; = "com.netease.dwrg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
+ .field public static final DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION:Ljava/lang/String; = "com.netease.dwrg.fj.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
- .field public static final ngpush:Ljava/lang/String; = "com.netease.dwrg.permission.ngpush"
+ .field public static final ngpush:Ljava/lang/String; = "com.netease.dwrg.fj.permission.ngpush"
```

只做**带引号的完整字符串**精确替换（`patch_dex.py` 的 `PKG_STRINGS`），不做前缀替换 ——
理由见 2.4 节（类型描述符不能被改写）。

**e) 渠道判定：确认无需改动（重要结论）**

`ApkChanneling.getChannel()` 会解析 **APK v2 签名块** 里 ID 为 `0xFF163163`
（`SignatureBlock$IdValue.CUSTOM_CHANNEL_ID = -0xe9ce9d`）的 `id-value` 作为渠道值。
离线解析原包的 v2 块后确认：

```
id=0x7109871a len=1491   ← 只有标准 v2 块
```

**原包里根本没有 `0xFF163163` 这个自定义块**，所以原包 `getChannel()` 返回
`null`，重签后 apksigner 写入的仍只有 `0x7109871a`（`checkV2()` 依旧为 true、
`getChannel()` 依旧返回 `null`）——**前后行为完全一致，无需硬编码渠道值**。

### 4.3 `classes13.dex`：新增（25 488 B，纯 Java）

与 `classes.dex` 分离编译（`javac --release 8` + `d8 --min-api 21`），
避开 64K 方法数/寄存器压力，也把回编译风险隔离在一个文件里。
游戏本身已是 multi-dex（`classes2..12.dex` 都在），minSdk 21 上 ART 会原生加载所有 `classesN.dex`，
不需要 `MultiDex.install()`。

| 类 | 职责 |
|---|---|
| `Boot` | 注入入口，保存 Context、投递悬浮窗 |
| `MemScanner` | 进程内扫描（`/proc/self/maps` 取区域 + 4.4 节的两条读内存通道） |
| `MemReader` | 读内存统一入口：优先 native `process_vm_readv`，退回 `/proc/self/mem` |
| `RoleTable` | 角色索引 → 中文名（72 条映射，从 `身份.h` 整体移植） |
| `OverlayWindow` | `WindowManager` 悬浮窗：扫描/复制/收起按钮，可拖动，按阵营上色 |
| `SigFix` | 返回官方 `Signature[]`（DER 硬编码 base64） |

### 4.4 免 root 读内存：为什么最终必须带一个自研 `.so`

这是全流程里**唯一一次**对「不新增 `.so`」的有意放宽，原因是被真实系统挡死了：

**通道 A：`/proc/self/mem`（原方案）—— Android 10+ 不可用。**
真机（Redmi K20 Pro / Android 13 / SELinux Enforcing）实测：

```
打开 /proc/self/mem 失败: java.io.FileNotFoundException:
    /proc/self/mem: open failed: EACCES (Permission denied)
```

这是 SELinux 层面的拒绝（对 `untrusted_app` 直接不给 `open`），不是权限声明问题，
Java 侧无解。

**通道 B：`process_vm_readv(getpid(), ...)`（现方案）—— 免 root 可用。**
它走的是内核

```
process_vm_rw() → mm_access() → ptrace_may_access() → __ptrace_may_access()
```

而 `__ptrace_may_access()` 的第一句就是
`if (same_thread_group(task, current)) return 0;` —— **读「自己」在 LSM 检查之前
就直接放行**，所以不需要 root、也不需要 `ptrace` 权限。
（这是「读别人要 root、读自己不要」这句话在内核里的确切出处。）

实现：`src/native/mmread.c`（纯 C，40 行）→ NDK 编成 `lib/arm64-v8a/libmmread.so`，
**只有 4 408 字节、只导出 1 个符号** `Java_com_fj_direct_MemReader_readSelf`。
用 `syscall(__NR_process_vm_readv, ...)` 而不是链接 libc 的同名函数，
因为 bionic 从 API 23 才导出该符号，而我们 minSdk 21。

**为什么这次可以接受新增 `.so`**（评估过程，不是拍脑袋）：

| 反外挂资产 | 内容 | 新增一个自家 so 的影响 |
|---|---|---|
| `assets/ntunisdk_so_uuids` | 网易自家 so 的白名单（约 100 条 `路径 + uuid`） | 只是白名单，校验的是**名单内**文件；新增文件不在名单里，也不改动名单 |
| `assets/probeSoMd5Record.txt` | 网易自家 probe 库在 3 种编译参数下的 md5 | 同上，只覆盖自家文件 |

另外：**不动任何既有 `.so`**（`libsec-lib.so` / `libenvsdk.so` / `libsecsdk.so` 等
一个字节没改），.so 名中性、`/proc/self/maps` 里出现的路径与其他 lib 完全同形
（原包 `extractNativeLibs="true"`，所有 so 都在安装期解压到 `/data/app/.../lib/arm64/`）。

**降级链**：`libmmread.so` 加载失败（比如换了 ABI）→ 自动退回 `/proc/self/mem`
（Android 9 及以下本来就能用），两条都失败才报错。结果行里会直接标出用的是哪条通道。

真机实测（Redmi K20 Pro / Android 13）：

```
耗时 7196 ms | 通道 process_vm_readv | 区域 1182 | 读取 3072 MB | 命中 0/12
```

### 4.5 注入点会命中的**所有**进程：非主进程必须不注入（真机踩过这个坑）

注入点 `UFProxyApplication` 是这个 app 的 `android:name`，而 **Application 在 app 的每个进程里
都会被创建** —— 这个包里除了游戏主进程，还有一个 `:PushService`（网易推送，清单里声明为
`com.netease.pushservice.PushService` + `android:process=":PushService"`）。

不拦的后果（真机上就是这么踩的）：

* 两个进程各建一个**位置完全重叠、长得一模一样**的悬浮窗，你点到的很可能是推送进程那个；
* 而扫描器读的是 `/proc/self/maps`（即「自己这个进程」），在推送进程里读到的是**推送进程的
  地址空间**，一个角色都不会有 —— 表现为不管在不在对局，永远「未命中任何角色」。

所以 `Boot` 里加了主进程判定，判定不通过就**不注入**（不建悬浮窗）：

```java
// /proc/self/cmdline 全版本可用、零反射风险；取不到再退回 ActivityThread.currentProcessName()
String me  = processName();          // "com.netease.dwrg.fj" 或 "com.netease.dwrg.fj:PushService"
String pkg = ctx.getPackageName();   // "com.netease.dwrg.fj"
return me == null || pkg == null || me.equals(pkg);
```

两个进程各自的 `logcat` 长这样（真机 2026-09-11）：

```
09-11 16:09:24.625 13099 13099 I FJDirect: 进程判定：cmdline=com.netease.dwrg.fj 包名=com.netease.dwrg.fj 主进程=true
09-11 16:09:24.625 13099 13099 I FJDirect: Boot.boot 注入成功，context=true
09-11 16:09:25.691 13099 13099 I FJDirect: 悬浮窗已创建
09-11 16:11:59.371 20563 20563 I FJDirect: 进程判定：cmdline=com.netease.dwrg.fj:PushService 包名=com.netease.dwrg.fj 主进程=false
```

判定之后 `dumpsys window windows` 里属于本 app、`appop=SYSTEM_ALERT_WINDOW` 的窗口**始终只有 1 个**，
且它的 `mSession` 指向主进程 pid。

## 5. 扫描器细节（与 root 版行为对齐）

特征码、字段偏移、守卫**完全沿用** root 版 `模仿者遍历.cpp`：

```
i+0  ==105(i) i+1==100(d) i+2==120(x) i+5==99(c) i+6==97(a) i+14==105 i+23==105
+0x3  内存编号 0-11（对外 +1 → 1-12）
+0xC  阵营 1=侦探团 2=狼人 3=神秘客
+0x19 起 6 字节 identity，按阵营取第 (camp-1) 字节作为单字节角色索引
守卫：阵营必须 ∈{1,2,3}；编号必须 ≤11；侦探团/神秘客的索引 0 视为未初始化跳过，狼人的 0 合法
```

相比原版修掉的两个问题（**为什么这么改**）：

1. **跨块漏命中**：原版按 4096 整页扫描，正好横跨页边界的特征码会被漏掉。
   这里每次读 `CHUNK + 32` 字节、只扫前 `CHUNK` 个起点，块与块之间天然严丝合缝。
2. **越界读**：原版 `Name[i+23]` 在 `i` 接近缓冲区尾部时会读越界。
   这里用 `limit = n - 32` 收敛（`i+0x1B = i+27 < n`）。

其它工程化处理：

* 只扫 `rw` 权限、且 pathname 为空或 `[anon` 的区域（跳过文件映射，避免白读几百 MB 的 .so/.wpk）；
* 读失败（未驻留页 → `EIO`）按 4096 页步进跳过并计数，不中断整体扫描；
* 单次扫描字节上限 8 GB，防止极端情况下长时间占用（真机实测：3 GB 只要 7 s，
  原定的 3 GB 上限会在扫完之前被截断，故放宽到 8 GB）；
* **按区域分组取最优**：命中先按 `rw-anon` 区域各算一份（区域内按编号去重、取首个命中），
  最后采信「命中编号最多」的那个区域 —— 对齐 root 版按 `roleCount` 降序取模块的策略。
  为什么必须这么做：进程里同时存在上一局残留、序列化副本等干扰数据，全局「首个命中」
  很容易混进过期的编号；而角色数据是 12 个人共用的同一张表，按区域取最全的那份才对得上；
  某个区域集满 12 个编号就立刻结束扫描；
* **兜底**：没有任何区域到 5 个编号时（比如刚进对局、数据还没写全），退回
  「全局按编号去重取首个命中」，宁可少报也不空手；结果行会标出是「采信区域」还是
  「全局合并」，方便对着 `logcat` 区分来源；
* 全程后台线程（`FJDirect-scan`），按钮上显示耗时（ms）。

---

## 6. 构建

### 6.1 依赖

| 依赖 | 路径 / 下载 |
|---|---|
| JDK 17 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` |
| Android build-tools 36.0.0 | `E:\Android\Sdk\build-tools\36.0.0`（`aapt2/d8/apksigner/zipalign/dexdump`） |
| `android.jar` | `E:\Android\Sdk\platforms\android-35\android.jar` |
| apktool 2.9.3 | `libs/apktool_2.9.3.jar`，23 254 968 B<br>SHA-256 `7956eb04194300ce0d0a84ad18771eebc94b89fb8d1ddcce8ea4c056818646f4`<br>来源：`https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar` |
| NDK r27d | `E:\Dev\tool\NDK\android-ndk-r27d`（只用 `toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android21-clang.cmd`）<br>找不到时会读环境变量 `ANDROID_NDK_HOME` |
| 原始 APK | `E:\Dev\workspace\idv\netease_dwrg_20260903.apk`，2 012 889 355 B<br>SHA-256 `0683dd40388bb6fb1d58d4111f80909dc5a2d7a0af07a703f0de73e7e271a5c3` |

> **为什么用 apktool 的 jar 而不是 smali/baksmali**：Maven Central 上的
> `com.android.tools.smali` 已下架（404）。apktool 2.9.3 内部打包了
> `brut.androlib.src.SmaliDecoder` / `SmaliBuilder`，`tools/DexTool.java` 用反射直接驱动它们，
> 效果等价。注意 `com.android.tools.smali.smali.Main` **没有 main 方法**（继承 jcommander Command），
> 所以不能直接 `java -cp ... Main`，必须走 Decoder/Builder API。

### 6.2 一键构建

```powershell
pwsh -File build.ps1                  # 完整流程（默认 = 共存版，包名 com.netease.dwrg.fj）
pwsh -File build.ps1 -OriginalPackage # 直装版（包名与官方一致，需先卸载官方包）
pwsh -File build.ps1 -SkipDecompile   # 复用 work\smali，只重跑编译/打包/签名
pwsh -File build.ps1 -V2Only          # 只做 v2 签名（安装要求 Android 7+）
pwsh -File build.ps1 -OutName x.apk   # 自定义产物名
```

流水线步骤（编号与 `build.ps1` 的 `Step` 输出一一对应）：

1. `javac` 编译 `tools/DexTool.java`
2. 从原包抽出 `classes.dex`
3. `baksmali` 反编译 → `work/smali`（约 40 s，5345 个 `.smali`）
4. `tools/patch_dex.py` 打补丁（**幂等**，可反复运行）→ 2 个注入点 + 12 个签名点 + 9 个 `SigningInfo` 点（共存版再加 2 条包名字符串）
5. **（仅共存版）** `tools/coexist.py patch` 生成 `work/AndroidManifest.patched.xml`、`patch-arsc` 生成 `work/resources.patched.arsc`，交给第 9 步用 `--replace` 替换
6. `smali --api 21` 回编译 → `work/classes.patched.dex`
7. `javac --release 8`（**必须带 `-encoding UTF-8`**，PowerShell 默认 GBK 会把中文源码编坏）+ `d8 --min-api 21` → `classes13.dex`
8. **（NDK）** `aarch64-linux-android21-clang -shared -fPIC -O2 -s` 编译 `src/native/mmread.c` → `work/native/libmmread.so`
9. `tools/repack.py` 流式重打包：换 `classes.dex`、紧随其后插入 `classes13.dex`、`--add` 加 `lib/arm64-v8a/libmmread.so`、其余条目按字节搬运，条目顺序与压缩方式保持不变；`--drop-v1-signature` 丢掉旧的 `H55_KEYS.*`（否则残留旧签名文件会让 v1 校验失败）
10. `zipalign -f -p 4`（`resources.arsc` 是 STORED，必须 4 字节对齐）
11. `apksigner sign --v1 --v2 --min-sdk-version 21`
12. 校验：`apksigner verify -v --print-certs`、`zipalign -c -v 4`、`aapt2 dump badging`（**断言实际包名**）、
    与官方包的 authorities/permission **无交集断言**、`resources.arsc` 包名与 STORED 断言、
    `classes13.dex` magic + `dexdump -f`、`libmmread.so` 的 ELF machine/导出符号断言

> zipalign 会往 stderr 刷**上千行** `WARNING: header mismatch`（Android 的 zip 库对原包
> 自解压条目/数据描述符风格抱怨），是已知噪音，最后仍会打印 `Verification successful`。
> `build.ps1` 已把它重定向掉。

### 6.3 密钥

`libs/direct.keystore`（`alias=fjdirect`，`storepass=keypass=fjdirect`，PKCS12）由 `build.ps1` 首次运行时用 `keytool` 生成：

```
SHA-256: 80:65:1B:C5:39:7A:0B:C1:26:88:C2:F3:E4:5E:BE:88:72:F9:8C:3C:49:AC:69:86:0F:22:D9:8B:DE:59:A9:53
```

---

## 7. 验收

### 7.1 静态（已通过，`build.ps1` 第 12 步自动断言）

| 检查 | 结果 |
|---|---|
| `apksigner verify -v` | v1 `true` / v2 `true` / v3 `false`，单签名者 `CN=fjdirect` |
| `zipalign -c -v 4` | `Verification successful` |
| `aapt2 dump badging` | 共存版 `com.netease.dwrg.fj` / 直装版 `com.netease.dwrg`；版本号不变、含 `SYSTEM_ALERT_WINDOW`、`native-code: 'arm64-v8a'` |
| `resources.arsc` 包名 | `com.netease.dwrg.fj`（共存版），仍为 STORED、3 788 696 B 不变 |
| `classes13.dex` magic | `dex\n035`，25 488 B，`dexdump -f` 解析正常 |
| `libmmread.so` | aarch64 ELF、4 408 B、只导出 `Java_com_fj_direct_MemReader_readSelf` |
| 逆向复核（把成品 `classes.dex` 反编译回来再数） | `Boot;->boot(` = **1**、`Boot;->ensure(` = **1**、`SigFix;->sigs()` = **18**、残留 `SigningInfo;->` 调用 = **0**、残留 `PackageInfo;->signatures` 读取 = **0**、残留旧包名字符串 = **0** |
| zip 逐条对比 | 共存版：`AndroidManifest.xml` / `classes.dex` / `resources.arsc` 变化，新增 `classes13.dex`、`libmmread.so`、`FJDIRECT.*`；原有条目顺序与字节完全一致（见 4.1 节） |
| 清单往返解析 | `aapt2 dump xmltree` / `dump badging` 均成功；与官方清单逐行 diff 45 行，**全部是预期内的包名改写** |
| 共存性 | 官方 / 新包 authorities 31 / 31、自定义 permission 3 / 3，**无交集** |
| AXML 字符串池不变量 | 原/新 flags 均为 `0x00000000`（无排序标志、UTF-16LE）、`stringsStart = 2664`、无 style、offsets 单调 |

### 7.2 真机

**共存版（推荐：官方包原样保留）**

```powershell
adb install -r "out\第五人格-共存版-2026.0828.1653.apk"    # MIUI 会拦 adb install，可改用 root：
# adb push "out\第五人格-共存版-2026.0828.1653.apk" /data/local/tmp/idv_fj.apk
# adb shell 'su -c "pm install -r /data/local/tmp/idv_fj.apk"'
# 设置 → 应用 → 找到新装的那个（图标/名称与官方相同，看应用详情的包名是不是 com.netease.dwrg.fj）
#      → 显示在其他应用上层 → 允许
adb logcat -s FJDirect
```

**直装版（会顶掉官方包）**

```powershell
adb uninstall com.netease.dwrg
adb install -r "out\第五人格-直装版-2026.0828.1653.apk"
```

1. **共存性**：官方客户端与新装的那个**同时存在**，都能启动、都能登录同一个账号、互不挤掉对方；
2. 启动能登录（验证签名回填：账号登录成功、支付页可打开）；
3. 进「模仿者」对局 → 点悬浮窗的「扫描」；
4. 验收：① 编号 1–12 各出现一次 ② 阵营配色正确（侦探团蓝 `#4FA8FF` / 狼人红 `#FF5A5A` / 神秘客黄 `#FFC93C`）③ 耗时正常（纯 Java 预计数百 ms–十几秒，显示在结果区）④ `adb logcat -s FJDirect` 与悬浮窗内容一致。

**实测记录（2026-09-11，Redmi K20 Pro / Android 13 / MIUI 13 / SELinux Enforcing）**

| 项 | 结果 |
|---|---|
| 安装 | `su -c "pm install -r /data/local/tmp/idv_fj.apk"` → `Success`（MIUI 会拦 `adb install`，走 root 装即可） |
| 与官方共存 | 设备上的官方包是 **4399 渠道版 `com.netease.dwrg.m4399`**，与 `com.netease.dwrg.fj` 互不影响，两个客户端都在 |
| 启动 | 正常进到游戏（登录界面 + 维护公告），**黑屏已消失** |
| 悬浮窗 | `FJDirect: 悬浮窗已创建`，屏幕上左上角显示「模仿者·直装 / 扫描 / 复制」 |
| 悬浮窗归属 | 属于本 app 的 `SYSTEM_ALERT_WINDOW` 窗口**只有 1 个**，`mSession` = 主进程 pid；`:PushService` 进程不再建窗（见 4.5 节） |
| 扫描（不在对局） | `耗时 2384 ms｜通道 process_vm_readv｜区域 457｜读取 1244 MB｜命中 0/12`（整轮扫完、没触发上限；不在对局所以没有命中，符合预期） |
| 扫描（游戏加载中） | `耗时 6877 ms｜通道 process_vm_readv｜区域 1293｜读取 3475 MB｜命中 0/12`（同上，未触发 8 GB 上限） |
| 兜底文件 | `/storage/emulated/0/Android/data/com.netease.dwrg.fj/files/scan.txt` 同步写出结果，内容与悬浮窗一致 |
| 待你验证 | 登录进游戏 → 进「模仿者」对局 → 点「扫描」，看是否出现编号 1–12 |

### 7.3 失败定位判据

| 现象 | 判据 |
|---|---|
| 装不上 | 对齐/签名问题 → 重跑步骤 8/9；低版本设备改用 `-V2Only` 之外的方式（打开 v1）或反过来 |
| 闪退 | `adb logcat` 看 `avc:`（SELinux）/ ART 错误 |
| 登录报「应用校验失败」 | 签名点没补全 → 按第 4.2 节的表格补点（先看 `mpay` 与 `unifix` 两族） |
| 扫描结果为空 | 先看结果行的「通道」：应为 `process_vm_readv`。若显示 `/proc/self/mem` 且报 EACCES，说明 `libmmread.so` 没加载成功（见 4.4 节）；若「区域 0」，看 `adb logcat -s FJDirect` 的诊断行 |
| 悬浮窗没出现 | 没授权 `SYSTEM_ALERT_WINDOW` → `logcat` 里会有 `创建悬浮窗失败` + Toast 提示；兜底结果写在 `/sdcard/Android/data/<包名>/files/scan.txt`（共存版是 `com.netease.dwrg.fj`） |

---

## 8. 已知边界（想清楚再动）

1. **不动任何既有 `.so`，只新增一个自研 `.so`**（`lib/arm64-v8a/libmmread.so`，4 408 B）。
   这是被系统逼出来的：Android 10+ 上 `/proc/self/mem` 被 SELinux 拒（实测 EACCES），
   只剩 `process_vm_readv` 这一条免 root 的路，而它需要 native 代码（详见 4.4 节）。
   反外挂的两个资产 `assets/ntunisdk_so_uuids`、`assets/probeSoMd5Record.txt` 都是
   **网易自家 so 的白名单/md5 记录**，新增一个不在名单里的 so 不改动、也不违反它们；
   但如果将来发现被 `libenvsdk.so` 的 maps 正则扫出来（会打 `FJDirect` 之外的日志、
   或游戏直接启动失败），**回退方案**是：把 so 改名成更像系统库的名字、
   或者改用 `dlopen` 从 `base.apk` 内直接加载（`extractNativeLibs="true"` 下不可行，
   需改清单）、或者退回「只支持 Android 9 及以下用 /proc/self/mem」。
2. **既有 `.so` 里的反外挂面一个字节都别碰**：`libsec-lib.so`（TracerPid/su/Xposed/Substrate/模拟器）、
   `libenvsdk.so`（PCRE2 正则扫 maps）、`libybuaxx.so`（`Java_com_netease_ybuax_*` 风控）、
   `libsecsdk.so`（dex 分析/VMP）、`assets/emulatordetector_data`。
3. **`SkinSecurity` 未补丁（评估后判定无需补）**：它用 `JarFile`/`JarEntry.getCertificates()`
   直接读 **皮肤 APK 文件**（`new File(path)` → `AssetManager`）的 JAR 证书，比对内置
   allowlist `{d88039b9…, e54eb91a…}`；不涉及主包，最多影响皮肤加载。
   注意：这条路径读的是**真实 JAR 证书**，我们无法伪造（没有官方私钥），与本次登录/支付无关。
4. **`classes.dex` 字符串少了 10 条**：57039 → 57029，逐条 diff 确认只少了未被使用的
   debug 局部变量名（`baos / extJsonObj / initListner / isBindSuccess / jsonObjects / jsout /
   paramJsonObj / paramObj / strInputstream / ver`），无 extra、无功能影响。
5. **直装版换包丢数据**：直装版必须先 `adb uninstall` 官方包，会清掉本地数据/缓存，登录要重新验证；
   **共存版没有这个问题** —— 新包名意味着全新的数据目录，官方包的数据与登录态原样保留。
6. **smali 回编译是确定性输出**：同一棵 `work/smali` 连续编译两次 SHA-256 完全一致，
   所以"补丁是否真的进包了"可以用哈希对拍。

---

## 9. 仓库结构

```
idv-mimic-direct/
├── build.ps1                     # 一键流水线，12 步（默认共存版；-OriginalPackage 切直装版）
├── src/native/
│   └── mmread.c                  # process_vm_readv 自读（免 root 的关键，40 行 C）
├── src/com/fj/direct/
│   ├── Boot.java                 # 注入入口
│   ├── MemScanner.java           # 进程内扫描器（/proc/self/maps + 两条读通道）
│   ├── MemReader.java            # 读内存统一入口：native 优先、文件兜底
│   ├── RoleTable.java            # 角色索引 → 中文名（72 条）
│   ├── OverlayWindow.java        # 悬浮窗 + 上色 + 复制/收起
│   └── SigFix.java               # 官方签名回填
├── tools/
│   ├── DexTool.java              # 反射驱动 apktool 的 SmaliDecoder/SmaliBuilder
│   ├── patch_dex.py              # smali 补丁（幂等）
│   ├── coexist.py                # 共存版：清单字符串池重写（33 改 / 20 留）+ arsc 包名字段
│   └── repack.py                 # 保序保压缩方式的流式重打包
├── libs/
│   ├── apktool_2.9.3.jar         # 不入库，见 6.1 下载地址
│   ├── official_cert.der         # 从 META-INF/H55_KEYS.RSA 提取的官方 X.509（845 B）
│   ├── official_cert.b64
│   └── direct.keystore           # 不入库
└── .gitignore                    # *.apk / work/ / out/ / *.keystore / *.jar
```

官方证书指纹（供核对，`libs/official_cert.der`）：

```
Subject/Issuer: CN=dwrg, OU=dwrg, O=dwrg, L=hz, ST=zj, C=cn
序列号: 758d51d5     算法: SHA256withRSA     有效期: 2017-11-13 → 2072-08-16
SHA-256: 918e39b4e77e4e1e03a7c0236c6f473037851069c67b5ebecf60e9b9744e4dc9
SHA-1  : b7cb8a61d0b7e0bbdcd8f4a5b12710544cd1c14e
MD5    : 08e1a6f478f1ac2098edf5125de5655b
```

> 提取方式：`META-INF/H55_KEYS.RSA` 是 PKCS#7，证书在**偏移 60、长度 845**。
> 该文件没有 PEM 头，所以 `keytool -printcert` 会报"无法解析输入"，用 Java
> `CertificateFactory` 或 `new Signature(der)` 都能正常解析（已实测）。

---

## 10. 许可与声明

仅供本人对**自有设备上的游戏客户端**做内存结构研究之用。
仓库不包含游戏原始 APK 与官方密钥，构建需要自备原包与合法授权。
