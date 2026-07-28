package com.miui.airplaycast.airplay

import android.util.Log
import com.google.gson.Gson
import com.miui.airplaycast.discovery.AirPlayDevice
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * AirPlay HTTP 控制客户端
 *
 * 实现 AirPlay 1 控制协议:
 *  - /server-info  设备能力探测
 *  - /reverse      反向通道 (委托给 [ReverseChannel])
 *  - /play /rate /stop /scrub /volume /photo
 *
 * 关键头部:
 *  - X-Apple-Session-ID  会话追踪
 *  - DACP-ID             客户端唯一标识
 *  - Active-Remote       远程控制会话 ID
 *
 * 错误处理: 所有方法返回 [AirPlayResult]，保留异常堆栈便于 UI 定位
 */
class AirPlayHttpClient(
    private val device: AirPlayDevice
) {
    companion object {
        private const val TAG = "AirPlayHttpClient"
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** X-Apple-Session-ID - 用于服务端追踪整个会话 */
    val sessionId: String = UUID.randomUUID().toString().uppercase()

    /** DACP-ID - 客户端唯一标识，部分接收端必须 */
    val dacpId: String = UUID.randomUUID().toString().uppercase().substring(0, 16)

    /** Active-Remote - 远程控制会话 ID */
    val activeRemote: String = (System.currentTimeMillis() and 0xFFFFFFFFL).toString(16).uppercase()

    private val baseUrl: String = "http://${device.host.hostAddress}:${device.port}"

    /** 反向通道 (按需启动) */
    var reverseChannel: ReverseChannel? = null
        private set

    /**
     * GET /server-info 查询设备信息
     *
     * 返回 JSON 包含: deviceID, model, modelName, osVersion, macAddress,
     * features (hex string), pi, pk (公钥), etc.
     */
    fun serverInfo(): AirPlayResult<ServerInfo> = airPlayTry {
        val raw = executeGet(AirPlayConstants.PATH_SERVER_INFO)?.body?.string()
            ?: throw AirPlayException(AirPlayError.ServerError(0, "/server-info 无响应"))
        Log.d(TAG, "server-info: $raw")
        parseServerInfo(raw)
    }

    /**
     * 启动 /reverse 反向通道
     *
     * AirPlay 协议要求客户端在 /play 之前建立反向通道，
     * 否则部分接收端会拒绝后续控制命令
     */
    fun startReverseChannel(): AirPlayResult<Unit> = airPlayTry {
        val ch = ReverseChannel(device, sessionId, dacpId, activeRemote)
        ch.start()
        reverseChannel = ch
    }

    /**
     * 播放媒体 (URL 或本地文件)
     *
     * body 格式 (text/parameters):
     *   Content-Location: http://example.com/video.mp4
     *   Start-Position: 0.0
     */
    fun play(contentLocation: String, startPosition: Double = 0.0): AirPlayResult<Unit> = airPlayTry {
        val body = buildString {
            append("Content-Location: ").append(contentLocation).append("\r\n")
            append("Start-Position: ").append(startPosition).append("\r\n")
        }
        val resp = executePostWithControlHeaders(
            AirPlayConstants.PATH_PLAY,
            body.toRequestBody(AirPlayConstants.CONTENT_TYPE_PARAMS.toMediaType()),
            AirPlayConstants.CONTENT_TYPE_PARAMS
        )
        requireSuccess(resp, "/play")
    }

    /** 播放速率: 0 = 暂停, 1 = 播放 */
    fun rate(value: Float): AirPlayResult<Unit> = airPlayTry {
        val resp = postControl("$baseUrl${AirPlayConstants.PATH_RATE}?value=$value")
        requireSuccess(resp, "/rate")
    }

    fun pause() = rate(0f)
    fun resume() = rate(1f)

    /** 停止播放 */
    fun stop(): AirPlayResult<Unit> = airPlayTry {
        val resp = postControl("$baseUrl${AirPlayConstants.PATH_STOP}")
        requireSuccess(resp, "/stop")
    }

    /** 跳转播放位置 (秒) */
    fun scrub(position: Double): AirPlayResult<Unit> = airPlayTry {
        val resp = postControl("$baseUrl${AirPlayConstants.PATH_SCRUB}?position=$position")
        requireSuccess(resp, "/scrub")
    }

    /** 查询播放进度，返回 duration / position */
    fun queryScrub(): AirPlayResult<Map<String, Double>> = airPlayTry {
        val resp = executeGetWithControlHeaders(AirPlayConstants.PATH_SCRUB)
            ?: throw AirPlayException(AirPlayError.ServerError(0, "/scrub 无响应"))
        requireSuccess(resp, "/scrub")
        val raw = resp.body?.string() ?: ""
        val map = mutableMapOf<String, Double>()
        raw.split("\n").forEach { line ->
            val parts = line.split(":")
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim().toDoubleOrNull()
                if (value != null) map[key] = value
            }
        }
        map
    }

    /** 设置音量 (0.0 - 1.0) */
    fun setVolume(volume: Float): AirPlayResult<Unit> = airPlayTry {
        val clamped = volume.coerceIn(0f, 1f)
        val resp = postControl("$baseUrl${AirPlayConstants.PATH_VOLUME}?volume=$clamped")
        requireSuccess(resp, "/volume")
    }

    /** 推送图片 (用于照片投放) */
    fun sendPhoto(imageBytes: ByteArray, transition: String = "None"): AirPlayResult<Unit> = airPlayTry {
        val request = Request.Builder()
            .url("$baseUrl${AirPlayConstants.PATH_PHOTO}?transition=$transition")
            .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
            .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
            .header(AirPlayConstants.HEADER_X_APPLE_DACP_ID, dacpId)
            .header(AirPlayConstants.HEADER_X_APPLE_ACTIVE_REMOTE, activeRemote)
            .header(AirPlayConstants.HEADER_CONTENT_TYPE, "image/jpeg")
            .post(imageBytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val resp = executeRequest(request)
            ?: throw AirPlayException(AirPlayError.ServerError(0, "/photo 无响应"))
        requireSuccess(resp, "/photo")
    }

    /**
     * AirPlay 2 配对 - POST /pair-setup (第一步)
     * 返回服务端 TLV8 数据，由 [PairingManager] 处理
     */
    fun pairSetupStep(payload: ByteArray): AirPlayResult<ByteArray> = airPlayTry {
        val request = Request.Builder()
            .url("$baseUrl${AirPlayConstants.PATH_PAIR_SETUP}")
            .header(AirPlayConstants.HEADER_CONTENT_TYPE, AirPlayConstants.CONTENT_TYPE_PAIRING)
            .post(payload.toRequestBody(AirPlayConstants.CONTENT_TYPE_PAIRING.toMediaType()))
            .build()
        val resp = executeRequest(request)
            ?: throw AirPlayException(AirPlayError.ServerError(0, "/pair-setup 无响应"))
        requireSuccess(resp, "/pair-setup")
        resp.body?.bytes() ?: ByteArray(0)
    }

    /**
     * AirPlay 2 配对验证 - POST /pair-verify
     */
    fun pairVerify(payload: ByteArray): AirPlayResult<ByteArray> = airPlayTry {
        val request = Request.Builder()
            .url("$baseUrl${AirPlayConstants.PATH_PAIR_VERIFY}")
            .header(AirPlayConstants.HEADER_CONTENT_TYPE, AirPlayConstants.CONTENT_TYPE_PAIRING)
            .post(payload.toRequestBody(AirPlayConstants.CONTENT_TYPE_PAIRING.toMediaType()))
            .build()
        val resp = executeRequest(request)
            ?: throw AirPlayException(AirPlayError.ServerError(0, "/pair-verify 无响应"))
        requireSuccess(resp, "/pair-verify")
        resp.body?.bytes() ?: ByteArray(0)
    }

    /**
     * AirPlay PIN 配对 - POST /pair-setup-pin
     *
     * 用于带 PIN 码的设备首次配对 (UxPlay / Apple TV 等)。
     * 流程为 HomeKit Pair-Setup (SRP-6a + Ed25519 + HKDF + ChaCha20)，
     * 客户端使用屏幕上显示的 PIN 码作为 SRP 密码。
     *
     * 本方法仅发送一步请求，由 [PairingManager] 编排多步交换。
     *
     * @param payload TLV8 编码的请求体
     * @param stage 当前阶段名 (用于错误归类)
     * @return 服务端 TLV8 响应
     */
    fun pairSetupPinStep(payload: ByteArray, stage: String = "/pair-setup-pin"): AirPlayResult<ByteArray> = airPlayTry {
        val request = Request.Builder()
            .url("$baseUrl${AirPlayConstants.PATH_PAIR_SETUP_PIN}")
            .header(AirPlayConstants.HEADER_CONTENT_TYPE, AirPlayConstants.CONTENT_TYPE_PAIRING)
            .post(payload.toRequestBody(AirPlayConstants.CONTENT_TYPE_PAIRING.toMediaType()))
            .build()
        val resp = executeRequest(request)
            ?: throw AirPlayException(AirPlayError.ServerError(0, "$stage 无响应"))
        // 注意: PIN 错误时服务端返回 200 + TLV{error:2}，而非 4xx
        // 仅当连接失败或严重服务端错误时才抛出
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            when (code) {
                401, 403 -> throw AirPlayException(AirPlayError.PairingRequired("$stage 拒绝 - 设备要求 PIN 配对"))
                404 -> throw AirPlayException(AirPlayError.HandshakeFailed(stage, code, "设备不支持 /pair-setup-pin，可能无需 PIN"))
                else -> throw AirPlayException(AirPlayError.ServerError(code, "$stage HTTP $code"))
            }
        }
        resp.body?.bytes() ?: ByteArray(0)
    }

    /**
     * 探测设备是否要求 PIN 配对
     *
     * 通过发送 pair-setup 第一步 (M1) 探测:
     *  - 返回 200 + TLV{state:2}  => 需要 PIN (进入 SRP 流程)
     *  - 返回 404                  => 不支持 PIN (无需配对)
     *  - 返回 401/403              => 强制配对
     */
    fun probePinRequired(): AirPlayResult<Boolean> = airPlayTry {
        val m1 = com.miui.airplaycast.airplay.pairing.Tlv8.encode(listOf(
            com.miui.airplaycast.airplay.pairing.Tlv8.TAG_METHOD to
                com.miui.airplaycast.airplay.pairing.Tlv8.byteOf(com.miui.airplaycast.airplay.pairing.Tlv8.METHOD_PAIR_SETUP),
            com.miui.airplaycast.airplay.pairing.Tlv8.TAG_SEQUENCE to
                com.miui.airplaycast.airplay.pairing.Tlv8.byteOf(1)
        ))
        val request = Request.Builder()
            .url("$baseUrl${AirPlayConstants.PATH_PAIR_SETUP_PIN}")
            .header(AirPlayConstants.HEADER_CONTENT_TYPE, AirPlayConstants.CONTENT_TYPE_PAIRING)
            .post(m1.toRequestBody(AirPlayConstants.CONTENT_TYPE_PAIRING.toMediaType()))
            .build()
        val resp = executeRequest(request)
            ?: return@airPlayTry false
        val code = resp.code
        val body = resp.body?.bytes() ?: ByteArray(0)
        resp.close()
        when (code) {
            200 -> {
                // 解析 TLV 看是否有 state=2 (server 等待 M3)
                val map = com.miui.airplaycast.airplay.pairing.Tlv8.decodeToMap(body)
                val state = map[com.miui.airplaycast.airplay.pairing.Tlv8.TAG_SEQUENCE]
                state != null && com.miui.airplaycast.airplay.pairing.Tlv8.intOf(state) == 2
            }
            404 -> false  // 不支持 PIN，无需配对
            401, 403 -> true  // 强制配对
            else -> false
        }
    }

    // ---------- 内部辅助 ----------

    private fun executeGet(path: String): Response? {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
            .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
            .get()
            .build()
        return executeRequest(request)
    }

    private fun executeGetWithControlHeaders(path: String): Response? {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
            .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
            .header(AirPlayConstants.HEADER_X_APPLE_DACP_ID, dacpId)
            .header(AirPlayConstants.HEADER_X_APPLE_ACTIVE_REMOTE, activeRemote)
            .get()
            .build()
        return executeRequest(request)
    }

    private fun executePostWithControlHeaders(path: String, body: okhttp3.RequestBody, contentType: String): Response? {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
            .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
            .header(AirPlayConstants.HEADER_X_APPLE_DACP_ID, dacpId)
            .header(AirPlayConstants.HEADER_X_APPLE_ACTIVE_REMOTE, activeRemote)
            .header(AirPlayConstants.HEADER_CONTENT_TYPE, contentType)
            .post(body)
            .build()
        return executeRequest(request)
    }

    /** 控制命令统一入口 (带 control headers + 空 body) */
    private fun postControl(url: String): Response? {
        val request = Request.Builder()
            .url(url)
            .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
            .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
            .header(AirPlayConstants.HEADER_X_APPLE_DACP_ID, dacpId)
            .header(AirPlayConstants.HEADER_X_APPLE_ACTIVE_REMOTE, activeRemote)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return executeRequest(request)
    }

    /**
     * 执行请求，保留异常堆栈
     *
     * 注意：这里不再用 runCatching{}.getOrNull() 吞掉异常，
     * 网络异常会向上抛由 [airPlayTry] 分类
     */
    private fun executeRequest(request: Request): Response? {
        return try {
            httpClient.newCall(request).execute()
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "连接被拒: ${request.url} - ${e.message}")
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "连接超时: ${request.url} - ${e.message}")
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "IO 错误: ${request.url} - ${e.message}")
            throw e
        }
    }

    /**
     * 校验响应状态码，根据状态码生成对应的 [AirPlayError]
     */
    private fun requireSuccess(resp: Response, stage: String) {
        if (resp.isSuccessful) return
        val code = resp.code
        val msg = "HTTP $code at $stage"
        resp.close()
        when (code) {
            400 -> throw AirPlayException(AirPlayError.HandshakeFailed(stage, code, "请求格式错误"))
            401, 403 -> throw AirPlayException(AirPlayError.PairingRequired("$stage 需要配对 - 请先完成 HomeKit 配对"))
            404 -> throw AirPlayException(AirPlayError.HandshakeFailed(stage, code, "服务端不支持该路径，可能不是 AirPlay 接收端"))
            408 -> throw AirPlayException(AirPlayError.Network("$stage 请求超时"))
            500, 502, 503 -> throw AirPlayException(AirPlayError.ServerError(code, "服务端内部错误"))
            else -> throw AirPlayException(AirPlayError.ServerError(code, msg))
        }
    }

    /**
     * 解析 /server-info 响应
     *
     * 字段示例 (Apple TV):
     *  { "deviceID":"AA:BB:CC:DD:EE:FF", "model":"AppleTV6,2",
     *    "modelName":"Apple TV", "osVersion":"17.1",
     *    "features":"0x5A7FFFF7,0x1", "pi":"...", "pk":"..." }
     *
     * features 为 128 位，逗号分隔两段:
     *   第一段 = 低 64 位 (含 VIDEO/AUTH/MIRRORING 等常用位)
     *   第二段 = 高 64 位 (含 AirPlay 2 标志位 0x40000)
     */
    private fun parseServerInfo(raw: String): ServerInfo {
        val gson = Gson()
        val map: Map<String, Any?> = gson.fromJson(raw, Map::class.java) ?: emptyMap()
        val featuresStr = (map["features"] as? String) ?: "0"
        val (featuresLow, featuresHigh) = parseFeatures128(featuresStr)
        return ServerInfo(
            deviceId = map["deviceID"] as? String ?: map["macAddress"] as? String,
            model = map["model"] as? String,
            modelName = map["modelName"] as? String,
            osVersion = map["osVersion"] as? String,
            featuresLow = featuresLow,
            featuresHigh = featuresHigh,
            rawFeatures = featuresStr,
            // 注意: 0x80 表示"支持认证"而非"强制认证"，真正的强制配对应由 401/403 触发
            requiresAuthentication = featuresLow and AirPlayConstants.FEATURE_AUTHENTICATION != 0L,
            supportsMirroring = featuresLow and AirPlayConstants.FEATURE_MIRRORING != 0L,
            supportsVideo = featuresLow and AirPlayConstants.FEATURE_VIDEO != 0L,
            supportsAudio = featuresLow and AirPlayConstants.FEATURE_AUDIO != 0L,
            // AirPlay 2 标志位在高 64 位的 bit 18 (0x40000)
            isAirPlay2 = featuresHigh and 0x40000L != 0L,
            publicKey = map["pk"] as? String,
            raw = raw
        )
    }

    /**
     * 解析 128 位 features 字段 (格式 "0x5A7FFFF7,0x1")
     *
     * @return (low64, high64)
     */
    private fun parseFeatures128(value: String): Pair<Long, Long> {
        val segments = value.split(",").map { it.trim() }
        val low = parseHexLong(segments.getOrNull(0) ?: "0")
        val high = parseHexLong(segments.getOrNull(1) ?: "0")
        return low to high
    }

    private fun parseHexLong(s: String): Long {
        if (s.isEmpty()) return 0L
        return s.toLongOrNull(16)
            ?: s.removePrefix("0x").toLongOrNull(16)
            ?: s.toLongOrNull()
            ?: 0L
    }

    fun close() {
        reverseChannel?.stop()
        reverseChannel = null
    }
}

/**
 * /server-info 解析后的设备能力信息
 *
 * features 为 128 位，拆分为 [featuresLow] (低 64 位) 与 [featuresHigh] (高 64 位)。
 * 常用位 (MIRRORING=0x20000, AUTH=0x80, VIDEO=0x1 等) 位于低 64 位；
 * AirPlay 2 标志位 (high bit 18, 即 0x40000 << 64) 位于高 64 位。
 */
data class ServerInfo(
    val deviceId: String?,
    val model: String?,
    val modelName: String?,
    val osVersion: String?,
    val featuresLow: Long,
    val featuresHigh: Long,
    val rawFeatures: String,
    val requiresAuthentication: Boolean,
    val supportsMirroring: Boolean,
    val supportsVideo: Boolean,
    val supportsAudio: Boolean,
    val isAirPlay2: Boolean,
    val publicKey: String?,
    val raw: String
)
