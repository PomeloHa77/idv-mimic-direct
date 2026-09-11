# 第五人格「模仿者看身份」免 root 直装版（注入式）

把原本需要 root 的内存扫描器**直接注入游戏进程**，让扫描器跑在游戏自己的地址空间里，
通过 `/proc/self/maps` + `/proc/self/mem` 读自己的内存。因此：

* **不需要 root**，不需要 `process_vm_readv`、不需要 `/proc/pid/pagemap`；
* **不新增、不修改任何 `.so`** —— 反外挂会核对 `assets/ntunisdk_so_uuids` 与
  `assets/probeSoMd5Record.txt`，纯 Java 实现天然绕开这一层；
* 不需要改 `AndroidManifest.xml` —— 官方包**本来就声明了**
  `android.permission.SYSTEM_ALERT_WINDOW`，悬浮窗直接可用。

一句话原理：**别人读你的内存要 root，你自己读自己的内存不用。**

---

## 1. 产物

| 项 | 值 |
|---|---|
| 文件 | `out/第五人格-直装版-2026.0828.1653.apk` |
| 大小 | 2 012 868 324 B |
| SHA-256 | `fb3cb387911084dd3319e3b4721c398c0a509503bb430c415b080f765fc42844` |
| 包名 | `com.netease.dwrg`（与官方一致） |
| versionCode / versionName | `262401653` / `2026.0828.1653`（与官方一致） |
| minSdk / targetSdk | 21 / 30 |
| ABI | `arm64-v8a`（官方包就只有这一套） |
| 签名 | v1 + v2（自签名 `CN=fjdirect`，与原包同样的方案组合，v3 关闭） |

> 因为签名变了，**必须先卸载官方包**再装：
> ```powershell
> adb uninstall com.netease.dwrg
> adb install -r "out\第五人格-直装版-2026.0828.1653.apk"
> ```
> 卸载会清掉本地数据/缓存，登录要重新验证一次 —— 这是重打包的固有代价。

---

## 2. 对外接口（只有两个）

| 接口 | 用途 |
|---|---|
| `com.fj.direct.Boot.boot(Landroid/content/Context;)V` | smali 在 `attachBaseContext` 里调用，保存 Context 并投递悬浮窗创建 |
| `com.fj.direct.SigFix.sigs()[Landroid/content/pm/Signature;` | smali 替换官方读签名点，返回**官方证书** |

外加 `Boot.ensure()V`（在 `onCreate` 里兜底重试，无参无返回）。

---

## 3. 改动一览（改了什么、为什么）

### 3.1 清单 / 资源 / so：**零改动**

逐条对比原包与新包的 zip 中央目录（6073 → 6074 条）：

```
added   : META-INF/FJDIRECT.RSA, META-INF/FJDIRECT.SF, classes13.dex
removed : META-INF/H55_KEYS.RSA, META-INF/H55_KEYS.SF
顺序一致: True
内容有差异的条目(2): classes.dex, META-INF/MANIFEST.MF
```

除这两个 dex 与重签产生的 `META-INF` 外，**其余 6071 个条目 CRC / 压缩方式 / 大小完全一致**，
条目顺序逐条对齐（`assets/res/*.wpk` 那 400 MB 级 STORED 资源包原样搬运，否则资源加载会崩）。

### 3.2 `classes.dex`：smali 级最小改动（21 处）

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

**d) 渠道判定：确认无需改动（重要结论）**

`ApkChanneling.getChannel()` 会解析 **APK v2 签名块** 里 ID 为 `0xFF163163`
（`SignatureBlock$IdValue.CUSTOM_CHANNEL_ID = -0xe9ce9d`）的 `id-value` 作为渠道值。
离线解析原包的 v2 块后确认：

```
id=0x7109871a len=1491   ← 只有标准 v2 块
```

**原包里根本没有 `0xFF163163` 这个自定义块**，所以原包 `getChannel()` 返回
`null`，重签后 apksigner 写入的仍只有 `0x7109871a`（`checkV2()` 依旧为 true、
`getChannel()` 依旧返回 `null`）——**前后行为完全一致，无需硬编码渠道值**。

### 3.3 `classes13.dex`：新增（22 384 B，无 native）

与 `classes.dex` 分离编译（`javac --release 8` + `d8 --min-api 21`），
避开 64K 方法数/寄存器压力，也把回编译风险隔离在一个文件里。
游戏本身已是 multi-dex（`classes2..12.dex` 都在），minSdk 21 上 ART 会原生加载所有 `classesN.dex`，
不需要 `MultiDex.install()`。

| 类 | 职责 |
|---|---|
| `Boot` | 注入入口，保存 Context、投递悬浮窗 |
| `MemScanner` | 进程内扫描（`/proc/self/maps` + `/proc/self/mem`） |
| `RoleTable` | 角色索引 → 中文名（72 条映射，从 `身份.h` 整体移植） |
| `OverlayWindow` | `WindowManager` 悬浮窗：扫描/复制/收起按钮，可拖动，按阵营上色 |
| `SigFix` | 返回官方 `Signature[]`（DER 硬编码 base64） |

