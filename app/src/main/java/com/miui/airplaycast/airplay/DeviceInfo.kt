package com.miui.airplaycast.airplay

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.net.NetworkInterface

/**
 * 设备信息工具
 *
 * 获取真实 MAC 地址 (用于 AirPlay plist deviceID 字段)
 * Android 6+ 限制 MAC 访问，需通过 NetworkInterface 或 WifiManager 获取
 */
object DeviceInfo {

    /**
     * 获取 WiFi 网卡 MAC 地址
     *
     * Android 6+ 默认返回 02:00:00:00:00:00，需特殊处理:
     *  - Android 11+ 永久限制，只能拿到随机 MAC
     *  - 老版本可通过 NetworkInterface 遍历
     *
     * @return MAC 地址字符串 (大写，冒号分隔)，获取失败返回占位
     */
    @SuppressLint("HardwareIds")
    fun getMacAddress(context: Context? = null): String {
        // 1. 尝试从 NetworkInterface 遍历
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                // 优先 wlan0
                if (name.startsWith("wlan") || name.startsWith("wi") || name.contains("eth0")) {
                    val mac = intf.hardwareAddress ?: continue
                    if (mac.size == 6 && mac.any { it != 0.toByte() }) {
                        return formatMac(mac)
                    }
                }
            }
        }

        // 2. Android 9 以下尝试 WifiManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && context != null) {
            runCatching {
                @Suppress("DEPRECATION")
                val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val info = wm?.connectionInfo
                val mac = info?.macAddress
                if (!mac.isNullOrBlank() && mac != "02:00:00:00:00:00") {
                    return mac.uppercase()
                }
            }
        }

        // 3. 兜底：基于 Build 序列号生成稳定伪 MAC (非真实，但 plist 字段必填)
        val seed = (Build.SERIAL ?: "AirPlayCast").hashCode()
        val bytes = ByteArray(6)
        var s = seed
        for (i in 0 until 6) {
            s = s * 31 + i
            bytes[i] = (s and 0xFF).toByte()
        }
        // 设定 locally administered 位避免与真实 OUI 冲突
        bytes[0] = (bytes[0].toInt() or 0x02).toByte()
        return formatMac(bytes)
    }

    private fun formatMac(bytes: ByteArray): String {
        return bytes.joinToString(":") { String.format("%02X", it.toInt() and 0xFF) }
    }
}
