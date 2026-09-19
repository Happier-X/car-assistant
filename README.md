# 车机助手 · Car Assistant

为**雪佛兰科鲁泽 2024 款（MyLink 车机）** 打造的开机自动化助手：

> 车机启动时 → 自动打开 WiFi → 自动拉起「亿连车机版」

---

## 一、为什么这个需求比看起来难

「开机开 WiFi + 启动 App」听起来是两行代码的事，但 Android 从 9 到 14 一路收紧权限，
到车机上（ROM 千奇百怪、没有 GMS、Android 版本跨度大）就变成了一个**多策略降级**问题。

### 难点 1：Android 10 起，普通 App 无法打开 WiFi

| Android 版本 | `setWifiEnabled()` 的行为 |
|---|---|
| ≤ 9 (API 28) | 正常可用 |
| 10 (API 29) | **targetSdk ≥ 29 时直接返回 false** |
| 10+ | 引入「软开关」，设置页开关 ≠ 射频真实状态 |
| 12+ | 部分车机 ROM 把 `WifiManager` 的 set 接口整体封死 |

**本项目的对策：`targetSdk = 28`。**

这不是偷懒，而是**刻意的设计决策**。车机是纯侧载场景（不进 Google Play），
不受 targetSdk 政策约束，因此可以用这个「后门」让 `setWifiEnabled()` 在
Android 10/11/12/13 上继续生效 —— 这是无 root 情况下唯一能真正静默开 WiFi 的途径。

### 难点 2：Android 10 起，后台无法启动 Activity

开机自动拉起 App 恰好是最典型的「后台启动」场景，会被系统静默丢弃，
logcat 里能看到 `Background activity start ... blocked`。

### 难点 3：亿连的包名未知

不同版本、不同渠道的亿连包名不一样，硬编码必然失效。

**本项目的对策：做成应用扫描器**，列出车机上所有已安装应用（含系统预装、
无 launcher 入口的隐藏应用）供用户点选，见「启动项」页。

---

## 二、解决方案：多级降级链

核心思路：**不赌单一方案**。按「成功率 × 侵入性」排序，逐级尝试，
**每级执行后都真实复查状态，成功即停止，绝不盲目往下走、也绝不虚报成功。**

### WiFi 开启策略链

| 序 | 策略 | 前置条件 | 说明 |
|---|---|---|---|
| S1 | `setWifiEnabled(true)` | targetSdk < 29 ✅ | 静默、无弹窗，首选 |
| S2 | 反射隐藏接口 | 部分 ROM 需要 | 绕过 ROM 的封装检查 |
| S3 | `su -c "svc wifi enable"` | root | 最经典的 root 方式 |
| S4 | `su -c "cmd wifi set-wifi-enabled enabled"` | root | Android 10+ 新命令 |
| S5 | `Settings.Global.WIFI_ON = 1` | WRITE_SECURE_SETTINGS | adb 可授予 |
| S6 | 无障碍服务代点设置页开关 | 无障碍服务 | 最终兜底 |

### 应用启动策略链

| 序 | 策略 | 前置条件 | 说明 |
|---|---|---|---|
| A1 | `startActivity` + NEW_TASK | 系统未拦截 | 最干净 |
| A2 | `su -c "am start -n pkg/cls"` | root | |
| A3 | `su -c "am start --user 0 ..."` | root + 多用户 | 车机 profile 场景 |
| A4 | `su -c "monkey -p pkg ..."` | root | 不依赖 Activity 名，最鲁棒 |
| A5 | 无障碍服务 `startActivity` | 无障碍服务 | **不受后台启动限制** |
| A6 | 全屏 Intent 通知 | — | 提示用户一键打开 |

> **为什么无障碍服务是最强兜底**：
> `AccessibilityService.startActivity()` 不受后台启动限制约束，
> 这是 Android 10+ 上无 root 拉起应用最可靠的途径。
> 它同时还能代点 WiFi 开关，一举两得。

---

## 三、技术栈

全部为 **2026 年最新稳定版**：

| 组件 | 版本 |
|---|---|
| Android Gradle Plugin | **9.4.0** |
| Gradle | **9.6.1** |
| Kotlin | **2.4.20** |
| Jetpack Compose BOM | **2026.09.00**（Compose 1.12.1 / Material3 1.4.0）|
| compileSdk / targetSdk / minSdk | 37 / **28** / 26 |
| Java | 17 |

