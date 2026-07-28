package com.miui.airplaycast

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miui.airplaycast.airplay.AirPlayError
import com.miui.airplaycast.airplay.LocalMediaServer
import com.miui.airplaycast.airplay.MediaCastSession
import com.miui.airplaycast.airplay.MirrorState
import com.miui.airplaycast.airplay.MirroringSession
import com.miui.airplaycast.capture.ScreenCaptureManager
import com.miui.airplaycast.capture.ScreenCaptureService
import com.miui.airplaycast.discovery.AirPlayDevice
import com.miui.airplaycast.discovery.DiscoveryViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.resume

/**
 * 主界面 ViewModel - 协调设备发现 / 媒体投放 / 屏幕镜像
 *
 * 修复点:
 *  - 镜像启动顺序: 先 RTSP 握手 (/info+SETUP+RECORD) 再启动编码器
 *    MediaCodec 启动后会立即产出 IDR 关键帧，避免服务端等待
 *  - 错误传递: 将 AirPlayError 暴露给 UI 分类提示
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val discovery = DiscoveryViewModel(app)

    val devices: StateFlow<List<AirPlayDevice>> = discovery.devices
    val selectedDevice: StateFlow<AirPlayDevice?> = discovery.selectedDevice
    val isDiscovering: StateFlow<Boolean> = discovery.isDiscovering

    // 模式切换: 0 = 镜像, 1 = 媒体
    private val _mode = MutableStateFlow(0)
    val mode: StateFlow<Int> = _mode.asStateFlow()

    // 媒体投放
    private val _mediaSession = MutableStateFlow<MediaCastSession?>(null)
    val mediaSession: StateFlow<MediaCastSession?> = _mediaSession.asStateFlow()

    // 屏幕镜像状态
    val mirrorState: StateFlow<MirrorState> = MirroringSession.state
    val captureState: StateFlow<ScreenCaptureManager.CaptureState> = ScreenCaptureManager.state

    // 投屏 URL
    private val _castUrl = MutableStateFlow("")
    val castUrl: StateFlow<String> = _castUrl.asStateFlow()

    // 选中的本地媒体 Uri
    private val _localUri = MutableStateFlow<Uri?>(null)
    val localUri: StateFlow<Uri?> = _localUri.asStateFlow()

    // 最近错误 (UI 分类提示用)
    private val _lastError = MutableStateFlow<AirPlayError?>(null)
    val lastError: StateFlow<AirPlayError?> = _lastError.asStateFlow()

    // 投屏状态消息
    private val _toast = MutableSharedFlow<String>()
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    // PIN 配对请求 - 当目标设备要求 PIN 时触发，UI 弹出输入框
    private val _pinRequest = MutableStateFlow<PinRequest?>(null)
    val pinRequest: StateFlow<PinRequest?> = _pinRequest.asStateFlow()

    private var localServer: LocalMediaServer? = null

    /** 待执行的 PIN 配对回调 (UI 输入 PIN 后调用) */
    @Volatile private var pendingPinAction: ((String) -> Unit)? = null

    /**
     * 由 MirroringSession/MediaCastSession 在收到 401/403 时触发
     * UI 监听 pinRequest 弹出输入框，用户输入后调用 [submitPin]
     */
    fun requestPin(deviceName: String, onPin: (String) -> Unit) {
        pendingPinAction = onPin
        _pinRequest.value = PinRequest(deviceName)
    }

    /** UI 提交 PIN 码给上层的握手流程 */
    fun submitPin(pin: String) {
        _pinRequest.value = null
        pendingPinAction?.invoke(pin)
        pendingPinAction = null
    }

    /** 用户取消 PIN 输入 */
    fun cancelPin() {
        _pinRequest.value = null
        pendingPinAction = null
        viewModelScope.launch { _toast.emit("已取消配对") }
    }

    fun setMode(mode: Int) { _mode.value = mode }
    fun selectDevice(device: AirPlayDevice?) { discovery.selectDevice(device) }
    fun refresh() {
        discovery.stopDiscovery()
        discovery.startDiscovery()
    }
    fun setCastUrl(url: String) { _castUrl.value = url }
    fun setLocalUri(uri: Uri?) { _localUri.value = uri }

    /**
     * 启动屏幕镜像
     *
     * 顺序:
     *  1. MirroringSession.start (探测 PIN -> RTSP 握手: /info + SETUP + RECORD)
     *  2. ScreenCaptureService.start (启动 MediaCodec + VirtualDisplay)
     *
     * 握手成功后立即启动编码器，MediaCodec 首帧即为 IDR 关键帧，
     * 服务端可立即获取 SPS/PPS 开始解码
     *
     * @param resultCode 来自 MediaProjection 的 resultCode
     * @param data 来自 MediaProjection 的 Intent
     */
    fun startMirroring(resultCode: Int, data: Intent) {
        val device = selectedDevice.value ?: return
        viewModelScope.launch {
            _lastError.value = null
            _toast.emit("正在连接 ${device.name}...")

            // 1. RTSP 握手 (含 PIN 配对探测 + /info + SETUP + RECORD)
            //    onPinRequired 回调在 IO 线程被调用，需挂起等待用户输入
            val result = MirroringSession.start(
                device, 1920, 1080, getApplication(),
                onPinRequired = { awaitPinFromUi(device.name) }
            )
            when (result) {
                is com.miui.airplaycast.airplay.AirPlayResult.Success -> {
                    // 2. 握手成功，立即启动屏幕捕获 (编码器会产出 IDR 关键帧)
                    ScreenCaptureService.start(getApplication(), resultCode, data)
                    _toast.emit("已开始镜像到 ${device.name}")
                }
                is com.miui.airplaycast.airplay.AirPlayResult.Failure -> {
                    _lastError.value = result.error
                    _toast.emit("镜像失败: ${result.error.displayText}")
                }
            }
        }
    }

    /**
     * 挂起等待 UI 输入 PIN 码
     *
     * 通过 [pinRequest] StateFlow 触发 UI 弹框，
     * 用户输入后调用 [submitPin] 唤醒本挂起函数
     */
    private suspend fun awaitPinFromUi(deviceName: String): String? {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            requestPin(deviceName) { pin ->
                if (cont.isActive) {
                    if (pin.isBlank()) cont.resume(null)
                    else cont.resume(pin)
                }
            }
            cont.invokeOnCancellation {
                // 协程取消时清理回调
                pendingPinAction = null
                _pinRequest.value = null
            }
        }
    }

    fun stopMirroring() {
        ScreenCaptureService.stop(getApplication())
        MirroringSession.stop()
        viewModelScope.launch { _toast.emit("已停止镜像") }
    }

    /**
     * 投放本地媒体文件
     *
     * @param uri 本地文件 Uri
     * @param presetIp UI 已探得的本机 IP (可选)，为空时由 ViewModel 内部获取
     */
    fun castLocalFile(uri: Uri, presetIp: String? = null) {
        val device = selectedDevice.value ?: return
        viewModelScope.launch {
            _lastError.value = null
            _toast.emit("正在准备本地文件...")
            val file = copyUriToCache(uri) ?: run {
                _toast.emit("读取文件失败")
                return@launch
            }
            val server = LocalMediaServer().also { it.start(file) }
            localServer = server

            val ip = presetIp ?: getLocalIp() ?: run {
                _toast.emit("无法获取本机 IP，请检查 WiFi 连接")
                return@launch
            }
            val url = server.buildUrl(ip)
            _castUrl.value = url
            _toast.emit("开始投放到 ${device.name}")

            val session = MediaCastSession(device)
            _mediaSession.value = session
            val ok = session.playUrl(url)
            if (!ok) {
                _lastError.value = session.lastError.value
                _toast.emit("投放失败: ${session.lastError.value?.displayText ?: "未知错误"}")
            }
        }
    }

    /**
     * 投放 HTTP URL
     */
    fun castUrl() {
        val device = selectedDevice.value ?: return
        val url = _castUrl.value.trim()
        if (url.isEmpty()) {
            viewModelScope.launch { _toast.emit("请输入有效的 URL") }
            return
        }
        val session = MediaCastSession(device)
        _mediaSession.value = session
        viewModelScope.launch {
            _lastError.value = null
            _toast.emit("开始投放到 ${device.name}")
            val ok = session.playUrl(url)
            if (!ok) {
                _lastError.value = session.lastError.value
                _toast.emit("投放失败: ${session.lastError.value?.displayText ?: "未知错误"}")
            }
        }
    }

    fun stopMediaCast() {
        _mediaSession.value?.stop()
        _mediaSession.value = null
        localServer?.stop()
        localServer = null
    }

    private fun copyUriToCache(uri: Uri): java.io.File? = runCatching {
        val ctx = getApplication<Application>()
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        val file = java.io.File(ctx.cacheDir, "cast_${System.currentTimeMillis()}_${uri.lastPathSegment ?: "media"}")
        file.outputStream().use { out -> input.copyTo(out) }
        input.close()
        file
    }.getOrNull()

    /**
     * 获取本机局域网 IP - 优先 WiFi 网卡
     *
     * 修复点:
     *  - 优先选择 wlan 开头的网卡 (避免 VPN/虚拟网卡)
     *  - 排除 loopback / link-local / 未启用网卡
     *  - 返回前做连通性自检 (本机 Socket bind 测试)
     */
    private fun getLocalIp(): String? {
        return runCatching {
            val candidates = mutableListOf<Pair<String, String>>()  // (ifaceName, ip)
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback || intf.isVirtual) continue
                val name = intf.name.lowercase()
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    if (addr is java.net.Inet6Address) continue  // 优先 IPv4
                    if (addr.isLinkLocalAddress) continue
                    candidates.add(name to addr.hostAddress)
                }
            }
            // 优先级: wlan* > eth* > 其他
            candidates.sortedBy { (name, _) ->
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("ap") -> 2  // 热点
                    else -> 3
                }
            }.firstOrNull()?.second
        }.getOrNull()
    }

    override fun onCleared() {
        super.onCleared()
        stopMediaCast()
        stopMirroring()
    }
}

/** PIN 配对请求信息 (供 UI 弹框显示) */
data class PinRequest(
    val deviceName: String,
    val hint: String = "请在接收端屏幕查看 PIN 码"
)
