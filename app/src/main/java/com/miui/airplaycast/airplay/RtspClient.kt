package com.miui.airplaycast.airplay

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * 极简 RTSP 客户端 - 用于 AirPlay 屏幕镜像会话
 *
 * AirPlay 镜像基于 RTSP/RTP，握手流程:
 *  1. POST /info ( plist 包含设备能力 )
 *  2. SETUP /stream  (建立流通道，返回 server_port)
 *  3. RECORD         (开始传输)
 *  4. POST /stream   (持续上传 H.264 NAL 数据)
 *  5. TEARDOWN       (结束会话)
 *
 * 注意: AirPlay 2 镜像的 H.264 数据流需通过 FairPlay 加密
 *      本实现支持原始 RTSP 信令，加密层另由 [MirroringEncryption] 处理
 */
class RtspClient(
    private val host: String,
    private val port: Int = AirPlayConstants.MIRROR_DEFAULT_PORT,
    private val timeoutMs: Int = 8000
) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    val isConnected: Boolean get() = socket?.isConnected == true && !socket!!.isClosed

    val sessionId: String = UUID.randomUUID().toString()

    /**
     * 连接 RTSP 服务
     */
    fun connect(): Boolean {
        return runCatching {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            sock.soTimeout = timeoutMs
            socket = sock
            reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
            true
        }.getOrDefault(false)
    }

    /**
     * 发送 RTSP 请求并返回状态码与头部
     *
     * @param method RTSP 方法 (SETUP / RECORD / TEARDOWN / ...)
     * @param path 路径
     * @param headers 额外头部
     * @param body 请求体
     * @return (status code, headers map, body string)
     */
    fun request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null
    ): RtspResponse {
        val w = writer ?: throw IllegalStateException("RTSP not connected")
        val r = reader ?: throw IllegalStateException("RTSP not connected")

        val req = buildString {
            append("$method rtsp://$host:$port$path RTSP/1.0\r\n")
            append("CSeq: ${nextCSeq()}\r\n")
            append("User-Agent: ${AirPlayConstants.USER_AGENT}\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            if (body != null) append("Content-Length: ${body.size}\r\n")
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

        return readResponse(r, socket!!.getInputStream())
    }

    private fun readResponse(r: BufferedReader, inputStream: java.io.InputStream): RtspResponse {
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

        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = inputStream.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read)
        } else ""

        return RtspResponse(statusCode, headers, body)
    }

    fun disconnect() {
        runCatching { writer?.flush() }
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }

    private var cseq = 0
    private fun nextCSeq(): Int = ++cseq
}

data class RtspResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String
) {
    val isSuccess: Boolean get() = statusCode in 200..299
}
