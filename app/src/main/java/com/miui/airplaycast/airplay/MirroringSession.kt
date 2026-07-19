package com.miui.airplaycast.airplay

import android.util.Log
import com.miui.airplaycast.capture.EncodedFrame
import com.miui.airplaycast.capture.H264Encoder
import com.miui.airplaycast.discovery.AirPlayDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AirPlay 屏幕镜像会话
 *
 * 工作流:
 *  1. POST /info  发送设备能力 plist
 *  2. SETUP /stream  建立流通道 (返回 server_port)
 *  3. RECORD       开始录制 (服务端开始接收)
 *  4. POST /stream  持续推送 H.264 NAL 数据
 *  5. TEARDOWN     关闭会话
 *
 * 重要限制:
 *  AirPlay 2 镜像的 H.264 数据需经过 FairPlay 加密
 *  Apple 官方接收端 (Apple TV) 会拒绝未加密的流
 *  本实现可对部分开源接收端 (如 UxPlay 自定义模式) 工作
 */
object MirroringSession {
    private const val TAG = "MirroringSession"

    private val _state = MutableStateFlow(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var rtsp: RtspClient? = null
    private var targetDevice: AirPlayDevice? = null

    private val frameQueue = ConcurrentLinkedQueue<Pair<ByteArray, Long>>()

    /**
     * 启动镜像会话
     */
    fun start(device: AirPlayDevice, width: Int, height: Int): Boolean {
        if (_state.value is MirrorState.Running) {
            Log.w(TAG, "Mirroring already running")
            return false
        }
        _state.value = MirrorState.Connecting
        targetDevice = device

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        var ok = false
        runCatching {
            val client = RtspClient(device.host.hostAddress ?: "", device.port)
            if (!client.connect()) {
                _state.value = MirrorState.Error("RTSP 连接失败")
                return false
            }
            rtsp = client

            // 1. 发送 /info plist
            val infoPlist = buildInfoPlist(width, height)
            val infoResp = client.request(
                AirPlayConstants.RTSP_METHOD_POST,
                AirPlayConstants.MIRROR_RTSP_PATH_INFO,
                headers = mapOf(
                    "Content-Type" to "application/x-apple-plist",
                    "X-Apple-ProtocolVersion" to "1"
                ),
                body = infoPlist.toByteArray(Charsets.UTF_8)
            )

            if (!infoResp.isSuccess) {
                _state.value = MirrorState.Error("info 失败: ${infoResp.statusCode}")
                return false
            }

            // 2. SETUP /stream
            val setupResp = client.request(
                AirPlayConstants.RTSP_METHOD_SETUP,
                AirPlayConstants.MIRROR_RTSP_PATH_STREAM,
                headers = mapOf(
                    "Content-Type" to "application/x-apple-plist"
                )
            )

            if (!setupResp.isSuccess) {
                _state.value = MirrorState.Error("SETUP 失败: ${setupResp.statusCode}")
                return false
            }

            // 3. RECORD
            val recordResp = client.request(
                AirPlayConstants.RTSP_METHOD_RECORD,
                AirPlayConstants.MIRROR_RTSP_PATH_STREAM,
                headers = mapOf(
                    "Session" to client.sessionId
                )
            )

            if (!recordResp.isSuccess) {
                _state.value = MirrorState.Error("RECORD 失败: ${recordResp.statusCode}")
                return false
            }

            _state.value = MirrorState.Running(device.name)
            ok = true

            // 4. 启动帧发送循环
            scope?.launch { frameSenderLoop() }
        }.onFailure {
            Log.e(TAG, "Failed to start mirroring", it)
            _state.value = MirrorState.Error(it.message ?: "未知错误")
            return false
        }
        return ok
    }

    fun stop() {
        runCatching { rtsp?.request(AirPlayConstants.RTSP_METHOD_TEARDOWN, AirPlayConstants.MIRROR_RTSP_PATH_STREAM) }
        runCatching { rtsp?.disconnect() }
        rtsp = null
        scope?.cancel()
        scope = null
        frameQueue.clear()
        targetDevice = null
        _state.value = MirrorState.Idle
    }

    /**
     * 接收来自 H264Encoder 的编码帧
     */
    fun enqueueFrame(nalData: ByteArray, isKeyFrame: Boolean, presentationTimeUs: Long) {
        if (_state.value !is MirrorState.Running) return
        frameQueue.add(nalData to presentationTimeUs)
        if (frameQueue.size > 60) {
            // 防止积压过多, 丢帧到关键帧
            var dropped = 0
            while (frameQueue.size > 30) {
                val p = frameQueue.poll() ?: break
                dropped++
            }
            Log.w(TAG, "Dropped $dropped frames due to backpressure")
        }
    }

    private suspend fun frameSenderLoop() {
        while (scope?.isActive == true && _state.value is MirrorState.Running) {
            val pair = frameQueue.poll()
            if (pair == null) {
                delay(5)
                continue
            }
            val (data, pts) = pair
            sendFrame(data, pts)
        }
    }

    private fun sendFrame(data: ByteArray, pts: Long) {
        val client = rtsp ?: return
        runCatching {
            client.request(
                AirPlayConstants.RTSP_METHOD_POST,
                AirPlayConstants.MIRROR_RTSP_PATH_STREAM,
                headers = mapOf(
                    "Content-Type" to AirPlayConstants.CONTENT_TYPE_BINARY,
                    "X-Apple-PT" to pts.toString()
                ),
                body = data
            )
        }.onFailure {
            Log.e(TAG, "Send frame failed", it)
        }
    }

    /**
     * 构建 AirPlay 镜像 /info plist
     *
     * 包含设备能力声明: 视频参数、音频参数、加密能力
     */
    private fun buildInfoPlist(width: Int, height: Int): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>width</key>
    <integer>$width</integer>
    <key>height</key>
    <integer>$height</integer>
    <key>fps</key>
    <integer>30</integer>
    <key>overscanned</key>
    <false/>
    <key>version</key>
    <string>${AirPlayConstants.USER_AGENT}</string>
    <key>deviceClass</key>
    <string>iPhone</string>
    <key>macAddress</key>
    <string>aa:bb:cc:dd:ee:ff</string>
    <key>model</key>
    <string>Android-MIUI</string>
    <key>sourceVersion</key>
    <string>460.21</string>
    <key>features</key>
    <integer>0x7</integer>
</dict>
</plist>
"""
    }
}

sealed class MirrorState {
    object Idle : MirrorState()
    object Connecting : MirrorState()
    data class Running(val deviceName: String) : MirrorState()
    data class Error(val message: String) : MirrorState()
}
