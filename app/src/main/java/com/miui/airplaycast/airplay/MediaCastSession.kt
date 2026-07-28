package com.miui.airplaycast.airplay

import android.util.Log
import com.miui.airplaycast.discovery.AirPlayDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AirPlay 媒体投放会话
 *
 * 支持:
 *  - HTTP URL 投放 (远程视频 URL)
 *  - 本地媒体文件投放 (需先启动本地 HTTP 服务)
 *  - 播放控制: 播放/暂停/停止/跳转/音量
 */
class MediaCastSession(
    private val device: AirPlayDevice
) {
    companion object {
        private const val TAG = "MediaCastSession"
    }

    private val client = AirPlayHttpClient(device)

    private val _state = MutableStateFlow<MediaCastState>(MediaCastState.Idle)
    val state: StateFlow<MediaCastState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(MediaProgress(0.0, 0.0))
    val progress: StateFlow<MediaProgress> = _progress.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    /** 最近一次错误详情 (供 UI 展示) */
    private val _lastError = MutableStateFlow<AirPlayError?>(null)
    val lastError: StateFlow<AirPlayError?> = _lastError.asStateFlow()

    /** 设备能力 (server-info 探测后填充) */
    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    val deviceName: String get() = device.name
    val deviceAddress: String get() = device.address

    /**
     * 投放一个媒体 URL
     *
     * 流程: server-info -> (PIN 配对探测, 带重试) -> /reverse -> /play
     *
     * @param url 媒体 URL
     * @param startPosition 起始位置 (秒)
     * @param onPinRequired 当设备要求 PIN 配对时回调 (suspend 等待用户输入)
     *                      参数 errorHint: 重试时的错误提示 (首次为 null)
     *                      返回 null 表示取消
     */
    suspend fun playUrl(
        url: String,
        startPosition: Double = 0.0,
        onPinRequired: (suspend (errorHint: String?) -> String?)? = null
    ): Boolean {
        _state.value = MediaCastState.Connecting
        _lastError.value = null

        // 1. 探测设备能力
        when (val infoResult = client.serverInfo()) {
            is AirPlayResult.Success -> {
                _serverInfo.value = infoResult.value
                val si = infoResult.value
                Log.i(TAG, "Server: ${si.model}, featuresLow=0x${si.featuresLow.toString(16)}, featuresHigh=0x${si.featuresHigh.toString(16)}, video=${si.supportsVideo}")
                if (!si.supportsVideo) {
                    Log.w(TAG, "Device may not support video casting, continuing anyway")
                }
            }
            is AirPlayResult.Failure -> {
                Log.w(TAG, "server-info 探测失败，继续尝试: ${infoResult.error.displayText}")
            }
        }

        // 1.5 探测 PIN 配对需求 (带重试，最多 3 次)
        //     注意: 不能用 runCatching{} 包裹，因为其 lambda 是非 suspend，
        //     而 onPinRequired.invoke() 是 suspend 调用
        if (onPinRequired != null) {
            val pairing = com.miui.airplaycast.airplay.pairing.PairingManager(device)
            when (val probe = pairing.isPinRequired()) {
                is AirPlayResult.Success -> {
                    if (probe.value) {
                        Log.i(TAG, "Device requires PIN pairing, requesting user input")
                        var attempts = 0
                        val maxAttempts = 3
                        while (attempts < maxAttempts) {
                            val errorHint = if (attempts > 0) "PIN 错误，请重试 ($attempts/$maxAttempts)" else null
                            val pin = onPinRequired.invoke(errorHint)
                            if (pin.isNullOrBlank()) {
                                val err = AirPlayError.PairingRequired("用户取消 PIN 输入")
                                _lastError.value = err
                                _state.value = MediaCastState.Error(err.displayText)
                                return false
                            }
                            when (val pairResult = pairing.pairSetupWithPin(pin)) {
                                is AirPlayResult.Success -> {
                                    Log.i(TAG, "PIN pairing succeeded")
                                    break
                                }
                                is AirPlayResult.Failure -> {
                                    attempts++
                                    if (attempts >= maxAttempts) {
                                        _lastError.value = pairResult.error
                                        _state.value = MediaCastState.Error(pairResult.error.displayText)
                                        return false
                                    }
                                    Log.w(TAG, "PIN pairing attempt $attempts failed: ${pairResult.error.displayText}, retrying...")
                                }
                            }
                        }
                    }
                }
                is AirPlayResult.Failure -> Log.w(TAG, "PIN probe failed (non-fatal): ${probe.error.displayText}")
            }
        }

        // 2. 启动反向通道 (失败不阻断，部分接收端不需要)
        when (val revResult = client.startReverseChannel()) {
            is AirPlayResult.Failure -> {
                Log.w(TAG, "Reverse channel 启动失败 (非致命): ${revResult.error.displayText}")
            }
            is AirPlayResult.Success -> Log.i(TAG, "Reverse channel started")
        }

        // 3. 投放媒体
        return when (val playResult = client.play(url, startPosition)) {
            is AirPlayResult.Success -> {
                _state.value = MediaCastState.Playing
                Log.i(TAG, "playUrl ok: $url @ ${device.name}")
                true
            }
            is AirPlayResult.Failure -> {
                _lastError.value = playResult.error
                _state.value = MediaCastState.Error(playResult.error.displayText)
                Log.e(TAG, "playUrl failed: ${playResult.error.displayText}")
                false
            }
        }
    }

    fun resume(): Boolean {
        return when (client.resume()) {
            is AirPlayResult.Success -> { _state.value = MediaCastState.Playing; true }
            is AirPlayResult.Failure -> { _lastError.value = it.error; false }
        }
    }

    fun pause(): Boolean {
        return when (client.pause()) {
            is AirPlayResult.Success -> { _state.value = MediaCastState.Paused; true }
            is AirPlayResult.Failure -> { _lastError.value = it.error; false }
        }
    }

    fun stop() {
        client.stop()
        client.close()
        _state.value = MediaCastState.Idle
        _progress.value = MediaProgress(0.0, 0.0)
    }

    fun seekTo(seconds: Double): Boolean {
        return when (client.scrub(seconds)) {
            is AirPlayResult.Success -> { updateProgress(); true }
            is AirPlayResult.Failure -> { _lastError.value = it.error; false }
        }
    }

    fun setVolume(v: Float): Boolean {
        val clamped = v.coerceIn(0f, 1f)
        return when (client.setVolume(clamped)) {
            is AirPlayResult.Success -> { _volume.value = clamped; true }
            is AirPlayResult.Failure -> { _lastError.value = it.error; false }
        }
    }

    fun updateProgress() {
        if (_state.value !is MediaCastState.Playing) return
        when (val r = client.queryScrub()) {
            is AirPlayResult.Success -> {
                val pos = r.value["position"] ?: 0.0
                val dur = r.value["duration"] ?: 0.0
                _progress.value = MediaProgress(pos, dur)
            }
            is AirPlayResult.Failure -> Log.w(TAG, "queryScrub failed: ${r.error.displayText}")
        }
    }
}

sealed class MediaCastState {
    object Idle : MediaCastState()
    object Connecting : MediaCastState()
    object Playing : MediaCastState()
    object Paused : MediaCastState()
    data class Error(val message: String) : MediaCastState()
}

data class MediaProgress(
    val positionSeconds: Double,
    val durationSeconds: Double
) {
    val progress: Float
        get() = if (durationSeconds > 0) (positionSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) else 0f
}