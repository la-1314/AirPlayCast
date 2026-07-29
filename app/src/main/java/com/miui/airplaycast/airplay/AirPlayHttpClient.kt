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

    val sessionId: String = UUID.randomUUID().toString().uppercase()
    val dacpId: String = UUID.randomUUID().toString().uppercase().substring(0, 16)
    val activeRemote: String = (System.currentTimeMillis() and 0xFFFFFFFFL).toString(16).uppercase()

    private val baseUrl: String = "http://${device.host.hostAddress}:${device.port}"

    var reverseChannel: ReverseChannel? = null
        private set

    fun serverInfo(): AirPlayResult<ServerInfo> = airPlayTry {
        val raw = executeGet(AirPlayConstants.PATH_SERVER_INFO)?.body?.string()
            ?: throw AirPlayException(AirPlayError.ServerError(0, "/server-info 无响应"))
        Log.d(TAG, "server-info: $raw")
        parseServerInfo(raw)
    }

    fun startReverseChannel(): AirPlayResult<Unit> = airPlayTry {
        val ch = ReverseChannel(device, sessionId, dacpId, activeRemote)
        ch.start()
        reverseChannel = ch
    }

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

    fun rate(value: Float): AirPlayResult<Unit> = airPlayTry {
        val resp = postControl("$baseUrl${AirPlayConstants.PATH_RATE}?value=$value")
        requireSuccess(resp, "/rate")
    }

    fun pause() = rate(0f)
    fun resume() = rate(1f)

    fun stop(): AirPlayResult<Unit> = airPlayTry {
        val resp = postControl("$baseUrl${AirPlayConstants.PATH_STOP}")
        requireSuccess(resp, "/stop")
    }

    fun scrub(position: Double): AirPlayResult<Unit> = airPlayTry {
        val resp = postControl("$baseUrl${AirPlayConstants.PATH_SCRUB}?position=$position")
        requireSuccess(resp, "/scrub")
    }

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

    fun setVolume(volume: Float): AirPlayResult<Unit> = airPlayTry {
        val clamped = volume.coerceIn(0f, 1f)
        val resp = postControl("$baseUrl${AirPlayConstants.PATH_VOLUME}?volume=$clamped")
        requireSuccess(resp, "/volume")
    }

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

    fun pairSetupPinStep(payload: ByteArray, stage: String = "/pair-setup-pin"): AirPlayResult<ByteArray> = airPlayTry {
        val request = Request.Builder()
            .url("$baseUrl${AirPlayConstants.PATH_PAIR_SETUP_PIN}")
            .header(AirPlayConstants.HEADER_CONTENT_TYPE, AirPlayConstants.CONTENT_TYPE_PAIRING)
            .post(payload.toRequestBody(AirPlayConstants.CONTENT_TYPE_PAIRING.toMediaType()))
            .build()
        val resp = executeRequest(request)
            ?: throw AirPlayException(AirPlayError.ServerError(0, "$stage 无响应"))
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
                val map = com.miui.airplaycast.airplay.pairing.Tlv8.decodeToMap(body)
                val state = map[com.miui.airplaycast.airplay.pairing.Tlv8.TAG_SEQUENCE]
                state != null && com.miui.airplaycast.airplay.pairing.Tlv8.intOf(state) == 2
            }
            404 -> false
            401, 403 -> true
            else -> false
        }
    }

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

    private fun requireSuccess(resp: Response?, stage: String) {
        if (resp == null) {
            throw AirPlayException(AirPlayError.ServerError(0, "$stage 无响应"))
        }
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

    private fun parseServerInfo(raw: String): ServerInfo {
        val gson = Gson()
        // 使用 TypeToken 保留泛型信息，避免 Map<*, *> 平台类型导致类型推断失败
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
        val map: Map<String, Any?> = gson.fromJson(raw, type) ?: emptyMap()
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
            requiresAuthentication = featuresLow and AirPlayConstants.FEATURE_AUTHENTICATION != 0L,
            supportsMirroring = featuresLow and AirPlayConstants.FEATURE_MIRRORING != 0L,
            supportsVideo = featuresLow and AirPlayConstants.FEATURE_VIDEO != 0L,
            supportsAudio = featuresLow and AirPlayConstants.FEATURE_AUDIO != 0L,
            isAirPlay2 = featuresHigh and 0x40000L != 0L,
            publicKey = map["pk"] as? String,
            raw = raw
        )
    }

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
