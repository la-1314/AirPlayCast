package com.miui.airplaycast.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.hardware.display.DisplayManager
import android.view.Surface
import android.view.WindowManager
import com.miui.airplaycast.airplay.MirroringSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 屏幕捕获 + H.264 编码管理器
 *
 * 工作流:
 *  1. MediaProjection 创建 VirtualDisplay
 *  2. VirtualDisplay 渲染到 MediaCodec 配置的 Surface
 *  3. MediaCodec 编码为 H.264 NAL 单元
 *  4. 编码后的帧推送到 [MirroringSession] 通过 RTSP 发送
 *
 * 视频参数 (AirPlay Mirroring 兼容):
 *  - 编码: H.264 Baseline 3.1 (AVC)
 *  - 分辨率: 自适应屏幕 (最高 1920x1080)
 *  - 帧率: 30fps
 *  - 关键帧间隔: 2 秒
 */
object ScreenCaptureManager {
    private const val TAG = "ScreenCaptureManager"

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var projection: MediaProjection? = null
    private var encoder: H264Encoder? = null

    fun start(context: Context, resultCode: Int, data: Intent) {
        if (_state.value is CaptureState.Running) {
            Log.w(TAG, "Already running")
            return
        }

        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        val mp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.getMediaProjection(resultCode, data)
        } else {
            mpm.getMediaProjection(resultCode, data)
        }
        projection = mp ?: run {
            _state.value = CaptureState.Error("MediaProjection 创建失败")
            return
        }

        val (width, height, dpi) = getDisplayInfo(context)

        val enc = H264Encoder(
            width = width,
            height = height,
            dpi = dpi,
            frameRate = 30,
            iframeInterval = 2
        ).apply {
            onFrameEncoded = { nalData, isKeyFrame, presentationTimeUs ->
                MirroringSession.enqueueFrame(nalData, isKeyFrame, presentationTimeUs)
            }
        }

        if (!enc.start()) {
            _state.value = CaptureState.Error("编码器启动失败")
            return
        }
        encoder = enc

        val virtualDisplay = mp.createVirtualDisplay(
            "AirPlayMirrorDisplay",
            width, height, dpi,
            DisplayDefaultFlags, enc.inputSurface,
            null, null
        )

        _state.value = CaptureState.Running(width, height)
        Log.i(TAG, "Screen capture started: ${width}x${height}")
    }

    fun stop() {
        runCatching { encoder?.stop() }
        runCatching { projection?.stop() }
        encoder = null
        projection = null
        _state.value = CaptureState.Idle
    }

    private fun getDisplayInfo(context: Context): Triple<Int, Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        // 限制到 1080p，避免编码压力过大
        val maxWidth = 1920
        val maxHeight = 1080
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        if (w > maxWidth || h > maxHeight) {
            val ratio = w.toFloat() / h.toFloat()
            if (ratio > maxWidth.toFloat() / maxHeight) {
                w = maxWidth
                h = (maxWidth / ratio).toInt()
            } else {
                h = maxHeight
                w = (maxHeight * ratio).toInt()
            }
        }
        // 编码器要求宽高为偶数
        w = w and 0x7FFFFFFE
        h = h and 0x7FFFFFFE
        return Triple(w, h, metrics.densityDpi)
    }

    private val DisplayDefaultFlags =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

    sealed class CaptureState {
        object Idle : CaptureState()
        data class Running(val width: Int, val height: Int) : CaptureState()
        data class Error(val message: String) : CaptureState()
    }
}
