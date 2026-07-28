package com.miui.airplaycast.airplay

import java.util.UUID

/**
 * AirPlay plist 构建工具
 *
 * AirPlay 协议大量使用 Apple plist (XML 格式) 携带结构化数据，
 * 例如镜像 /info、SETUP 请求体、配对请求等
 *
 * plist 类型映射:
 *  <dict>  -> Map
 *  <array> -> List
 *  <string> -> String
 *  <integer> -> Long/Int
 *  <real> -> Double
 *  <true/>/<false/> -> Boolean
 *  <data> -> Base64 ByteArray
 */
object PlistBuilder {

    /**
     * 构建镜像会话 /info plist
     *
     * 参考 UxPlay / esp-apple-airplay 实现，关键字段:
     *  - width/height: 屏幕尺寸 (镜像通常横屏)
     *  - fps: 帧率
     *  - overscanned: 是否过扫描
     *  - deviceClass: 设备类型 (iPhone/iPad/Mac)
     *  - macAddress: 客户端 MAC (需真实，用于服务端识别)
     *  - model: 设备型号字符串
     *  - sourceVersion: AirPlay 协议版本
     *  - features: 能力位 (hex 字符串)
     *  - pi: product identifier (UUID)
     *  - vm: 视频镜像开关
     *  - deviceID: 等同 macAddress
     *  - sessionUUID: 会话 UUID
     */
    fun buildMirrorInfoPlist(
        width: Int,
        height: Int,
        fps: Int = 30,
        macAddress: String,
        model: String = "iPhone14,5",
        sessionUUID: String = UUID.randomUUID().toString().uppercase(),
        productUUID: String = UUID.randomUUID().toString().uppercase()
    ): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>width</key>
    <integer>$width</integer>
    <key>height</key>
    <integer>$height</integer>
    <key>fps</key>
    <integer>$fps</integer>
    <key>overscanned</key>
    <false/>
    <key>version</key>
    <string>${AirPlayConstants.USER_AGENT}</string>
    <key>deviceClass</key>
    <string>iPhone</string>
    <key>macAddress</key>
    <string>$macAddress</string>
    <key>deviceID</key>
    <string>$macAddress</string>
    <key>model</key>
    <string>$model</string>
    <key>sourceVersion</key>
    <string>460.21</string>
    <key>features</key>
    <string>0x27</string>
    <key>pi</key>
    <string>$productUUID</string>
    <key>sessionUUID</key>
    <string>$sessionUUID</string>
    <key>vm</key>
    <true/>
    <key>isGroup</key>
    <false/>
</dict>
</plist>
"""
    }

    /**
     * 构建 SETUP 请求体 plist
     *
     * SETUP 携带会话声明，服务端据此建立流通道
     *  - deviceID: 客户端 MAC
     *  - sessionUUID: 会话唯一 ID
     *  - sessionType: 会话类型 (镜像=64, 音视频=96)
     *  - processes: 进程能力数组
     */
    fun buildSetupPlist(
        macAddress: String,
        sessionUUID: String = UUID.randomUUID().toString().uppercase()
    ): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>deviceID</key>
    <string>$macAddress</string>
    <key>sessionUUID</key>
    <string>$sessionUUID</string>
    <key>sessionType</key>
    <integer>64</integer>
    <key>osVersion</key>
    <string>16.0</string>
    <key>model</key>
    <string>iPhone14,5</string>
    <key>processes</key>
    <array>
        <dict>
            <key>bundleID</key>
            <string>com.miui.airplaycast</string>
            <key>pid</key>
            <integer>${Process.myPid()}</integer>
            <key>processName</key>
            <string>AirPlayCast</string>
        </dict>
    </array>
    <key>launchUUID</key>
    <string>$sessionUUID</string>
</dict>
</plist>
"""
    }

    /**
     * 构建 RECORD 请求体 plist
     *
     * RECORD 通知服务端开始接收流，携带视频参数
     */
    fun buildRecordPlist(
        width: Int,
        height: Int,
        fps: Int = 30
    ): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>width</key>
    <integer>$width</integer>
    <key>height</key>
    <integer>$height</integer>
    <key>fps</key>
    <integer>$fps</integer>
    <key>videoCodec</key>
    <integer>0</integer>
    <key>videoFormatInfo</key>
    <dict>
        <key>payloadType</key>
        <integer>96</integer>
        <key>profileIdc</key>
        <integer>66</integer>
        <key>levelIdc</key>
        <integer>30</integer>
        <key>profileIop</key>
        <integer>0</integer>
    </dict>
</dict>
</plist>
"""
    }
}
