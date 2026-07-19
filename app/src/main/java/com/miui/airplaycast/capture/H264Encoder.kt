package com.miui.airplaycast.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer

/**
 * H.264 (AVC) 硬件编码器
 *
 * 使用 Android MediaCodec 编码屏幕帧为 H.264 NAL 流
 * 输出 NAL 单元由 [MirroringSession] 通过 RTSP POST 上传
 *
 * AirPlay 镜像期望的 NAL 结构:
 *  - SPS (NAL type 7) + PPS (NAL type 8) 在 keyframe 头部
 *  - IDR slice (NAL type 5) 关键帧
 *  - Non-IDR slice (NAL type 1) P 帧
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val frameRate: Int = 30,
    private val iframeInterval: Int = 2,
    private val bitrate: Int = calcBitrate(width, height, frameRate)
) {
    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /**
         * 根据分辨率与帧率估算码率
         */
        fun calcBitrate(w: Int, h: Int, fps: Int): Int {
            // 经验公式: 像素数 × fps × 0.07
            return (w * h * fps * 0.07f).toInt().coerceAtLeast(500_000)
        }
    }

    private var codec: MediaCodec? = null
    private var scope: CoroutineScope? = null

    lateinit var inputSurface: Surface
        private set

    var onFrameEncoded: ((nalData: ByteArray, isKeyFrame: Boolean, presentationTimeUs: Long) -> Unit)? = null

    private val _frameFlow = MutableSharedFlow<EncodedFrame>(extraBufferCapacity = 30)
    val frameFlow: SharedFlow<EncodedFrame> = _frameFlow.asSharedFlow()

    /**
     * 启动编码器
     */
    fun start(): Boolean {
        return runCatching {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeInterval)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }

            val codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            this.codec = codec

            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope?.launch { drainOutputLoop() }

            Log.i(TAG, "H.264 encoder started: ${width}x${height} @ ${frameRate}fps, ${bitrate / 1000}kbps")
            true
        }.onFailure { Log.e(TAG, "Failed to start encoder", it) }.getOrDefault(false)
    }

    fun stop() {
        runCatching { codec?.signalEndOfInputStream() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface.release() }
        runCatching { scope?.cancel() }
        codec = null
        scope = null
        Log.i(TAG, "H.264 encoder stopped")
    }

    /**
     * 强制请求关键帧 (用于客户端连接时立即获取 I 帧)
     */
    fun requestKeyFrame() {
        runCatching {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec?.setParameters(params)
        }
    }

    private suspend fun drainOutputLoop() {
        val codec = this.codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (scope?.isActive == true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    delay(5)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    Log.i(TAG, "Output format: $format")
                }
                index >= 0 -> {
                    val buf: ByteBuffer? = codec.getOutputBuffer(index)
                    if (buf != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        buf.position(bufferInfo.offset)
                        buf.get(data, 0, bufferInfo.size)

                        val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val frame = EncodedFrame(
                            data = data,
                            isKeyFrame = isKeyFrame,
                            presentationTimeUs = bufferInfo.presentationTimeUs,
                            sps = extractNal(data, 7),
                            pps = extractNal(data, 8)
                        )

                        onFrameEncoded?.invoke(data, isKeyFrame, bufferInfo.presentationTimeUs)
                        _frameFlow.tryEmit(frame)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun extractNal(data: ByteArray, nalType: Int): ByteArray? {
        var i = 0
        while (i < data.size - 4) {
            // 查找起始码 0x00 0x00 0x00 0x01 或 0x00 0x00 0x01
            val start: Int
            val nalStart: Int
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) {
                start = i
                nalStart = i + 4
            } else if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 1.toByte()) {
                start = i
                nalStart = i + 3
            } else {
                i++
                continue
            }
            if (nalStart >= data.size) break
            val type = (data[nalStart].toInt() and 0x1F)
            if (type == nalType) {
                // 找下一个 NAL 起始位置
                var j = nalStart + 1
                while (j < data.size - 3) {
                    if ((data[j] == 0.toByte() && data[j+1] == 0.toByte() && data[j+2] == 1.toByte()) ||
                        (data[j] == 0.toByte() && data[j+1] == 0.toByte() && data[j+2] == 0.toByte() && data[j+3] == 1.toByte())) {
                        return data.copyOfRange(start, j)
                    }
                    j++
                }
                return data.copyOfRange(start, data.size)
            }
            i = nalStart + 1
        }
        return null
    }
}

data class EncodedFrame(
    val data: ByteArray,
    val isKeyFrame: Boolean,
    val presentationTimeUs: Long,
    val sps: ByteArray? = null,
    val pps: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
