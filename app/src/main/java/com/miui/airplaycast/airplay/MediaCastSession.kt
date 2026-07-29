package com.miui.airplaycast.airplay

import android.util.Log
import com.miui.airplaycast.discovery.AirPlayDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AirPlay 媒体投放会话
 */
class MediaCastSession(
    private val device: AirPlayDevice
) {
    companion object {
        private const val TAG = "MediaCastSession"
    }

    private val client = AirPlayHttpClient(device)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<MediaCastState>(MediaCastState.Idle)
    val state: StateFlow<MediaCastState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(MediaProgress(0.0, 0.0))
    val progress: StateFlow<MediaProgress> = _progress.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _lastError = MutableStateFlow<AirPlayError?>(null)
    val lastError: StateFlow<AirPlayError?> = _lastError.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    val deviceName: String get() = device.name
    val deviceAddress: String get() = device.address

    suspend fun playUrl(
        url: String,
        startPosition: Double = 0.0,
        onPinRequired: (suspend (errorHint: String?) -> String?)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        _state.value = MediaCastState.Connecting
        _lastError.value = null

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

        // PIN 配对探测 (带重试，最多 3 次)
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
                                return@withContext false
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
                                        return@withContext false
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

        when (val revResult = client.startReverseChannel()) {
            is AirPlayResult.Failure -> {
                Log.w(TAG, "Reverse channel 启动失败 (非致命): ${revResult.error.displayText}")
            }
            is AirPlayResult.Success -> Log.i(TAG, "Reverse channel started")
        }

        when (val playResult = client.play(url, startPosition)) {
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

    fun resume() {
        ioScope.launch {
            when (val r = client.resume()) {
                is AirPlayResult.Success -> _state.value = MediaCastState.Playing
                is AirPlayResult.Failure -> _lastError.value = r.error
            }
        }
    }

    fun pause() {
        ioScope.launch {
            when (val r = client.pause()) {
                is AirPlayResult.Success -> _state.value = MediaCastState.Paused
                is AirPlayResult.Failure -> _lastError.value = r.error
            }
        }
    }

    fun stop() {
        ioScope.launch {
            client.stop()
            client.close()
            _state.value = MediaCastState.Idle
            _progress.value = MediaProgress(0.0, 0.0)
        }
    }

    fun seekTo(seconds: Double) {
        ioScope.launch {
            when (val r = client.scrub(seconds)) {
                is AirPlayResult.Success -> queryProgress()
                is AirPlayResult.Failure -> _lastError.value = r.error
            }
        }
    }

    fun setVolume(v: Float) {
        val clamped = v.coerceIn(0f, 1f)
        ioScope.launch {
            when (val r = client.setVolume(clamped)) {
                is AirPlayResult.Success -> _volume.value = clamped
                is AirPlayResult.Failure -> _lastError.value = r.error
            }
        }
    }

    fun updateProgress() {
        ioScope.launch { queryProgress() }
    }

    private suspend fun queryProgress() {
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
