# AirPlayCast

基于 AirPlay 协议的安卓投屏应用，采用 MIUI/HyperOS 现代设计风格。

## 功能特性

### 屏幕镜像 (Screen Mirroring)
- 基于 AirPlay 镜像协议 (RTSP + H.264 NAL 流)
- 使用 `MediaProjection` API 实时捕获屏幕
- `MediaCodec` 硬件编码为 H.264
- 自适应分辨率 (最高 1080p, 30fps)
- 前台服务保活，符合 Android 10+ 规范

### 媒体投放 (Media Cast)
- 支持远程 HTTP/HTTPS URL 投放
- 支持本地媒体文件投放 (mp4/mov/mp3/m4a 等)
- 内置 HTTP 文件服务器，支持 Range 请求
- 播放控制：播放/暂停/停止/跳转/音量调节

### 设备发现
- 基于 `NsdManager` 实现 mDNS 服务发现
- 同时监听 `_airplay._tcp.` 和 `_raop._tcp.`
- 解析 TXT 记录判断设备能力 (镜像/视频/音频)
- 实时设备列表，自动去重

### UI 设计
- MIUI/HyperOS 设计语言
- 大圆角卡片 (20dp)、毛玻璃质感
- 渐变色主按钮 (蓝色/橙色)
- 支持深色模式
- 分段控件切换镜像/媒体模式
- 状态徽章 + 渐变 Hero 卡片

## 技术架构

```
AirPlayCast/
├── app/
│   └── src/main/
│       ├── java/com/miui/airplaycast/
│       │   ├── AirPlayApp.kt              # Application 入口
│       │   ├── MainActivity.kt            # 主 Activity
│       │   ├── MainViewModel.kt           # 主 ViewModel
│       │   │
│       │   ├── discovery/                 # mDNS 设备发现
│       │   │   ├── AirPlayDiscovery.kt    # NsdManager 封装
│       │   │   └── DiscoveryViewModel.kt
│       │   │
│       │   ├── airplay/                   # AirPlay 协议实现
│       │   │   ├── AirPlayConstants.kt    # 协议常量
│       │   │   ├── AirPlayHttpClient.kt   # HTTP 控制通道
│       │   │   ├── RtspClient.kt          # RTSP 镜像通道
│       │   │   ├── MirroringEncryption.kt # FairPlay 加密层声明
│       │   │   ├── MirroringSession.kt    # 镜像会话管理
│       │   │   ├── MediaCastSession.kt    # 媒体投放会话
│       │   │   └── LocalMediaServer.kt    # 本地 HTTP 文件服务器
│       │   │
│       │   ├── capture/                   # 屏幕捕获与编码
│       │   │   ├── ScreenCaptureService.kt # 前台服务
│       │   │   ├── ScreenCaptureManager.kt # 投屏管理
│       │   │   ├── H264Encoder.kt         # H.264 编码器
│       │   │   └── MediaPlaybackService.kt
│       │   │
│       │   └── ui/
│       │       ├── theme/                 # MIUI 主题
│       │       │   ├── Color.kt
│       │       │   ├── Type.kt
│       │       │   └── Theme.kt
│       │       ├── components/            # MIUI 风格组件
│       │       │   └── MiComponents.kt
│       │       └── screens/               # 屏幕级 Composable
│       │           └── MainScreen.kt
│       │
│       └── res/                           # 资源文件
```

## 构建要求

- Android Studio Iguana+ / Koala+
- JDK 17
- Android SDK 34
- Min SDK 26 (Android 8.0)
- Kotlin 2.0.10
- Compose Compiler 2.0.10
- Gradle 8.9

## 构建与运行

1. 在 Android Studio 中打开 `AirPlayCast/` 目录
2. 等待 Gradle 同步完成
3. 连接 Android 设备 (Android 8.0+)
4. 点击 Run 按钮

```bash
# 命令行构建
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## AirPlay 协议实现说明

### 协议层次

**AirPlay 1 (HTTP)** - 本项目完整实现
- 设备发现: mDNS `_airplay._tcp.` / `_raop._tcp.`
- 控制: HTTP/1.1 (端口 7000)
- 媒体: HTTP URL (Content-Location) 投放
- 路径: `/play`, `/rate`, `/stop`, `/scrub`, `/volume`, `/photo`, `/server-info`

**AirPlay Mirroring (RTSP)** - 本项目实现信令部分
- 通道: RTSP (端口 7100)
- 流: H.264 NAL 通过 POST `/stream` 推送
- 信令: SETUP / RECORD / TEARDOWN

**AirPlay 2 (HomeKit + FairPlay)** - 本项目不实现
- 配对: HomeKit Pair-Setup (Ed25519)
- 流加密: FairPlay DRM
- 由于 FairPlay 是 Apple 专有加密，受法律保护，无法在开源项目中实现

### 关于屏幕镜像到 Apple TV 的兼容性

由于 Apple TV (tvOS 11+) 要求 FairPlay 加密的镜像流，本项目实现的镜像功能：
- ✅ 可工作: 第三方开源接收端 (如 [UxPlay](https://github.com/FDH2/UxPlay)、[AirPlayServer-ESP32](https://github.com/espressif/esp-apple-airplay))
- ❌ 不可工作: 官方 Apple TV (FairPlay 校验失败)

如需在 Apple TV 上实现镜像，建议：
1. 在 Apple TV 上安装第三方接收端 App (如 AirReceiver)
2. 或使用本项目仅作媒体投放 (URL/本地文件) 模式

### 参考的开源项目

- [openairplay/openairplay](https://github.com/openairplay/openairplay) - AirPlay 协议 C 语言实现
- [FDH2/UxPlay](https://github.com/FDH2/UxPlay) - 基于 GStreamer 的 AirPlay 接收端
- [espressif/esp-apple-airplay](https://github.com/espressif/esp-apple-airplay) - Apple 授权 ESP32 接收端
- [insidegui/AirReceiver](https://github.com/insidegui/AirReceiver) - macOS Swift 接收端

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` | AirPlay HTTP 控制 |
| `ACCESS_WIFI_STATE` | 获取局域网 IP |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS 多播发现 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 屏幕镜像前台服务 (Android 14+) |
| `POST_NOTIFICATIONS` | 投屏通知 (Android 13+) |
| `READ_MEDIA_VIDEO/AUDIO` | 选择本地媒体文件 |

## 法律声明

本项目仅用于学习 AirPlay 协议原理，不包含任何 Apple FairPlay 解密/加密代码。
所有 AirPlay 相关商标归 Apple Inc. 所有。
请在使用时遵守当地法律法规，不要用于绕过 DRM 保护。
