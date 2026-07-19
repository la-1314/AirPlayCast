package com.miui.airplaycast.airplay

/**
 * AirPlay 协议常量定义
 *
 * 参考:
 *  - openairplay/openairplay (C 实现)
 *  - espressif/esp-apple-airplay (AppleTV AirPlay Receiver)
 *  - 逆向资料: https://openairplay.github.io/airplay-spec/
 *
 * AirPlay 1 协议分两层:
 *  1. HTTP/REST 控制通道 (端口 7000): /play, /rate, /stop, /scrub, /volume, /photo, /server-info
 *  2. AirTunes/RAOP 流通道 (端口 mDNS 公布): 音频流，AES-128-CTR 加密
 *
 * AirPlay 2 协议 (新版 Apple TV, HomePod) 使用:
 *  - Pairing: /pair-setup-pin /pair-verify (HomeKit Pair-Setup)
 *  - 流通道: HTTP/2 + fairplay 加密 (内嵌于 main stream)
 *  - 控制: HomeKit Accessory Protocol
 *
 * 本实现优先兼容 AirPlay 1 + 部分 AirPlay 2 公开部分
 */
object AirPlayConstants {

    // HTTP 控制路径 (AirPlay 1)
    const val PATH_PLAY = "/play"
    const val PATH_STOP = "/stop"
    const val PATH_RATE = "/rate"
    const val PATH_SCRUB = "/scrub"
    const val PATH_VOLUME = "/volume"
    const val PATH_PHOTO = "/photo"
    const val PATH_SERVER_INFO = "/server-info"
    const val PATH_REVERSE = "/reverse"
    const val PATH_SLIDESHOW = "/slideshow"
    const val PATH_STREAM = "/stream"

    // AirPlay Reverse (反向) 事件
    const val REVERSE_EVENT_PLAYBACK = "playback"
    const val REVERSE_EVENT_VOLUME = "volumeChanged"
    const val REVERSE_EVENT_SLIDE = "slideshow"

    // 屏幕镜像 (AirPlay Mirroring) 特有路径
    // 通过 RTSP 在 :7100 端口建立，逐步 SETUP/RECORD/TEARDOWN
    // 镜像会话使用专有加密 (FairPlay)
    const val MIRROR_RTSP_PATH_INFO = "/info"
    const val MIRROR_RTSP_PATH_STREAM = "/stream"

    // HTTP 头
    const val HEADER_USER_AGENT = "User-Agent"
    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val HEADER_X_APPLE_SESSION = "X-Apple-Session-ID"
    const val HEADER_X_APPLE_DACP_ID = "DACP-ID"
    const val HEADER_X_APPLE_ACTIVE_REMOTE = "Active-Remote"

    const val USER_AGENT = "AirPlayCast/1.0 (Android; MIUI)"
    const val CONTENT_TYPE_BINARY = "application/octet-stream"
    const val CONTENT_TYPE_PARAMS = "text/parameters"
    const val CONTENT_TYPE_URL = "text/x-apple-plist+xml"

    // RTSP 协议命令 (镜像会话)
    const val RTSP_METHOD_SETUP = "SETUP"
    const val RTSP_METHOD_RECORD = "RECORD"
    const val RTSP_METHOD_TEARDOWN = "TEARDOWN"
    const val RTSP_METHOD_GET_PARAMETER = "GET_PARAMETER"
    const val RTSP_METHOD_SET_PARAMETER = "SET_PARAMETER"
    const val RTSP_METHOD_POST = "POST"
    const val RTSP_METHOD_FLUSH = "FLUSH"

    // AirPlay 镜像 RTSP 端口（参考实现）
    const val MIRROR_DEFAULT_PORT = 7100
}
