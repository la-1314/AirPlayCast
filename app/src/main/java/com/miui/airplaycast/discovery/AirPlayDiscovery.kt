package com.miui.airplaycast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetAddress

/**
 * AirPlay 接收端设备信息
 *
 * AirPlay Receiver 通过 mDNS/Bonjour 注册的 _airplay._tcp. 服务
 * 以及 _raop._tcp. (AirPlay 1 Audio) / _airplay._tcp. 同时支持
 */
data class AirPlayDevice(
    val name: String,
    val host: InetAddress,
    val port: Int,
    val macAddress: String? = null,
    val model: String? = null,
    val supportsMirroring: Boolean = false,
    val supportsAirPlayVideo: Boolean = false,
    val supportsAudio: Boolean = false,
    val firmwareVersion: String? = null,
    val rawTxt: Map<String, String> = emptyMap()
) {
    val address: String get() = host.hostAddress ?: "unknown"

    val displayAddress: String
        get() = buildString {
            append("$host:$port")
            if (!model.isNullOrBlank()) append(" · $model")
        }
}

/**
 * 使用 NsdManager 监听 _airplay._tcp. 服务发现 AirPlay 接收端
 *
 * AirPlay 服务类型说明：
 * - _airplay._tcp. : 主服务，包含设备信息 (model, srcvers, flags 等)
 * - _raop._tcp.    : AirPlay 1 音频流，deviceid = MAC，tcparams/tpparams 等
 *
 * 屏幕镜像能力检测：通过 TXT 记录中的 features/flags 字段判断
 *   bit 0  (0x01): Video, bit 7 (0x40): Mute, bit 17 (0x20000): Mirroring 等
 */
class AirPlayDiscovery(private val context: Context) {

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _devices = MutableStateFlow<List<AirPlayDevice>>(emptyList())
    val devices: StateFlow<List<AirPlayDevice>> = _devices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun startDiscovery() {
        if (_isDiscovering.value) return
        _isDiscovering.value = true

        discoverService(AIRPLAY_SERVICE_TYPE)
        discoverService(RAOP_SERVICE_TYPE)
    }

    fun stopDiscovery() {
        if (!_isDiscovering.value) return
        listeners.forEach { runCatching { nsdManager.stopServiceDiscovery(it) } }
        listeners.clear()
        _isDiscovering.value = false
    }

    private fun discoverService(serviceType: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isDiscovering.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveService(serviceInfo, serviceType)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _devices.update { list ->
                    list.filterNot { it.name == serviceInfo.serviceName }
                }
            }
        }
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        listeners.add(listener)
    }

    private fun resolveService(info: NsdServiceInfo, originalServiceType: String) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host ?: return
                val txt = serviceInfo.attributes
                    .mapKeys { it.key.lowercase() }
                    .mapValues { String(it.value, Charsets.UTF_8).trim() }

                val features = parseFeatures(txt["features"] ?: txt["flags"] ?: "0")
                val name = serviceInfo.serviceName

                val device = AirPlayDevice(
                    name = name,
                    host = host,
                    port = serviceInfo.port,
                    macAddress = txt["deviceid"] ?: txt["macaddress"],
                    model = txt["model"],
                    supportsMirroring = features.supportsMirroring || txt["model"]?.contains("AppleTV") == true,
                    supportsAirPlayVideo = features.supportsVideo,
                    supportsAudio = originalServiceType == RAOP_SERVICE_TYPE || features.supportsAudio,
                    firmwareVersion = txt["srcvers"] ?: txt["fv"],
                    rawTxt = txt
                )

                _devices.update { list ->
                    val without = list.filterNot { it.name == name || (it.macAddress != null && it.macAddress == device.macAddress) }
                    without + device
                }
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // 部分设备解析失败时忽略
            }
        }
        runCatching { nsdManager.resolveService(info, resolveListener) }
    }

    private fun parseFeatures(value: String): Features {
        val bits = value.toLongOrNull(16) ?: value.toLongOrNull() ?: 0L
        return Features(
            supportsVideo = bits and 0x01L != 0L,
            supportsPhoto = bits and 0x04L != 0L,
            supportsAudio = bits and 0x08L != 0L,
            supportsMirroring = bits and 0x20000L != 0L,
            requiresAuthentication = bits and 0x80L != 0L
        )
    }

    private data class Features(
        val supportsVideo: Boolean,
        val supportsPhoto: Boolean,
        val supportsAudio: Boolean,
        val supportsMirroring: Boolean,
        val requiresAuthentication: Boolean
    )

    companion object {
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp."
        const val RAOP_SERVICE_TYPE = "_raop._tcp."
    }
}
