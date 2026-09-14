# YATS3

**Y**et **A**nother **TS3** —— 基于
[flamme-demon/TS6_Droid](https://github.com/flamme-demon/TS6_Droid)（原作者）经
[YUAXI/TS6_Droid_CN](https://github.com/YUAXI/TS6_Droid_CN)（简体中文版）裁剪定制的
TeamSpeak 3 安卓客户端。使用 Jetpack Compose 构建，音频链路由 Rust 编写的 `tslib` 驱动。

本仓库为内部定制版本，相对直接上游有两点差异：

1. 围绕语音通话完成了音频与稳定性专项（缺陷修复、抖动缓冲、语音激活门控、输出设备路由等）；
2. 按内部使用需求移除了与通话无关的模块，明细见「已移除的功能」。界面文案已去除
   「二次元」「Han」等对外表述。

---

## 来源与致谢

YATS3 的全部基础来自以下上游项目：

| 项目 | 作者 | 说明 |
|---|---|---|
| [TS6_Droid](https://github.com/flamme-demon/TS6_Droid) | flamme-demon | 原始 Android 客户端，Jetpack Compose + Rust `tslib` 架构 |
| [TS6_Droid_CN](https://github.com/YUAXI/TS6_Droid_CN) | YUAXI | 简体中文本地化版本，YATS3 的直接基础 |

上游中文版贡献者：

<a href="https://github.com/YUAXI/TS6_Droid_CN/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=YUAXI/TS6_Droid_CN" />
</a>

YATS3 遵循 GPLv3，保留上游全部版权声明与许可证，详见 [LICENSE](LICENSE)。

---

## 已移除的功能

以下功能存在于直接上游，YATS3 已移除。更新日志中涉及它们的条目为上游沿革记录，
不代表当前可用。

| 功能 | 移除提交 | 移除范围 |
|---|---|---|
| 二次元 / 动漫背景 | `1e90efd` | `AnimeBackground.kt`；停止联网获取壁纸 |
| 自定义背景 | `1e90efd` | `CropScreen.kt`、`CustomBackgroundManager.kt` |
| 壁纸缓存管理 | `1e90efd` | `WallpaperCacheManager.kt` 及设置页入口 |
| 应用内检查更新 | `681da28` | `UpdateChecker.kt`；移除 GitHub Releases 版本查询 |
| 应用内下载安装 APK | `681da28` | `InAppUpdater.kt`；移除相关 `FileProvider` 路径与权限声明 |
| 密聊（Whisper） | `21f38ef` | `WhisperManager.kt`、`WhisperBridge.kt` 及频道树、用户项入口 |
| 「关于」页对外内容 | `681da28` | 改为内部说明，移除项目宣传与更新入口 |

---

## 功能特性

### 语音通话

- **抖动缓冲与丢包隐藏**：按语音包号去重、重排，缺包时以 PLC 补帧
- **播放时钟时间基**：按绝对 20ms 时隙推进，不依赖 `playbackHeadPosition`
  （部分机型该值恒为 0，会导致缓冲判断失效、无声）
- **语音激活门控**：静音时不编码、不上行；PTT 模式下按下即发送
- **说话圈**：本地按麦克风能量判定，远端按语音包到达判定，保持时间 250ms
- **输出设备选择**：通话中可切换扬声器、听筒、有线耳机、蓝牙（顶栏菜单）
- **通话独占声音**：开启时占用音频焦点（音乐暂停），关闭时与音乐并存
- **麦克风电平测试**：设置页按住测量电平，显示其与门限的关系，带峰值保持游标
- **音量增益**：设置页与通话界面底栏均可调，拖动实时生效

### 通话交互

- **全屏触摸 PTT**：PTT 模式下双指唤出，任意位置按住即说话，松开停止；期间屏幕常亮
- **悬浮窗**：在其他应用上层显示当前说话人，支持开关
- **崩溃取证**：`Thread.UncaughtExceptionHandler` 与 `ApplicationExitInfo` 补记；
  报告文件名含毫秒级时间戳；原生 trace 从 protobuf 二进制中提取可读片段

### 本地化

- 简体中文完整覆盖（`zh-rCN`），支持中文、English、Français 应用内切换

### 通用能力

- 频道树、文字消息（频道与私聊）、文件传输与图片预览、头像与频道图标、消息本地持久化

---

## 更新日志

### v2.1.5 → v2.1.22（2026-09-11 ~ 09-13）

本轮为语音通话专项。以下按主题归并，条目末尾标注对应版本号。

**稳定性**

- 修复偶发闪退：`JitterBuffer` 内部 `TreeMap` 被两个线程并发访问（写入位于 service
  主线程，读取与修改位于音频播放线程），红黑树结构损坏后在 `fixAfterInsertion` 抛出
  `NullPointerException`。所有公开入口加锁，计数器改为 `@Volatile`。（2.1.11）
- 修复抖动缓冲深度锁死：升档判定误用 PLC 计数形成正反馈，深度升至上限后不再回落；
  同步将上限放宽至 240ms。（2.1.6）

**音频链路**

- 新增抖动缓冲与丢包隐藏。（2.1.6）
- 播放时钟改为单调时间基。（2.1.5）
- 修复语音激活门控未作用于发送端：此前门控未生效，持续按 50 包/秒上行。（2.1.6）
- 说话圈判据改为流判定：远端以语音包到达为准，去除解码能量判定，消除抖动缓冲预填充
  引起的显示滞后。（2.1.12）

**输出设备与音频焦点**

- 新增通话音频输出设备选择，支持扬声器、听筒、有线耳机、蓝牙；设备列表随插拔刷新，
  选择结果持久化，设备不可用时回退「跟随系统」。（2.1.17）
- 蓝牙 A2DP 与 SCO 通道分别列出。（2.1.17）
- 修复采集侧设备路由：不再将输出设备应用于 `AudioRecord`，改为在输入设备列表中匹配。
  （2.1.19）
- 设备列表中排除 `TYPE_FM` 与 `TYPE_TELEPHONY`。（2.1.19）
- 新增「通话独占声音」开关。（2.1.18）
- 修复被音乐占用音频焦点后的持续静音：`AUDIOFOCUS_LOSS` 为永久性失去，系统不会回调
  `GAIN`，需主动重新申请。（2.1.14）
- 音量增益支持在通话界面底栏调节。（2.1.20）

**通话交互**

- 新增全屏触摸 PTT。（2.1.9）修复该功能引入的手势事件回归。（2.1.10）
- 修复悬浮窗三项缺陷：说话人显示残留、头像按压反馈形状异常、说话人切换响应延迟约
  540ms。（2.1.7 / 2.1.8 / 2.1.13）

**可观测性**

- 新增崩溃取证机制。（2.1.7）
- 事件通道丢弃计数与告警日志；消息消费移出主线程。（2.1.7）
- 设置页新增麦克风电平测试。（2.1.6）

**内部化**

- 移除二次元壁纸、自定义背景整套，内部化「关于」页，移除应用内检查更新，移除密聊；
  应用名改为 TS3。明细见「已移除的功能」。（2.1.5）

### v2.1.4-Han 及更早（上游沿革）

<details>
<summary>展开查看</summary>

以下为直接上游 `TS6_Droid_CN` 的历史条目。标注 *（已移除）* 的功能在本项目中已不存在。

#### v2.1.4-Han（2026-08-18）

- 昵称长度验证：连接时校验昵称不少于 3 个字符（中、英、法三语提示）

#### v2.1.3-Han（2026-07-28）

- TS3 Spacer 频道渲染：解析 `[cspacer]`、`[lspacer]`、`[rspacer]`、`[*spacer]` 标签

#### v2.1.2-Han（2026-07-15）

- *（已移除）* 自定义背景：相册上传与裁切预览
- 设置页改为卡片式布局：外观、音频、聊天、更多

#### v2.1.0-Han（2026-06-27）

- *（已移除）* 应用内更新：应用内下载并安装 APK

#### v2.0.1-Han（2026-06-26）

- 全项目 54 处 Flow 采集迁移至 `collectAsStateWithLifecycle`，降低后台 CPU 与功耗
- 背景淡入动画改用 `Modifier.graphicsLayer {}`，跳过 Composition 阶段

#### v2.0.0-Han（2026-06-26）

- Material 3 全面重构：Dynamic Color 动态取色（Android 12+）、15 级排版体系
- 新增 SplashScreen 启动界面、首页底部导航栏
- *（已移除）* 壁纸缓存系统与自定义背景相关能力

</details>

---

## 构建

### 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | compileSdk 35（minSdk 29 / targetSdk 35） |
| Gradle | 由 wrapper 提供，无需单独安装 |

### 本地构建

```bash
export JAVA_HOME=/path/to/jdk17
export PATH="$JAVA_HOME/bin:$PATH"

# 构建 APK（使用仓库内预编译的原生库）
./gradlew assembleDebug -x buildRustLibs

# 运行单元测试
./gradlew testDebugUnitTest -x buildRustLibs
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

`-x buildRustLibs` 为必需参数。除本地具备 Rust 工具链并克隆上游 `tslib` 源码外，
均应跳过该任务，直接使用 `jniLibs/` 中的预编译产物。

### 原生库（`jniLibs/`）

`app/src/main/jniLibs/<abi>/libtslib_jni.so` 由上游 Rust 项目 `tslib` 编译而来。
该产物已提交至仓库，使 CI 与本地构建均无需 Rust 工具链。

支持的 ABI：**arm64-v8a**、**x86_64**。其余 ABI 目录仅含 AndroidX 辅助库。

重新编译原生库的步骤参考上游仓库。

### 云编译（GitHub Actions）

仓库已配置 `.github/workflows/android-build.yml`：

1. 推送至 `main` / `master`，或在 Actions 页面手动触发；
2. 工作流以 JDK 17 运行单元测试，随后执行 `./gradlew assembleDebug -x buildRustLibs`；
3. 在运行记录的 Artifacts 区域下载 `TS3-Android-App`。

---

## 签名

本仓库不含签名文件（已在 `.gitignore` 中排除）。默认构建产物使用 debug 签名，适用于
自用安装。

如需正式签名，在项目根目录生成 keystore 并在 `app/build.gradle.kts` 中配置：

```bash
keytool -genkey -v -keystore release.keystore -alias <your-alias> \
        -keyalg RSA -keysize 2048 -validity 10000
```

keystore 及其口令不得提交至仓库。

---

## 技术架构

底层 Rust 架构与本地编译环境搭建等技术细节，参考上游仓库：

- [flamme-demon/TS6_Droid](https://github.com/flamme-demon/TS6_Droid)
- [YUAXI/TS6_Droid_CN](https://github.com/YUAXI/TS6_Droid_CN)

---

## 开源许可

YATS3 遵循 GNU GPLv3 开源许可证，详见 [LICENSE](LICENSE)。

作为上游的衍生作品，本项目保留原许可证与全部版权声明，并按 GPLv3 要求公开全部源码。
