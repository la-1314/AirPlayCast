package com.miui.airplaycast.airplay

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 极简 HTTP 文件服务器
 *
 * 用于将本地媒体文件通过 HTTP URL 形式投放到 AirPlay 接收端
 * AirPlay Video 协议要求 Content-Location 必须是 HTTP(S) URL
 *
 * 支持特性:
 *  - HEAD / GET 请求
 *  - 范围请求 (Range / 206 Partial Content)
 *  - MIME 类型识别 (mp4, m4a, mp3, etc.)
 */
class LocalMediaServer(
    private val port: Int = 0  // 0 表示自动分配
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var serveFile: File? = null

    val actualPort: Int get() = serverSocket?.localPort ?: 0
    val isRunning: Boolean get() = running

    /**
     * 启动服务器
     */
    fun start(file: File) {
        if (running) return
        serveFile = file
        serverSocket = ServerSocket(port, 8, InetAddress.getByName("0.0.0.0"))
        running = true
        Log.i(TAG, "LocalMediaServer started on port ${serverSocket!!.localPort} serving ${file.name}")

        thread(name = "LocalMediaServer", isDaemon = true) {
            while (running) {
                runCatching {
                    val socket = serverSocket?.accept() ?: return@runCatching
                    handleClient(socket)
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        serveFile = null
    }

    /**
     * 获取本机投放 URL
     * @param hostAddress 本机在局域网内的 IP
     */
    fun buildUrl(hostAddress: String): String {
        val file = serveFile ?: return ""
        return "http://$hostAddress:${actualPort}/${file.name}"
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val output = BufferedOutputStream(s.getOutputStream())

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0]

            // 读取请求头
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val file = serveFile ?: run {
                writeError(output, 404, "Not Found")
                return
            }

            if (method == "HEAD") {
                writeHeaders(output, file, 200, file.length(), 0..file.length() - 1)
                output.flush()
                return
            }

            if (method != "GET") {
                writeError(output, 405, "Method Not Allowed")
                return
            }

            val range = headers["range"]
            val (status, totalLength, startEnd) = if (range != null && range.startsWith("bytes=")) {
                val rangeSpec = range.removePrefix("bytes=").substringBefore(",")
                val (start, end) = if (rangeSpec.startsWith("-")) {
                    val len = rangeSpec.removePrefix("-").toLong()
                    val s = file.length() - len
                    s to file.length() - 1
                } else if (rangeSpec.endsWith("-")) {
                    val s = rangeSpec.removeSuffix("-").toLong()
                    s to file.length() - 1
                } else {
                    val split = rangeSpec.split("-")
                    split[0].toLong() to split[1].toLong()
                }
                Triple(206, end - start + 1, start..end)
            } else {
                Triple(200, file.length(), 0L..file.length() - 1)
            }

            writeHeaders(output, file, status, totalLength, startEnd)

            file.inputStream().use { fis ->
                var skip = startEnd.start
                while (skip > 0) {
                    val n = fis.skip(skip)
                    if (n <= 0) break
                    skip -= n
                }
                val buf = ByteArray(8192)
                var remaining = totalLength
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = fis.read(buf, 0, toRead)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    remaining -= n
                }
            }
            output.flush()
        }
    }

    private fun writeHeaders(
        output: BufferedOutputStream,
        file: File,
        status: Int,
        contentLength: Long,
        range: LongRange
    ) {
        val statusText = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            else -> "Unknown"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status $statusText\r\n")
        sb.append("Content-Type: ${getMimeType(file)}\r\n")
        sb.append("Content-Length: $contentLength\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (status == 206) {
            sb.append("Content-Range: bytes ${range.start}-${range.endInclusive}/${file.length()}\r\n")
        }
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray())
    }

    private fun writeError(output: BufferedOutputStream, code: Int, msg: String) {
        val body = "<html><body><h1>$code $msg</h1></body></html>"
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $msg\r\n")
        sb.append("Content-Type: text/html\r\n")
        sb.append("Content-Length: ${body.length}\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        sb.append(body)
        output.write(sb.toString().toByteArray())
        output.flush()
    }

    private fun getMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "LocalMediaServer"
    }
}