架构：**纯 Kotlin + Compose + Coroutines Flow + DataStore + kotlinx.serialization**

几个值得说明的技术选型：

- **AGP 9.x 内置 Kotlin 支持**：不再需要 `org.jetbrains.kotlin.android` 插件，
  重复应用会直接报错（这是 AGP 9 的行为变更）。
- **零第三方依赖**：除了必要的 AndroidX/Compose，没有引入任何第三方库。
  release APK 仅 **1.5 MB**。
- **自绘图标**：`material-icons-extended` 有 30MB+，会把 debug APK 从 2MB 撑到 20MB。
  我们只用 20 个图标，因此手写了矢量图（`ui/icons/CarIcons.kt`），
  路径数据取自 Material Symbols 官方图标集。
- **DataStore 而非 SharedPreferences**：类型安全、天然 Flow、事务性写入。

---

## 四、项目结构

```
app/src/main/java/com/carassistant/
├── CarAssistantApp.kt              # Application 入口（只做必要初始化）
├── core/                           # 与 UI 无关的核心逻辑
│   ├── Models.kt                   # 数据模型（LaunchTarget / BootReport / 策略枚举）
│   ├── RunLog.kt                   # 运行日志（内存 + 落盘 + logcat 三路输出）
│   ├── ShellRunner.kt              # Shell 执行器（suspend + 硬超时 + root 探测）
│   ├── WifiController.kt           # ★ WiFi 多策略降级链
│   ├── AppLauncher.kt              # ★ 应用启动多策略降级链 + 应用扫描
│   ├── BootFlowOrchestrator.kt     # ★ 流程编排（去重 + 容错 + 状态推送）
│   ├── Notifications.kt            # 通知（前台服务存活 + 全屏 Intent 兜底）
│   ├── AccessibilityBridge.kt      # 无障碍服务解耦桥接
│   └── Permissions.kt              # 权限/能力检测集中入口
├── data/
│   └── SettingsRepository.kt       # 配置仓库（DataStore + JSON 序列化）
├── service/
│   ├── AssistantService.kt         # 执行前台服务（保证开机时不被回收）
│   └── AssistAccessibilityService.kt # ★ 无障碍服务（代启动 + 代点 WiFi 开关）
├── receiver/
│   └── TriggerReceiver.kt          # 广播触发器（多触发源 + 快速返回）
└── ui/
    ├── MainActivity.kt             # 主界面（4 个标签页）
    ├── MainViewModel.kt            # 状态聚合
    ├── icons/CarIcons.kt           # 自绘矢量图标
    ├── theme/                      # 车机配色与字体（深色 + 高对比 + 大字号）
    ├── common/Components.kt        # 通用组件（56dp 热区）
    └── screens/
        ├── HomeScreen.kt           # 状态页（配置摘要 + 测试按钮 + 环境自检）
        ├── AppsScreen.kt           # ★ 启动项配置 + 应用选择器
        ├── SettingsScreen.kt       # 设置页
        └── LogsScreen.kt           # ★ 运行日志（车机上的核心调试工具）
```

---

## 五、构建与安装

### 环境要求

- JDK 17+（Android Studio 自带的 JBR 即可）
- Android SDK（compileSdk 37 平台）

### 构建

