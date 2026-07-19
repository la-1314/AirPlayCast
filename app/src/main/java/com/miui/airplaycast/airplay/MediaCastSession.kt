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
    private val client = AirPlayHttpClient(device)

    private val _state = MutableStateFlow<MediaCastState>(MediaCastState.Idle)
    val state: StateFlow<MediaCastState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(MediaProgress(0.0, 0.0))
    val progress: StateFlow<MediaProgress> = _progress.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    val deviceName: String get() = device.name
    val deviceAddress: String get() = device.address

    /**
     * 投放一个媒体 URL
     */
    fun playUrl(url: String, startPosition: Double = 0.0): Boolean {
        _state.value = MediaCastState.Connecting
        val ok = client.play(url, startPosition)
        if (ok) {
            _state.value = MediaCastState.Playing
            Log.i(TAG, "playUrl ok: $url @ $device")
        } else {
            _state.value = MediaCastState.Error("投屏失败，请检查设备是否在同一 WiFi")
            Log.e(TAG, "playUrl failed: $url @ $device")
        }
        return ok
    }

    fun resume(): Boolean {
        val ok = client.resume()
        if (ok) _state.value = MediaCastState.Playing
        return ok
    }

    fun pause(): Boolean {
        val ok = client.pause()
        if (ok) _state.value = MediaCastState.Paused
        return ok
    }

    fun stop() {
        client.stop()
        _state.value = MediaCastState.Idle
        _progress.value = MediaProgress(0.0, 0.0)
    }

    fun seekTo(seconds: Double): Boolean {
        val ok = client.scrub(seconds)
        if (ok) updateProgress()
        return ok
    }

    fun setVolume(v: Float): Boolean {
        val clamped = v.coerceIn(0f, 1f)
        val ok = client.setVolume(clamped)
        if (ok) _volume.value = clamped
        return ok
    }

    /**
     * 拉取最新播放进度
     */
    fun updateProgress() {
        if (_state.value !is MediaCastState.Playing) return
        val info = client.queryScrub()
        val pos = info["position"] ?: 0.0
        val dur = info["duration"] ?: 0.0
        _progress.value = MediaProgress(pos, dur)
    }

    companion object {
        private const val TAG = "MediaCastSession"
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
