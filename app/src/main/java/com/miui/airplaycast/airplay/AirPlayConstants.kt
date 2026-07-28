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

    // AirPlay 2 配对路径
    const val PATH_PAIR_SETUP = "/pair-setup"
    const val PATH_PAIR_SETUP_PIN = "/pair-setup-pin"
    const val PATH_PAIR_VERIFY = "/pair-verify"

    // AirPlay Reverse (反向) 事件
    const val REVERSE_EVENT_PLAYBACK = "playback"
    const val REVERSE_EVENT_VOLUME = "volumeChanged"
    const val REVERSE_EVENT_SLIDE = "slideshow"

    // 屏幕镜像 (AirPlay Mirroring) 特有路径
    // 通过 RTSP 在 :7100 端口建立，逐步 SETUP/RECORD/TEARDOWN
    const val MIRROR_RTSP_PATH_INFO = "/info"
    const val MIRROR_RTSP_PATH_STREAM = "/stream"

    // HTTP 头
    const val HEADER_USER_AGENT = "User-Agent"
    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val HEADER_X_APPLE_SESSION = "X-Apple-Session-ID"
    const val HEADER_X_APPLE_DACP_ID = "DACP-ID"
    const val HEADER_X_APPLE_ACTIVE_REMOTE = "Active-Remote"
    const val HEADER_X_APPLE_PROTOCOL_VERSION = "X-Apple-ProtocolVersion"
    const val HEADER_X_APPLE_STREAM_ID = "x-apple-stream-id"
    const val HEADER_CSEQ = "CSeq"
    const val HEADER_SESSION = "Session"
    const val HEADER_APPLE_CHALLENGE = "Apple-Challenge"

    const val USER_AGENT = "AirPlayCast/1.0 (Android; MIUI)"
    const val CONTENT_TYPE_BINARY = "application/octet-stream"
    const val CONTENT_TYPE_PARAMS = "text/parameters"
    const val CONTENT_TYPE_URL = "text/x-apple-plist+xml"
    const val CONTENT_TYPE_PLIST = "application/x-apple-plist"
    const val CONTENT_TYPE_PAIRING = "application/octet-stream"

    // RTSP 协议命令 (镜像会话)
    const val RTSP_METHOD_SETUP = "SETUP"
    const val RTSP_METHOD_RECORD = "RECORD"
    const val RTSP_METHOD_TEARDOWN = "TEARDOWN"
    const val RTSP_METHOD_GET_PARAMETER = "GET_PARAMETER"
    const val RTSP_METHOD_SET_PARAMETER = "SET_PARAMETER"
    const val RTSP_METHOD_POST = "POST"
    const val RTSP_METHOD_FLUSH = "FLUSH"

    // AirPlay 镜像 RTSP 默认端口
    // 注意：实际端口应通过 HTTP /server-info 的 'features' 与 'pk' 字段查询
    // 老式设备 (AirPort Express, 早期 Apple TV) 使用 7100
    const val MIRROR_DEFAULT_PORT = 7100

    // HTTP 控制默认端口
    const val HTTP_CONTROL_PORT = 7000

    // AirPlay features 位标志 (位于低 64 位)
    const val FEATURE_VIDEO = 0x1L
    const val FEATURE_PHOTO = 0x4L
    const val FEATURE_AUDIO = 0x8L
    // 0x80 = 支持认证 (不一定强制)，真正的强制配对由 401/403 响应判定
    const val FEATURE_AUTHENTICATION = 0x80L
    const val FEATURE_MIRRORING = 0x20000L
    // AirPlay 2 标志位于高 64 位的 bit 18 (即 0x40000L << 64)
    // 这里保留常量仅供低 64 位兼容性检测使用，isAirPlay2 应通过 featuresHigh 判断
    const val FEATURE_AIRPLAY_2_HIGH_BIT = 0x40000L
}
