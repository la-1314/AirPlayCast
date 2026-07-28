package com.miui.airplaycast.airplay

import android.content.Context
import android.util.Log
import com.miui.airplaycast.discovery.AirPlayDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AirPlay 屏幕镜像会话
 *
 * 工作流 (修复后):
 *  1. GET /server-info (HTTP 7000) 查询设备能力 + 确认支持镜像
 *  2. 连接 RTSP 端口 7100 (而非 HTTP 7000)
 *  3. POST /info (plist 含真实 MAC、deviceID、pi、vm)
 *  4. SETUP /stream (携带 plist 请求体, 解析响应的 Session ID)
 *  5. RECORD (使用服务端 Session ID)
 *  6. POST /stream 持续推送 H.264 NAL 帧 (带 4 字节时间戳前缀)
 *  7. TEARDOWN 关闭会话
 */
object MirroringSession {
    private const val TAG = "MirroringSession"

    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var rtsp: RtspClient? = null
    private var targetDevice: AirPlayDevice? = null
    private var contextRef: Context? = null

    private val frameQueue = ConcurrentLinkedQueue<MirrorFrame>()
    @Volatile private var startTimeMs: Long = 0

    /**
     * 启动镜像会话 (仅握手，不包含编码器启动)
     */
    suspend fun start(device: AirPlayDevice, width: Int, height: Int, context: Context): AirPlayResult<Unit> =
        start(device, width, height, context, onPinRequired = null)

