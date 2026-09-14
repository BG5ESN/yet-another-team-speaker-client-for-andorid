# TS3 Android 客户端（内部定制版）

基于 [flamme-demon/TS6_Droid](https://github.com/flamme-demon/TS6_Droid)（原作者）经
[YUAXI/TS6_Droid_CN](https://github.com/YUAXI/TS6_Droid_CN)（简体中文版）裁剪定制的
TeamSpeak 3 安卓客户端。

使用 Jetpack Compose 构建，底层由 Rust 编写的 `tslib` 驱动。

> **这是内部定制版，不是面向公众的发行版。** 相比直接上游做了两件事：一是围绕语音通话做了一轮
> 音频与稳定性专项（修偶发闪退、抖动缓冲、语音激活门控、输出设备路由等）；二是按内部使用需求
> **裁掉了与通话无关的模块**（详见下方「已移除的功能」）。界面文案已去掉「二次元 / Han」等对外表述。

---

## 来源与致谢

本项目的全部基础来自这两位开发者的工作，请优先支持上游：

| 项目 | 作者 | 说明 |
|---|---|---|
| [TS6_Droid](https://github.com/flamme-demon/TS6_Droid) | **flamme-demon** | 原始 Android 客户端，Jetpack Compose + Rust `tslib` 架构 |
| [TS6_Droid_CN](https://github.com/YUAXI/TS6_Droid_CN) | **YUAXI** | 简体中文本地化版本，本项目直接基于它定制 |

上游中文版的贡献者：

<a href="https://github.com/YUAXI/TS6_Droid_CN/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=YUAXI/TS6_Droid_CN" />
</a>

本项目遵循 GPLv3，保留上游全部版权声明与许可证（见 [LICENSE](LICENSE)）。

---

## 已移除的功能

以下功能在直接上游中存在，但**本仓库的代码里已经没有**，因此下方历史更新日志中涉及它们的
描述仅作沿革记录，不代表当前可用：

| 功能 | 移除提交 | 说明 |
|---|---|---|
| 二次元 / 动漫背景 | `1e90efd` | `AnimeBackground.kt` 整个删除；不再联网拉取壁纸 |
| 自定义背景 | `1e90efd` | 相册选图 + 裁切界面（`CropScreen.kt`、`CustomBackgroundManager.kt`）整块删除 |
| 壁纸缓存管理 | `1e90efd` | `WallpaperCacheManager.kt` 删除，设置页相关条目一并去掉 |
| 应用内检查更新 | `681da28` | `UpdateChecker.kt` 删除；不再访问 GitHub Releases 查版本 |
| 应用内下载安装 APK | `681da28` | `InAppUpdater.kt` 删除；相关 `FileProvider` 路径与权限声明一并去掉 |
| 密聊（Whisper） | `21f38ef` | `WhisperManager.kt`、`WhisperBridge.kt` 删除；频道树与用户项相关入口去掉 |
| 「关于」页的对外内容 | `681da28` | 改为内部说明，去掉项目宣传与更新入口 |

因此，本文档的「功能特性」一节**只列当前代码里确实存在的功能**。

---

## 功能特性

### 语音通话

- **抖动缓冲 + 丢包隐藏**：按语音包号去重 / 重排 / PLC 补帧，抗网络抖动
- **播放时钟时间基**：按绝对 20ms 时隙推进，不依赖 `playbackHeadPosition`
  （部分机型该值恒为 0，会导致缓冲判断失效、彻底没声音）
- **语音激活门控（VA）真正生效**：静音时不编码、不上行；PTT 模式按住必发
- **说话圈**：本地按麦克风能量、远端按是否收到语音包判定，250ms 保持防逐字闪
- **输出设备选择**：通话中随时切换扬声器 / 听筒 / 有线 / 蓝牙（顶栏喇叭菜单）
- **通话独占声音开关**：开启时抢占音频焦点（音乐暂停）；关闭时与音乐并存
- **麦克风 VA 测试**：设置页按住测试电平，看它落在门限哪一侧（带峰值保持游标）
- **音量增益**：设置页与通话界面底栏两处可调，拖动实时生效

### 通话交互

- **全屏触摸 PTT**：PTT 模式下双指唤出，任意位置按住即说话，松开停止；期间屏幕常亮
- **悬浮窗**：其他应用上层显示当前说话人，支持开关
- **崩溃取证**：`Thread.UncaughtExceptionHandler` + `ApplicationExitInfo` 补记，
  毫秒级时间戳防同名覆盖，原生 trace 从 protobuf 二进制里提取可读片段

### 本地化

- 简体中文 100% 补齐（`zh-rCN`），支持中文 / English / Français 应用内切换

### 保留的通用能力

- 频道树、文字消息（频道 / 私聊）、文件传输与图片预览、头像与频道图标、消息本地持久化

---

## 更新日志

### v2.1.22（2026-09-13）

- 回退「输出通道」选项。真机验证证明该方案不成立：设备上 `mCommunicationStrategyId` 为 0，
  厂商把通信策略并入了媒体策略，换成 `USAGE_VOICE_COMMUNICATION` 后音频仍落在媒体那条
  mixer thread 上，与音乐互相牵连。收益为零、代价照旧，故整笔拆掉。

### v2.1.21（2026-09-13）

- 新增「输出通道」选项（媒体策略 / 通信策略）。**已在 2.1.22 撤回。**

### v2.1.20（2026-09-13）

- 音量增益搬到通话界面底栏：点开滑块，拖动实时生效、松手落盘（与设置页同一套刻度）

### v2.1.19（2026-09-13）

- 修采集侧路由：不再把输出设备硬塞给 `AudioRecord`（扬声器没有采集端，必然被系统拒绝），
  改为在输入设备列表里找对应项，找不到就交回系统默认
- 设备列表排掉 `TYPE_FM`（收音机，拿耳机线当天线）与 `TYPE_TELEPHONY`（虚拟设备）

### v2.1.18（2026-09-13）

- 新增「通话独占声音」开关：开启时抢占音频焦点（音乐暂停，原有行为）；关闭时不抢焦点，
  通话与音乐并存

### v2.1.17（2026-09-13）

- 新增通话音频输出设备选择：顶栏菜单可切换扬声器 / 听筒 / 有线 / 蓝牙，设备列表随插拔实时刷新
- 蓝牙的 A2DP 与 SCO 两条通道分别列出（前者音质好、后者麦克风可用）
- 记住选择；设备消失（拔耳机 / 蓝牙断开）自动回退「跟随系统」

### v2.1.16（2026-09-13）

- 回退「有人说话时音乐变轻」。真机反馈切到 duck 焦点后讲话没声音。
  **教训：焦点类型 `MAY_DUCK` 的语义是「我接受被别人压低」，方向搞反了。**

### v2.1.15（2026-09-13）

- 有人说话时让音乐变轻（动态 duck 焦点）。**已在 2.1.16 撤回。**

### v2.1.14（2026-09-13）

- 修通话中被音乐抢走焦点后**永久静音**：`AUDIOFOCUS_LOSS` 是永久失去，
  系统此后不会再回调 `GAIN`，必须自己重抢

### v2.1.13（2026-09-13）

- 拆掉悬浮窗说话人的三层延迟叠加（源 250ms + Service 500ms + Compose 500ms），
  悬浮图标响应从 ~540ms 降到 ~290ms

### v2.1.12（2026-09-13）

- 说话圈判据回到「流判定」（远端）：收到语音包即点亮，去掉解码能量那一层，
  避免抖动缓冲预填充带来的滞后

### v2.1.11（2026-09-13）

- **修偶发闪退**：`JitterBuffer` 的 `TreeMap` 被两条线程并发访问（写包在 service 的
  主线程、读/改在音频播放线程），红黑树被写坏后在 `fixAfterInsertion` 抛 NPE。
  加锁 + 计数器 `@Volatile`，并补并发回归测试（做过对照实验：解除锁即复现）

### v2.1.10（2026-09-13）

- 修 2.1.9 引入的严重回归：全屏 PTT 手势把所有抬起事件都吃掉了，
  导致「说话停不下来」且所有按钮失效

### v2.1.9（2026-09-13）

- 新增全屏触摸 PTT（双指唤出）+ PTT 模式下屏幕不熄灭

### v2.1.8（2026-09-13）

- 修悬浮窗卡在最后说话的人身上：防抖 job 被每 500ms 一次的 `users` 刷新反复取消重建，
  与 500ms 延迟同频导致永不完成

### v2.1.7（2026-09-13）

- 修悬浮窗圆形头像按下时露出的方框：`.clip(CircleShape)` 必须放在 `.clickable` 之前

### v2.1.6（2026-09-13）

- 崩溃报告不再攒成 `(1)(2)(3)`（时间戳精确到毫秒 + 写入前删同名）
- 原生 trace 从 protobuf 二进制里提取可读片段（此前直接按 UTF-8 转，67% 是乱码）
- 事件通道丢弃留痕（`droppedEvents` 计数 + 告警日志），消息消费移出主线程

### v2.1.5（2026-09-11）

- 修主线程 CPU 空转（`isLocalVoiceActive` getter 每次读都新建 `StateFlow`，
  Compose 反复取消/重建订阅）
- 应用名改为 TS3
- **内部化裁剪**：移除二次元壁纸 / 自定义背景整套、内部化「关于」页、移除应用内检查更新、去掉密聊
  （详见「已移除的功能」）

### v2.1.4-Han 及更早（上游沿革，仅作记录）

<details>
<summary>展开查看</summary>

> ⚠️ 以下是直接上游（`TS6_Droid_CN`）的历史条目。其中**自定义背景、壁纸缓存、应用内更新**
> 等功能已在本项目中移除，此处保留仅为沿革记录，**不代表当前可用**。详见「已移除的功能」。

#### v2.1.4-Han（2026-08-18）

- 昵称长度验证：连接服务器时校验昵称至少 3 个字符（中/英/法三语提示）

#### v2.1.3-Han（2026-07-28）

- TS3 Spacer 频道渲染：解析 `[cspacer]`、`[lspacer]`、`[rspacer]`、`[*spacer]` 标签

#### v2.1.2-Han（2026-07-15）

- *（已移除）* 自定义背景：相册上传 + 裁切预览
- 设置页改为卡片式布局：外观、音频、聊天、更多

#### v2.1.0-Han（2026-06-27）

- *（已移除）* 应用内更新：应用内下载并安装 APK、进度条显示

#### v2.0.1-Han（2026-06-26）

- 全项目 54 处 Flow 采集迁移至 `collectAsStateWithLifecycle`，降低后台 CPU 与耗电
- 背景淡入动画改用 `Modifier.graphicsLayer {}`，跳过 Composition 阶段

#### v2.0.0-Han（2026-06-26）

- Material 3 全面重构：Dynamic Color 动态取色（Android 12+）、15 级排版体系
- 新增 SplashScreen 启动界面、首页底部导航栏（主页 + 设置）
- *（已移除）* 壁纸缓存系统、自定义背景相关能力

</details>

---

## 构建

### 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | compileSdk 35（minSdk 29 / targetSdk 35） |
| Gradle | 随 wrapper 提供，无需单独安装 |

### 本地构建

```bash
export JAVA_HOME=/path/to/jdk17
export PATH="$JAVA_HOME/bin:$PATH"

# 直接出包（用仓库里预编译好的原生库）
./gradlew assembleDebug -x buildRustLibs

# 跑单元测试
./gradlew testDebugUnitTest -x buildRustLibs
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> `-x buildRustLibs` 是**必需**的 —— 除非你本地装了 Rust 工具链并克隆了上游
> `tslib` 源码，否则跳过它、直接使用 `jniLibs/` 里现成的 `.so`。

### 关于原生库（`jniLibs/`）

`app/src/main/jniLibs/<abi>/libtslib_jni.so` 是从上游 Rust 项目 `tslib` 编译出来的产物，
**故意提交到仓库**：这样 CI 和本地构建都能跳过 Rust 编译直接出包。

支持的 ABI：**arm64-v8a**、**x86_64**
（另外两个 ABI 目录里只有 AndroidX 的辅助库，没有 `libtslib_jni.so`）

需要重新编译原生库时，参考上游仓库的 Rust 构建说明。

### 云编译（GitHub Actions）

仓库已配置 `.github/workflows/android-build.yml`：

1. 推送到 `main` / `master`，或在 Actions 页面手动触发
2. 工作流用 JDK 17 + `./gradlew assembleDebug -x buildRustLibs` 打包
3. 在运行记录的 **Artifacts** 区域下载 `TS6-Han-Android-App`

---

## 关于签名

本仓库**不包含**签名文件（已在 `.gitignore` 中排除）。默认构建产物使用 debug 签名，
仅适合自用安装。

如需正式签名，在项目根目录生成自己的 keystore，并在 `app/build.gradle.kts` 中配置：

```bash
keytool -genkey -v -keystore release.keystore -alias <your-alias> \
        -keyalg RSA -keysize 2048 -validity 10000
```

> ⚠️ 不要把 keystore 和它的口令提交到仓库。

---

## 技术架构

底层 Rust 架构、本地编译环境搭建等技术细节，参考上游仓库：

- [flamme-demon/TS6_Droid](https://github.com/flamme-demon/TS6_Droid)
- [YUAXI/TS6_Droid_CN](https://github.com/YUAXI/TS6_Droid_CN)

---

## 开源许可

本项目遵循 **GNU GPLv3** 开源许可证，详见 [LICENSE](LICENSE)。

作为上游的衍生作品，本项目保留原许可证与全部版权声明，并按 GPLv3 要求公开全部源码。
