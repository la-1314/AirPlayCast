package com.miui.airplaycast.airplay

import android.os.Process
import java.util.UUID

object PlistBuilder {

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