## 4. 扫描器细节（与 root 版行为对齐）

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
* 单次扫描字节上限 3 GB，防止极端情况下长时间占用；
* 12 个编号各留**首个**命中，集满 12 个提前结束；
* 全程后台线程（`FJDirect-scan`），按钮上显示耗时（ms）。

---

## 5. 构建

### 5.1 依赖

| 依赖 | 路径 / 下载 |
|---|---|
| JDK 17 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` |
| Android build-tools 36.0.0 | `E:\Android\Sdk\build-tools\36.0.0`（`aapt2/d8/apksigner/zipalign/dexdump`） |
| `android.jar` | `E:\Android\Sdk\platforms\android-35\android.jar` |
| apktool 2.9.3 | `libs/apktool_2.9.3.jar`，23 254 968 B<br>SHA-256 `7956eb04194300ce0d0a84ad18771eebc94b89fb8d1ddcce8ea4c056818646f4`<br>来源：`https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar` |
| 原始 APK | `E:\Dev\workspace\idv\netease_dwrg_20260903.apk`，2 012 889 355 B<br>SHA-256 `0683dd40388bb6fb1d58d4111f80909dc5a2d7a0af07a703f0de73e7e271a5c3` |

> **为什么用 apktool 的 jar 而不是 smali/baksmali**：Maven Central 上的
> `com.android.tools.smali` 已下架（404）。apktool 2.9.3 内部打包了
> `brut.androlib.src.SmaliDecoder` / `SmaliBuilder`，`tools/DexTool.java` 用反射直接驱动它们，
> 效果等价。注意 `com.android.tools.smali.smali.Main` **没有 main 方法**（继承 jcommander Command），
> 所以不能直接 `java -cp ... Main`，必须走 Decoder/Builder API。

### 5.2 一键构建

```powershell
pwsh -File build.ps1                 # 完整流程（反编译 → 补丁 → 编译 → 重打包 → 对齐 → 签名 → 校验）
pwsh -File build.ps1 -SkipDecompile  # 复用 work\smali，只重跑编译/打包/签名
pwsh -File build.ps1 -V2Only         # 只做 v2 签名（安装要求 Android 7+）
```

流水线 10 步：

1. `javac` 编译 `tools/DexTool.java`
2. 从原包抽出 `classes.dex`
3. `baksmali` 反编译 → `work/smali`（约 40 s，5345 个 `.smali`）
4. `tools/patch_dex.py` 打补丁（**幂等**，可反复运行）→ 12 个签名点 + 9 个 `SigningInfo` 点 + 2 个注入点
5. `smali --api 21` 回编译 → `work/classes.patched.dex`
6. `javac --release 8`（**必须带 `-encoding UTF-8`**，PowerShell 默认 GBK 会把中文源码编坏）+ `d8 --min-api 21` → `classes13.dex`
7. `tools/repack.py` 流式重打包：只换 `classes.dex`、紧随其后插入 `classes13.dex`，其余条目按字节搬运，条目顺序与压缩方式保持不变；`--drop-v1-signature` 丢掉旧的 `H55_KEYS.*`（否则残留旧签名文件会让 v1 校验失败）
8. `zipalign -f -p 4`（`resources.arsc` 是 STORED，必须 4 字节对齐）
9. `apksigner sign --v1 --v2 --min-sdk-version 21`
10. 三重校验：`apksigner verify -v --print-certs`、`zipalign -c -v 4`、`aapt2 dump badging`，再抽出 `classes13.dex` 校 magic 并用 `dexdump -f` 复核

> zipalign 会往 stderr 刷**上千行** `WARNING: header mismatch`（Android 的 zip 库对原包
> 自解压条目/数据描述符风格抱怨），是已知噪音，最后仍会打印 `Verification successful`。
> `build.ps1` 已把它重定向掉。

### 5.3 密钥

`libs/direct.keystore`（`alias=fjdirect`，`storepass=keypass=fjdirect`，PKCS12）由 `build.ps1` 首次运行时用 `keytool` 生成：

```
SHA-256: 80:65:1B:C5:39:7A:0B:C1:26:88:C2:F3:E4:5E:BE:88:72:F9:8C:3C:49:AC:69:86:0F:22:D9:8B:DE:59:A9:53
```

---

## 6. 验收

### 6.1 静态（已通过）

| 检查 | 结果 |
|---|---|
| `apksigner verify -v` | v1 `true` / v2 `true` / v3 `false`，单签名者 |
| `zipalign -c -v 4` | `Verification successful` |
| `aapt2 dump badging` | 包名 `com.netease.dwrg`、版本号不变、含 `SYSTEM_ALERT_WINDOW`、`native-code: 'arm64-v8a'` |
| `classes13.dex` magic | `dex\n035`，`dexdump -f` 解析正常 |
| 逆向复核（把成品 `classes.dex` 反编译回来再数） | `Boot;->boot(` = **1**、`Boot;->ensure(` = **1**、`SigFix;->sigs()` = **18**、残留 `SigningInfo;->` 调用 = **0**、残留 `PackageInfo;->signatures` 读取 = **0** |
| zip 逐条对比 | 仅 `classes.dex` 变化；条目顺序完全一致 |

### 6.2 真机

```powershell
adb uninstall com.netease.dwrg
adb install -r "out\第五人格-直装版-2026.0828.1653.apk"
# 设置 → 应用 → 第五人格 → 显示在其他应用上层 → 允许
adb logcat -s FJDirect
```

1. 启动能登录（验证签名回填：账号登录成功、支付页可打开）；
2. 进「模仿者」对局 → 点悬浮窗的「扫描」；
3. 验收：① 编号 1–12 各出现一次 ② 阵营配色正确（侦探团蓝 `#4FA8FF` / 狼人红 `#FF5A5A` / 神秘客黄 `#FFC93C`）③ 耗时正常（纯 Java 预计数百 ms–十几秒，显示在按钮结果区）④ `adb logcat -s FJDirect` 与悬浮窗内容一致。

### 6.3 失败定位判据

| 现象 | 判据 |
|---|---|
| 装不上 | 对齐/签名问题 → 重跑步骤 8/9；低版本设备改用 `-V2Only` 之外的方式（打开 v1）或反过来 |
| 闪退 | `adb logcat` 看 `avc:`（SELinux）/ ART 错误 |
| 登录报「应用校验失败」 | 签名点没补全 → 按第 3.2 节的表格补点（先看 `mpay` 与 `unifix` 两族） |
| 扫描结果为空 | 读取 `/proc/self/mem` 被拒 → 诊断行会打印具体异常；`adb logcat -s FJDirect` 里能看到 `不可读页跳过次数` 与错误信息 |
| 悬浮窗没出现 | 没授权 `SYSTEM_ALERT_WINDOW` → `logcat` 里会有 `创建悬浮窗失败` + Toast 提示；兜底结果写在 `/sdcard/Android/data/com.netease.dwrg/files/scan.txt` |

---

## 7. 已知边界（想清楚再动）

1. **不碰任何 `.so`**。反外挂会核对 `assets/ntunisdk_so_uuids`、`assets/probeSoMd5Record.txt`，
   所以彻底不做 JNI。若将来发现 `/proc/self/mem` 被 SELinux 拒绝而必须退到
   `process_vm_readv`，那就要新增一个极小的 native so —— **届时需要重新评估完整性校验的风险**。
2. **`libs/../*.so` 里的反外挂面不要动**：`libsec-lib.so`（TracerPid/su/Xposed/Substrate/模拟器）、
   `libenvsdk.so`（PCRE2 正则扫 maps）、`libybuaxx.so`（`Java_com_netease_ybuax_*` 风控）、
   `libsecsdk.so`（dex 分析/VMP）、`assets/emulatordetector_data`。本次改动只限 dex 层。
3. **`SkinSecurity` 未补丁（评估后判定无需补）**：它用 `JarFile`/`JarEntry.getCertificates()`
   直接读 **皮肤 APK 文件**（`new File(path)` → `AssetManager`）的 JAR 证书，比对内置
   allowlist `{d88039b9…, e54eb91a…}`；不涉及主包，最多影响皮肤加载。
   注意：这条路径读的是**真实 JAR 证书**，我们无法伪造（没有官方私钥），与本次登录/支付无关。
4. **`classes.dex` 字符串少了 10 条**：57039 → 57029，逐条 diff 确认只少了未被使用的
   debug 局部变量名（`baos / extJsonObj / initListner / isBindSuccess / jsonObjects / jsout /
   paramJsonObj / paramObj / strInputstream / ver`），无 extra、无功能影响。
5. **换包丢数据**：`adb uninstall` 会清掉本地数据/缓存，登录需重新验证 —— 重打包固有代价。
6. **smali 回编译是确定性输出**：同一棵 `work/smali` 连续编译两次 SHA-256 完全一致，
   所以"补丁是否真的进包了"可以用哈希对拍。

---

## 8. 仓库结构

```
idv-mimic-direct/
├── build.ps1                     # 一键流水线（10 步）
├── src/com/fj/direct/
│   ├── Boot.java                 # 注入入口
│   ├── MemScanner.java           # 进程内扫描器
│   ├── RoleTable.java            # 角色索引 → 中文名（72 条）
│   ├── OverlayWindow.java        # 悬浮窗 + 上色 + 复制/收起
│   └── SigFix.java               # 官方签名回填
├── tools/
│   ├── DexTool.java              # 反射驱动 apktool 的 SmaliDecoder/SmaliBuilder
│   ├── patch_dex.py              # smali 补丁（幂等）
│   └── repack.py                 # 保序保压缩方式的流式重打包
├── libs/
│   ├── apktool_2.9.3.jar         # 不入库，见 5.1 下载地址
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

## 9. 许可与声明

仅供本人对**自有设备上的游戏客户端**做内存结构研究之用。
仓库不包含游戏原始 APK 与官方密钥，构建需要自备原包与合法授权。
