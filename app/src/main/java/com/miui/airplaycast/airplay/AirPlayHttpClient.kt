package com.miui.airplaycast.airplay

import com.miui.airplaycast.discovery.AirPlayDevice
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * AirPlay HTTP 控制客户端
 *
 * 实现 AirPlay 1 控制协议，封装 /server-info、/play、/rate、/stop、/scrub、/volume 等命令
 * 使用 OkHttp 进行 HTTP 通信
 *
 * 每个 AirPlay 会话需要分配 X-Apple-Session-ID 用于服务端追踪
 */
class AirPlayHttpClient(
    private val device: AirPlayDevice
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val sessionId: String = UUID.randomUUID().toString().uppercase()

    private val baseUrl: String = "http://${device.host.hostAddress}:${device.port}"

    /**
     * 查询设备信息
     * 返回 JSON: {deviceID, model, modelName, osVersion, macAddress, ...}
     */
    fun serverInfo(): String? {
        return executeGet(AirPlayConstants.PATH_SERVER_INFO)
    }

    /**
     * 播放媒体 (URL 或本地文件)
     *
     * body 示例:
     * Content-Location: http://example.com/video.mp4
     * Start-Position: 0.0
     */
    fun play(contentLocation: String, startPosition: Double = 0.0): Boolean {
        val body = buildString {
            append("Content-Location: ").append(contentLocation).append("\r\n")
            append("Start-Position: ").append(startPosition).append("\r\n")
        }.toRequestBody(AirPlayConstants.CONTENT_TYPE_PARAMS.toMediaType())

        val response = executePost(AirPlayConstants.PATH_PLAY, body, AirPlayConstants.CONTENT_TYPE_PARAMS)
        return response?.isSuccessful == true
    }

    /**
     * 播放速率: 0 = 暂停, 1 = 播放
     */
    fun rate(value: Float): Boolean {
        val request = Request.Builder()
            .url("$baseUrl${AirPlayConstants.PATH_RATE}?value=$value")
            .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
            .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return executeRequest(request)?.isSuccessful == true
    }

    fun pause() = rate(0f)
    fun resume() = rate(1f)

    /**
     * 停止播放
     */
    fun stop(): Boolean {
        val request = Request.Builder()
            .url("$baseUrl${AirPlayConstants.PATH_STOP}")
            .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
            .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return executeRequest(request)?.isSuccessful == true
    }

    /**
     * 跳转播放位置 (秒)
     */
    fun scrub(position: Double): Boolean {
        val request = Request.Builder()
            .url("$baseUrl${AirPlayConstants.PATH_SCRUB}?position=$position")
            .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
            .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return executeRequest(request)?.isSuccessful == true
    }

    /**
     * 查询播放进度, 返回 "duration: x.x\r\nposition: y.y"
     */
    fun queryScrub(): Map<String, Double> {
        val result = executeGet(AirPlayConstants.PATH_SCRUB) ?: return emptyMap()
        val map = mutableMapOf<String, Double>()
        result.split("\n").forEach { line ->
            val parts = line.split(":")
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim().toDoubleOrNull()
                if (value != null) map[key] = value
            }
        }
        return map
    }

    /**
     * 设置音量 (0.0 - 1.0)
     */
    fun setVolume(volume: Float): Boolean {
        val request = Request.Builder()
            .url("$baseUrl${AirPlayConstants.PATH_VOLUME}?volume=$volume")
            .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
            .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return executeRequest(request)?.isSuccessful == true
    }

    /**
     * 推送图片 (用于照片投放)
     */
    fun sendPhoto(imageBytes: ByteArray, transition: String = "None"): Boolean {
        val request = Request.Builder()
            .url("$baseUrl${AirPlayConstants.PATH_PHOTO}?transition=$transition")
            .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
            .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
            .header(AirPlayConstants.HEADER_CONTENT_TYPE, "image/jpeg")
            .post(imageBytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        return executeRequest(request)?.isSuccessful == true
    }

    private fun executeGet(path: String): String? {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
            .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
            .get()
            .build()
        return executeRequest(request)?.body?.string()
    }

    private fun executePost(path: String, body: okhttp3.RequestBody, contentType: String): Response? {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
            .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
            .header(AirPlayConstants.HEADER_CONTENT_TYPE, contentType)
            .post(body)
            .build()
        return executeRequest(request)
    }

    private fun executeRequest(request: Request): Response? {
        return runCatching {
            httpClient.newCall(request).execute()
        }.getOrNull()
    }
}