```bash
# Windows
gradlew.bat :app:assembleDebug

# Linux / macOS
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 安装到车机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

车机没开 USB 调试的话，可以先把 APK 拷到 U 盘，用车机的文件管理器安装。

### 安装后必做（重要）

**第一步：授予 WRITE_SECURE_SETTINGS。**

这是无 root 情况下开 WiFi 的关键后路。用 adb 执行一次即可（重启不失效）：

```bash
adb shell pm grant com.carassistant android.permission.WRITE_SECURE_SETTINGS
```

**第二步：开启无障碍服务。**

在 App 里点「设置 → 开启无障碍服务」，或手动进入
`系统设置 → 无障碍 → 已安装的服务 → 车机助手（启动兜底）`。

它的作用：
1. 静默开 WiFi 失败时，自动点击设置页里的 WiFi 开关
2. 后台启动 App 被系统拦截时，代为启动（不受后台启动限制）

**第三步：加入电池优化白名单。**

避免流程执行到一半被系统冻结。

**第四步：在「启动项」页添加亿连。**

点「添加应用」→ 搜索「连」或「Link」→ 选中 → 它会被加到启动列表。

> 如果列表里找不到亿连，点「手动输入包名」直接填入。
> 想知道包名可以执行：`adb shell pm list packages | grep -i link`

---

## 六、验证与调试

### 不重启车机就能测试

App 首页有两个按钮：
- **执行一次完整流程** —— 走完整链路（WiFi + 所有启动项）
- **只测试打开 WiFi** —— 单独验证 WiFi 这一环，并打印每一级策略的详细结果

### 日志页

车机上连 adb 抓 logcat 很麻烦（要拆中控、找 USB），所以完整执行日志做进了 App：

- 按级别着色（成功绿 / 失败红 / 里程碑蓝）
- 每一步都有记录：试了哪级策略、返回什么、复查结果如何
- 可「复制全部」后发出来排查
- 「导出当前窗口结构」可以 dump 当前界面的控件树，
  用于分析你车机 ROM 的设置页长什么样

日志同步写入 `filesDir/car_assistant.log`，超过 512KB 自动滚动。

---

## 七、常见问题

**Q：开机后 WiFi 没开，日志显示「静默方式全部失败」**

说明 `setWifiEnabled()` 在你的车型上被 ROM 封死了。检查：
1. 无障碍服务是否开启？（对应 S6 兜底）
2. 是否授予了 `WRITE_SECURE_SETTINGS`？（对应 S5）

**Q：日志显示「Root 权限：不可用」，但我的车机已经 root 了**

不同车机的 `su` 位置和参数不同。项目里已探测 8 种常见组合
（`su -c` / `su 0` / `su root -c` / `/system/xbin/su` …）。
如果都不行，在首页点「重新检测 root」再试。日志里会显示具体试过哪些路径。

**Q：亿连启动了但又退到后台，被后面启动的 App 顶掉了**

多个 App 依次启动时，只有最后一个会留在前台。解决：
在「启动项」页把亿连**移到第一位**，并把它的延时设为 **「立即启动」**。

**Q：开机广播没触发流程**

车机开机流程千差万别。本项目监听了 7 种触发源：
`BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `QUICKBOOT_POWERON`（熄火不断电的车机）/
`MY_PACKAGE_REPLACED` / `POWER_CONNECTED` / `DOCK_EVENT` / `SCREEN_ON`。

如果都不触发，去「设置 → 触发时机」把**「屏幕点亮时」**也打开
（代价是每次亮屏都会执行一次，已做 20 秒去重）。

**Q：启动后出现「无法自动启动 亿连」的通知**

这是 A6 兜底：所有静默手段都被系统拦截了，至少给你一个一键打开的入口。
请开启无障碍服务（对应 A5），它不受后台启动限制。

---

## 八、设计取舍说明

几个刻意的选择，避免后来者困惑：

1. **`targetSdk = 28` 不是没升级，是故意锁的。** 见第一节难点 1。

2. **前台探测失败时乐观返回「已在前台」。** 车机上探测手段（root dumpsys /
   无障碍事件）都可能不可用。此时若返回 false，会触发所有降级策略白跑一遍，
   甚至把已经正常打开的 App 再重启一次。宁可漏报，不可误判。

3. **WiFi 失败不阻断 App 启动。** 用户核心诉求是「上车就能看到亿连」，
   WiFi 只是前置条件。失败也继续，并在报告里如实标注。

4. **流程去重窗口 20 秒。** 开机时会收到多个广播，不去重会连续拉起好几次 App。

5. **`START_NOT_STICKY`。** 服务被杀后不自动重启 —— 开机场景下重启只会造成重复执行。

6. **不使用 `applicationIdSuffix` 区分 debug/release。** 包名必须一致，
   否则 adb 授予的 `WRITE_SECURE_SETTINGS` 在 debug 包上不生效。

---

## 九、许可

本项目为个人车机改造用途。图标路径数据来自
[Material Symbols](https://fonts.google.com/icons)（Apache License 2.0）。