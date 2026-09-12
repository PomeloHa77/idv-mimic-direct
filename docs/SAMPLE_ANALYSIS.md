# 拿到「别人的改包样本」时怎么用（对照分析清单）

场景：网上能找到的、据说**不是打几局就秒封**的直装样本。
它的价值不在于抄它的功能，而在于**它是一条活下来的样本**——可以直接和我们的产物对拍，
把「改包必封」这个判读从原理推断变成可对比的工程问题。

## 0. 安全纪律（先读这段）

这类「透视」包大量是**二次打包的盗号 / 远控 / 挖矿**载体。分析价值高，
但样本本身是**不可信输入**：

- 不装主力机、不装常用账号的设备；不给任何权限（尤其无障碍、悬浮窗、安装未知应用）；
- 顺序：**静态先行**（zip 结构 → jadx → so 哈希 → 清单），静态看不够再考虑动态；
- 要动态就在**一次性设备/模拟器 + 断网**上跑，绝不用它登录任何有价值的账号；
- 全程留哈希（sha256）存档，别覆盖原样本。

## 1. 十分钟内能拿到的硬事实

```powershell
# 0) 存档样本指纹
Get-FileHash 样本.apk -Algorithm SHA256

# 1) 包身份 + 清单权限（改动最直观的地方）
& "$BUILD_TOOLS\aapt2.exe" dump badging 样本.apk | Select-String "package:|uses-permission|application-label"

# 2) 签名证书（自签？还是别的）
& "$BUILD_TOOLS\apksigner.bat" verify --print-certs -v 样本.apk

# 3) 条目级差异：它到底动了哪些 zip 条目（本仓库自带）
python tools\compare_apks.py 官方包.apk 样本.apk --json sample-diff.json
```

`tools/compare_apks.py` 会输出：新增 / 删除 / 内容变化 的条目、按目录分组统计、
以及「第一现场」高亮（`classes*.dex`、`lib/*.so`、`assets/ntunisdk_so_uuids`、
`assets/probeSoMd5Record.txt`、`assets/emulatordetector_data`）和 v1 证书 SHA-256。

## 2. 逐项对照表（每一条都直接决定我们的方案边界）

| 看什么 | 怎么看 | 判读 |
|---|---|---|
| **签名** | `apksigner verify --print-certs` | 自签 → 与我们同类；若仍是官方证书 → 它没重打包（走了别的注入路径，性质完全不同） |
| **包名 / versionName** | `aapt2 dump badging` | 保留官方包名 = 直装；改名 = 共存 |
| **改动的 zip 条目** | `compare_apks.py` 第 1 节 | 它加了几个 dex / so？还是**原地替换**（把改过的 dex 用原名塞回去，条目数不变）？后者能让 `ZipPrint.getApkHash` 的条目集合变化更小 |
| **有没有动既有 `.so`** | 第 1 节 + 第 3 节 | 动了 `libsec-lib.so` / `libenvsdk.so` → 说明**反外挂本体是可以碰的**（这是我们当初自设的禁区） |
| **反外挂资产清单** | 第 3 节 | `assets/ntunisdk_so_uuids`、`probeSoMd5Record.txt` 被删/被改 → 说明作者踩过这两个文件，而且没死 |
| **权限集合** | `badging` 的 uses-permission diff | 有没有为了悬浮窗/读写加新权限（我们当初坚持不加） |
| **注入入口** | `jadx` 看 Application / 代理类 / `attachBaseContext` | 它是改 Application 还是改 Activity；有没有顺手把签名读取改成常量（我们做的 12 处回填它做了几处） |
| **上报链** | jadx 搜 `postDeviceData`、`matchConfigInfo`、`DiInfo`、`applicationInfoStr` | **最值得看的一项**：它有没有删/短路上报？有没有把上报内容换成官方值？「不秒封」的最可能解释就在这里 |
| **native 侧** | `.so` 哈希 diff；`llvm-nm --dynamic` 看导出 | 它有没有自己带 so、有没有替换反外挂 so |
| **动态痕迹** | 隔离设备上 `/proc/<pid>/maps`、`dumpsys window`、线程名 | 它加载了什么、有没有悬浮窗、进程里有几个可疑线程 |

## 3. 三种可能的结论，对应三条完全不同的路

| 对比结果 | 含义 | 我们要做什么 |
|---|---|---|
| **A. 自签重打包 + 动了反外挂 so / 掐了上报** | 「改包」不是必死，「不处理上报」才是必死 | 直装路可救：改的重点从「包体指纹」转到「上报与反外挂」，我们原来的两个不变量（不动 so、不动 assets 清单）要重写 |
| **B. 自签重打包 + 别的地方也没多动，就是能活** | 我们的归因**错了** | 回到抓上报 payload 的路子：把官方包与改包登录时的上报字段逐项 diff，找出真正被判的那一个 |
| **C. 它根本不是「改包」**（root 注入 / 虚拟化运行 / 云端代打 / 纯画面识别） | 「直装」只是宣传口径 | 我们的判读不变：免 root + 读内存仍然不可能，直装路维持"不可行" |

## 4. 不依赖样本的替代验证（随时可做）

在自己的包上做**上报内容对比**：root 设备上抓改包登录时的上报 payload
（`postDeviceData` / unifix 的 `configInfoStr`），与官方包的同名字段逐项 diff。
做完这一步，就算没有样本，也能直接看到「哪个字段不一样」——这才是最省账号成本的排查。
