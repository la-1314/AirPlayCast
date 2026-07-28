package com.miui.airplaycast.airplay

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 极简 RTSP 客户端 - 用于 AirPlay 屏幕镜像会话
 *
 * AirPlay 镜像基于 RTSP/RTP，握手流程:
 *  1. POST /info ( plist 包含设备能力 )
 *  2. SETUP /stream  (建立流通道，返回 server_port + Session)
 *  3. RECORD         (开始传输)
 *  4. POST /stream   (持续上传 H.264 NAL 数据)
 *  5. TEARDOWN       (结束会话)
 *
 * 关键修复:
 *  - 端口使用 7100 (MIRROR_DEFAULT_PORT) 而非 HTTP 7000
 *  - 解析 SETUP 响应的 Session 头，后续请求复用
 *  - 支持二进制 body (H.264 NAL 流)
 *  - 读取响应使用 BufferedInputStream 正确处理二进制
 */
class RtspClient(
    private val host: String,
    private val port: Int = AirPlayConstants.MIRROR_DEFAULT_PORT,
    private val timeoutMs: Int = 10000
) {
    companion object {
        private const val TAG = "RtspClient"
    }

    private var socket: Socket? = null
    private var rawInput: BufferedInputStream? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    val isConnected: Boolean get() = socket?.isConnected == true && !socket!!.isClosed

    /** 客户端本地生成的会话 ID (用于 SETUP 请求头) */
    val localSessionId: String = UUID.randomUUID().toString().uppercase().replace("-", "")

    /** 服务端在 SETUP 响应中返回的 Session ID (后续 RECORD/TEARDOWN 使用) */
    var serverSessionId: String? = null
        private set

    private val cseqCounter = AtomicInteger(0)

    /**
     * 连接 RTSP 服务
     *
     * @return 连接成功
     * @throws AirPlayException 端口错误或网络错误时抛出
     */
    fun connect(): Boolean {
        return try {
            // 端口校验: 防止误用 HTTP 7000 端口
            if (port == AirPlayConstants.HTTP_CONTROL_PORT) {
                throw AirPlayException(AirPlayError.PortMismatch(
                    "RTSP 客户端使用了 HTTP 控制端口 7000，应为镜像端口 7100 或 /server-info 返回的端口"
                ))
            }
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            sock.soTimeout = timeoutMs
            sock.tcpNoDelay = true
            socket = sock
            rawInput = BufferedInputStream(sock.getInputStream())
            reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
            writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.ISO_8859_1))
            Log.i(TAG, "RTSP connected to $host:$port")
            true
        } catch (e: AirPlayException) {
            throw e
        } catch (e: java.net.ConnectException) {
            throw AirPlayException(AirPlayError.Network("RTSP 连接被拒 ($host:$port) - 端口可能未开放或非镜像端口", e))
        } catch (e: java.net.SocketTimeoutException) {
            throw AirPlayException(AirPlayError.Network("RTSP 连接超时 ($host:$port)", e))
        } catch (e: Throwable) {
            throw AirPlayException(AirPlayError.Unknown("RTSP 连接失败: ${e.message}", e))
        }
    }

    /**
     * 发送 RTSP 请求并返回状态码与头部
     *
     * @param method RTSP 方法 (SETUP / RECORD / TEARDOWN / POST)
     * @param path 路径
     * @param headers 额外头部
     * @param body 请求体 (可为文本或二进制)
     * @param contentType Content-Type
     * @return RtspResponse
     */
    fun request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null
    ): RtspResponse {
        val w = writer ?: throw AirPlayException(AirPlayError.Network("RTSP 未连接"))
        val req = buildString {
            append("$method rtsp://$host:$port$path RTSP/1.0\r\n")
            append("CSeq: ${cseqCounter.incrementAndGet()}\r\n")
            append("User-Agent: ${AirPlayConstants.USER_AGENT}\r\n")
            // SETUP 后的所有请求携带服务端返回的 Session
            serverSessionId?.let { append("Session: $it\r\n") }
            contentType?.let { append("Content-Type: $it\r\n") }
            if (body != null) append("Content-Length: ${body.size}\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        w.write(req)
        if (body != null) {
            w.flush()
            socket!!.getOutputStream().write(body)
            socket!!.getOutputStream().flush()
        } else {
            w.flush()
        }
        return readResponse()
    }

    private fun readResponse(): RtspResponse {
        val r = reader ?: throw AirPlayException(AirPlayError.Network("RTSP 未连接"))
        val statusLine = r.readLine() ?: return RtspResponse(0, emptyMap(), "")
        val parts = statusLine.split(" ", limit = 3)
        val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = r.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }

        // 解析服务端返回的 Session ID
        headers["Session"]?.let { sid ->
            serverSessionId = sid.substringBefore(';').trim()
            Log.d(TAG, "Got server session id: $serverSessionId")
        }

        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = rawInput?.read(buf, read, contentLength - read) ?: -1
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, Charsets.ISO_8859_1)
        } else ""

        return RtspResponse(statusCode, headers, body)
    }

    /**
     * 发送二进制帧 (H.264 NAL)，避免 String 转换损坏
     * 用于 POST /stream 推送镜像帧
     */
    fun sendBinaryFrame(
        path: String,
        data: ByteArray,
        extraHeaders: Map<String, String> = emptyMap()
    ): RtspResponse {
        val w = writer ?: throw AirPlayException(AirPlayError.Network("RTSP 未连接"))
        val req = buildString {
            append("${AirPlayConstants.RTSP_METHOD_POST} rtsp://$host:$port$path RTSP/1.0\r\n")
            append("CSeq: ${cseqCounter.incrementAndGet()}\r\n")
            append("User-Agent: ${AirPlayConstants.USER_AGENT}\r\n")
            serverSessionId?.let { append("Session: $it\r\n") }
            append("Content-Length: ${data.size}\r\n")
            append("Content-Type: ${AirPlayConstants.CONTENT_TYPE_BINARY}\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        w.write(req)
        w.flush()
        socket!!.getOutputStream().write(data)
        socket!!.getOutputStream().flush()
        return readResponse()
    }

    fun disconnect() {
        runCatching { writer?.flush() }
        runCatching { socket?.close() }
        socket = null
        rawInput = null
        reader = null
        writer = null
        serverSessionId = null
        Log.i(TAG, "RTSP disconnected")
    }
}

data class RtspResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String
) {
    val isSuccess: Boolean get() = statusCode in 200..299
}
