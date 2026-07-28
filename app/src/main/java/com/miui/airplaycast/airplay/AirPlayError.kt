package com.miui.airplaycast.airplay

/**
 * AirPlay 协议错误类型
 *
 * 细分错误类别便于 UI 分类提示与日志定位
 */
sealed class AirPlayError(open val message: String) {
    /** 网络不通 / 连接超时 / 连接被拒 */
    data class Network(override val message: String, val cause: Throwable? = null) : AirPlayError(message)
    /** 端口错误 (例如 RTSP 端口用了 HTTP 端口) */
    data class PortMismatch(override val message: String) : AirPlayError(message)
    /** 设备要求配对但未完成 */
    data class PairingRequired(override val message: String) : AirPlayError(message)
    /** 协议握手失败 (SETUP/RECORD 等返回非 2xx) */
    data class HandshakeFailed(val stage: String, val statusCode: Int, override val message: String) : AirPlayError(message)
    /** FairPlay 加密不支持 */
    data class FairPlayNotSupported(override val message: String) : AirPlayError(message)
    /** 服务端返回错误状态码 */
    data class ServerError(val statusCode: Int, override val message: String) : AirPlayError(message)
    /** 其他未知错误 */
    data class Unknown(override val message: String, val cause: Throwable? = null) : AirPlayError(message)

    val displayText: String
        get() = when (this) {
            is Network -> "网络错误: $message"
            is PortMismatch -> "端口错误: $message"
            is PairingRequired -> "需要配对: $message"
            is HandshakeFailed -> "握手失败[$stage]: HTTP $statusCode - $message"
            is FairPlayNotSupported -> "FairPlay 不支持: $message"
            is ServerError -> "服务端错误 HTTP $statusCode: $message"
            is Unknown -> "未知错误: $message"
        }
}

/**
 * AirPlay 操作结果封装
 */
sealed class AirPlayResult<out T> {
    data class Success<T>(val value: T) : AirPlayResult<T>()
    data class Failure(val error: AirPlayError) : AirPlayResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): AirPlayResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): AirPlayResult<T> {
        if (this is Success) action(value)
        return this
    }

    inline fun onFailure(action: (AirPlayError) -> Unit): AirPlayResult<T> {
        if (this is Failure) action(error)
        return this
    }

    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): AirPlayError? = (this as? Failure)?.error
}

inline fun <T> airPlayTry(block: () -> T): AirPlayResult<T> = try {
    AirPlayResult.Success(block())
} catch (e: java.net.ConnectException) {
    AirPlayResult.Failure(AirPlayError.Network("连接被拒 - ${e.message}", e))
} catch (e: java.net.SocketTimeoutException) {
    AirPlayResult.Failure(AirPlayError.Network("连接超时 - ${e.message}", e))
} catch (e: java.net.UnknownHostException) {
    AirPlayResult.Failure(AirPlayError.Network("无法解析主机 - ${e.message}", e))
} catch (e: javax.net.ssl.SSLException) {
    AirPlayResult.Failure(AirPlayError.Network("SSL 错误 - ${e.message}", e))
} catch (e: AirPlayException) {
    AirPlayResult.Failure(e.error)
} catch (e: Throwable) {
    AirPlayResult.Failure(AirPlayError.Unknown(e.message ?: e.javaClass.simpleName, e))
}

/**
 * airPlayTry 的 suspend 版本
 *
 * 用于需要在内联块中调用 suspend 函数的场景 (如 PIN 配对回调 onPinRequired)
 * 普通 [airPlayTry] 的 block 是非 suspend lambda，无法在其中调用 suspend 函数
 */
suspend inline fun <T> airPlayTrySuspend(crossinline block: suspend () -> T): AirPlayResult<T> = try {
    AirPlayResult.Success(block())
} catch (e: java.net.ConnectException) {
    AirPlayResult.Failure(AirPlayError.Network("连接被拒 - ${e.message}", e))
} catch (e: java.net.SocketTimeoutException) {
    AirPlayResult.Failure(AirPlayError.Network("连接超时 - ${e.message}", e))
} catch (e: java.net.UnknownHostException) {
    AirPlayResult.Failure(AirPlayError.Network("无法解析主机 - ${e.message}", e))
} catch (e: javax.net.ssl.SSLException) {
    AirPlayResult.Failure(AirPlayError.Network("SSL 错误 - ${e.message}", e))
} catch (e: AirPlayException) {
    AirPlayResult.Failure(e.error)
} catch (e: Throwable) {
    AirPlayResult.Failure(AirPlayError.Unknown(e.message ?: e.javaClass.simpleName, e))
}

/**
 * 可抛出的 AirPlay 异常，便于在协议层向上传递错误
 */
class AirPlayException(val error: AirPlayError) : RuntimeException(error.message)