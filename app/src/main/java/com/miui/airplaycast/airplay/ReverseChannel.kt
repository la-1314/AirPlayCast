package com.miui.airplaycast.airplay

import android.util.Log
import com.miui.airplaycast.discovery.AirPlayDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ReverseChannel(
    private val device: AirPlayDevice,
    private val sessionId: String,
    private val dacpId: String,
    private val activeRemote: String
) {
    companion object {
        private const val TAG = "ReverseChannel"
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val _events = MutableSharedFlow<ReverseEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ReverseEvent> = _events.asSharedFlow()

    private var scope: CoroutineScope? = null
    @Volatile private var running = false

    fun start(): Boolean {
        if (running) return true
        running = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope?.launch { listenLoop() }
        Log.i(TAG, "Reverse channel started to ${device.name}")
        return true
    }

    fun stop() {
        running = false
        scope?.cancel()
        scope = null
        Log.i(TAG, "Reverse channel stopped")
    }

    private suspend fun listenLoop() {
        while (running && scope?.isActive == true) {
            try {
                val request = Request.Builder()
                    .url("http://${device.host.hostAddress}:${device.port}${AirPlayConstants.PATH_REVERSE}")
                    .header(AirPlayConstants.HEADER_USER_AGENT, AirPlayConstants.USER_AGENT)
                    .header(AirPlayConstants.HEADER_X_APPLE_SESSION, sessionId)
                    .header(AirPlayConstants.HEADER_X_APPLE_DACP_ID, dacpId)
                    .header(AirPlayConstants.HEADER_X_APPLE_ACTIVE_REMOTE, activeRemote)
                    .header("X-Apple-Purpose", "event")
                    .header(AirPlayConstants.HEADER_CONTENT_TYPE, AirPlayConstants.CONTENT_TYPE_PARAMS)
                    .post("url=airplay://reverse".toRequestBody(null))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Reverse POST returned ${response.code}, will retry")
                    response.close()
                    kotlinx.coroutines.delay(2000)
                    continue
                }

                // 不能用 `?: run { ...; continue }`，因 continue 跨 inline lambda
                // 边界属于实验特性，改用 if-null 显式处理
                val source = response.body?.source()
                if (source == null) {
                    response.close()
                    kotlinx.coroutines.delay(2000)
                    continue
                }

                Log.i(TAG, "Reverse channel connected, waiting for events...")
                while (running && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    parseEvent(line)?.let { _events.tryEmit(it) }
                }
                response.close()
            } catch (e: IOException) {
                Log.w(TAG, "Reverse channel IO error: ${e.message}")
            }
            if (running) kotlinx.coroutines.delay(2000)
        }
    }

    private fun parseEvent(line: String): ReverseEvent? {
        if (!line.startsWith("event:")) return null
        val name = line.removePrefix("event:").trim()
        return when (name) {
            AirPlayConstants.REVERSE_EVENT_PLAYBACK -> ReverseEvent.Playback
            AirPlayConstants.REVERSE_EVENT_VOLUME -> ReverseEvent.VolumeChanged
            AirPlayConstants.REVERSE_EVENT_SLIDE -> ReverseEvent.SlideChanged
            else -> ReverseEvent.Unknown(name)
        }
    }
}

sealed class ReverseEvent {
    object Playback : ReverseEvent()
    object VolumeChanged : ReverseEvent()
    object SlideChanged : ReverseEvent()
    data class Unknown(val name: String) : ReverseEvent()
}
