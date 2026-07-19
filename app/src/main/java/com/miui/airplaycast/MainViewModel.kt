package com.miui.airplaycast

import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

/**
 * 主界面 ViewModel - 协调设备发现 / 媒体投放 / 屏幕镜像
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

    // 投屏状态消息
    private val _toast = MutableSharedFlow<String>()
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    private var localServer: LocalMediaServer? = null

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
     * @param resultCode 来自 MediaProjection 的 resultCode
     * @param data 来自 MediaProjection 的 Intent
     */
    fun startMirroring(resultCode: Int, data: Intent) {
        val device = selectedDevice.value ?: return
        viewModelScope.launch {
            // 启动镜像 RTSP 会话
            val ok = MirroringSession.start(device, 1920, 1080)
            if (!ok) {
                _toast.emit("镜像会话启动失败")
                return@launch
            }
            // 启动屏幕捕获
            ScreenCaptureService.start(getApplication(), resultCode, data)
            _toast.emit("已开始镜像到 ${device.name}")
        }
    }

    fun stopMirroring() {
        ScreenCaptureService.stop(getApplication())
        MirroringSession.stop()
        viewModelScope.launch { _toast.emit("已停止镜像") }
    }

    /**
     * 投放本地媒体文件
     */
    fun castLocalFile(uri: Uri, hostIp: String) {
        val device = selectedDevice.value ?: return
        viewModelScope.launch {
            _toast.emit("正在准备本地文件...")
            val file = copyUriToCache(uri) ?: run {
                _toast.emit("读取文件失败")
                return@launch
            }
            val server = LocalMediaServer().also { it.start(file) }
            localServer = server

            val url = server.buildUrl(hostIp)
            _castUrl.value = url
            _toast.emit("开始投放到 ${device.name}")

            val session = MediaCastSession(device)
            _mediaSession.value = session
            session.playUrl(url)
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
            _toast.emit("开始投放到 ${device.name}")
            session.playUrl(url)
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

    override fun onCleared() {
        super.onCleared()
        stopMediaCast()
        stopMirroring()
    }
}