    /**
     * 启动镜像会话 (带 PIN 配对回调)
     *
     * @param onPinRequired 当探测到设备要求 PIN 时调用 (suspend，回调内阻塞等待用户输入)
     *                      参数 errorHint: 重试时的错误提示 (首次为 null)
     *                      返回用户输入的 PIN 码，返回 null 表示用户取消
     */
    suspend fun start(
        device: AirPlayDevice,
        width: Int,
        height: Int,
        context: Context,
        onPinRequired: (suspend (errorHint: String?) -> String?)?
    ): AirPlayResult<Unit> = airPlayTrySuspend {
        if (_state.value is MirrorState.Running) {
            throw AirPlayException(AirPlayError.Unknown("镜像已在运行"))
        }
        _state.value = MirrorState.Connecting
        targetDevice = device
        contextRef = context

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val hostAddress = device.host.hostAddress
        if (hostAddress.isNullOrBlank()) {
            throw AirPlayException(AirPlayError.Network(
                "设备 ${device.name} 的 IP 地址为空，mDNS 解析可能未完成，请稍后重试"
            ))
        }

        val macAddress = DeviceInfo.getMacAddress(context)
        Log.i(TAG, "Starting mirror to ${device.name} ($hostAddress), mac=$macAddress")

        // 1. 先通过 HTTP /server-info 探测设备能力 (确认支持镜像)
        val httpClient = AirPlayHttpClient(device)
        val infoResult = httpClient.serverInfo()
        var mirrorPort = AirPlayConstants.MIRROR_DEFAULT_PORT
        if (infoResult is AirPlayResult.Failure) {
            Log.w(TAG, "server-info 探测失败，继续尝试: ${infoResult.error.displayText}")
        } else if (infoResult is AirPlayResult.Success) {
            val si = infoResult.value
            Log.i(TAG, "Server: model=${si.model}, featuresLow=0x${si.featuresLow.toString(16)}, featuresHigh=0x${si.featuresHigh.toString(16)}, mirror=${si.supportsMirroring}, auth=${si.requiresAuthentication}, airplay2=${si.isAirPlay2}")
            if (!si.supportsMirroring && !device.supportsMirroring) {
                Log.w(TAG, "Device may not support mirroring, continuing anyway")
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
                                throw AirPlayException(AirPlayError.PairingRequired("用户取消 PIN 输入"))
                            }
                            when (val pairResult = pairing.pairSetupWithPin(pin)) {
                                is AirPlayResult.Success -> {
                                    Log.i(TAG, "PIN pairing succeeded, continuing handshake")
                                    break
                                }
                                is AirPlayResult.Failure -> {
                                    attempts++
                                    if (attempts >= maxAttempts) {
                                        throw AirPlayException(pairResult.error)
                                    }
                                    Log.w(TAG, "PIN pairing attempt $attempts failed: ${pairResult.error.displayText}, retrying...")
                                }
                            }
                        }
                    } else {
                        Log.i(TAG, "Device does not require PIN")
                    }
                }
                is AirPlayResult.Failure -> {
                    Log.w(TAG, "PIN probe failed (non-fatal): ${probe.error.displayText}")
                }
            }
        }

        // 2. 连接 RTSP 镜像端口 (默认 7100)
        val client = RtspClient(hostAddress, mirrorPort)
        client.connect()
        rtsp = client

        // 3. POST /info (plist 含真实 MAC、deviceID、pi、vm)
        val infoPlist = PlistBuilder.buildMirrorInfoPlist(
            width = width,
            height = height,
            macAddress = macAddress
        )
        val infoResp = client.request(
            AirPlayConstants.RTSP_METHOD_POST,
            AirPlayConstants.MIRROR_RTSP_PATH_INFO,
            headers = mapOf(
                AirPlayConstants.HEADER_X_APPLE_PROTOCOL_VERSION to "1"
            ),
            body = infoPlist.toByteArray(Charsets.UTF_8),
            contentType = AirPlayConstants.CONTENT_TYPE_PLIST
        )
        if (!infoResp.isSuccess) {
            throw AirPlayException(AirPlayError.HandshakeFailed(
                "/info", infoResp.statusCode,
                "镜像 info 协商失败 - 设备可能不支持镜像或协议版本不兼容"
            ))
        }
        Log.i(TAG, "/info OK")

        // 4. SETUP /stream (携带 plist 请求体 + 标准 RTSP Transport 头)
        val setupPlist = PlistBuilder.buildSetupPlist(macAddress)
        val setupResp = client.request(
            AirPlayConstants.RTSP_METHOD_SETUP,
            AirPlayConstants.MIRROR_RTSP_PATH_STREAM,
            headers = mapOf(
                "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record"
            ),
            body = setupPlist.toByteArray(Charsets.UTF_8),
            contentType = AirPlayConstants.CONTENT_TYPE_PLIST
        )
        if (!setupResp.isSuccess) {
            throw AirPlayException(AirPlayError.HandshakeFailed(
                "SETUP", setupResp.statusCode,
                "镜像流通道建立失败 - 设备可能要求 FairPlay 配对"
            ))
        }
        Log.i(TAG, "SETUP OK, server session=${client.serverSessionId}")

        // 5. RECORD (使用服务端返回的 Session + RTP-Info 头)
        val recordPlist = PlistBuilder.buildRecordPlist(width, height)
        val recordResp = client.request(
            AirPlayConstants.RTSP_METHOD_RECORD,
            AirPlayConstants.MIRROR_RTSP_PATH_STREAM,
            headers = mapOf(
                "RTP-Info" to "seq=0;rtptime=0"
            ),
            body = recordPlist.toByteArray(Charsets.UTF_8),
            contentType = AirPlayConstants.CONTENT_TYPE_PLIST
        )
        if (!recordResp.isSuccess) {
            throw AirPlayException(AirPlayError.HandshakeFailed(
                "RECORD", recordResp.statusCode,
                "镜像录制启动失败"
            ))
        }
        Log.i(TAG, "RECORD OK, mirroring started")

        startTimeMs = System.currentTimeMillis()
        _state.value = MirrorState.Running(device.name)

        // 6. 启动帧发送循环
        scope?.launch { frameSenderLoop() }
    }.onFailure { error ->
        Log.e(TAG, "Mirror start failed: ${error.displayText}")
        _state.value = MirrorState.Error(error.displayText)
        cleanup()
    }

    fun stop() {
        runCatching {
            rtsp?.request(
                AirPlayConstants.RTSP_METHOD_TEARDOWN,
                AirPlayConstants.MIRROR_RTSP_PATH_STREAM
            )
        }
        cleanup()
        _state.value = MirrorState.Idle
    }

    private fun cleanup() {
        runCatching { rtsp?.disconnect() }
        rtsp = null
        scope?.cancel()
        scope = null
        frameQueue.clear()
        targetDevice = null
        contextRef = null
    }

    /**
     * 接收来自 H264Encoder 的编码帧，加入发送队列
     *
     * AirPlay 镜像帧格式:
     *  [4 字节大端时间戳 (90kHz 时钟)] + [H.264 NAL 数据 (Annex B 格式)]
     */
    fun enqueueFrame(nalData: ByteArray, isKeyFrame: Boolean, presentationTimeUs: Long) {
        if (_state.value !is MirrorState.Running) return
        // 90kHz 时钟: 1 us = 0.09 tick
        val pts90k = ((presentationTimeUs * 90) / 1000).toInt() and 0x7FFFFFFF
        frameQueue.add(MirrorFrame(nalData, isKeyFrame, pts90k))
        if (frameQueue.size > 60) {
            var dropped = 0
            while (frameQueue.size > 30) {
                frameQueue.poll() ?: break
                dropped++
            }
            Log.w(TAG, "Dropped $dropped frames due to backpressure")
        }
    }

    private suspend fun frameSenderLoop() {
        while (scope?.isActive == true && _state.value is MirrorState.Running) {
            val frame = frameQueue.poll()
            if (frame == null) {
                delay(5)
                continue
            }
            sendFrame(frame)
        }
    }

    private fun sendFrame(frame: MirrorFrame) {
        val client = rtsp ?: return
        runCatching {
            val payload = ByteArray(4 + frame.data.size)
            ByteBuffer.wrap(payload, 0, 4).putInt(frame.pts90k)
            System.arraycopy(frame.data, 0, payload, 4, frame.data.size)

            client.sendBinaryFrame(
                AirPlayConstants.MIRROR_RTSP_PATH_STREAM,
                payload,
                extraHeaders = mapOf(
                    "X-Apple-PT" to frame.pts90k.toString()
                )
            )
        }.onFailure {
            Log.e(TAG, "Send frame failed: ${it.message}")
        }
    }

    private data class MirrorFrame(
        val data: ByteArray,
        val isKeyFrame: Boolean,
        val pts90k: Int
    )
}

sealed class MirrorState {
    object Idle : MirrorState()
    object Connecting : MirrorState()
    data class Running(val deviceName: String) : MirrorState()
    data class Error(val message: String) : MirrorState()
}