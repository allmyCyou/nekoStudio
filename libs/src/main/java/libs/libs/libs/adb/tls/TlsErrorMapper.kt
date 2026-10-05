package libs.libs.libs.adb.tls

import java.io.IOException
import java.nio.channels.InterruptedByTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException

/**
 * 借鉴 kadb 的 TLS 错误映射器：递归分析 Throwable 异常链
 */
internal object TlsErrorMapper {

    fun map(throwable: Throwable): AdbTlsException {
        if (throwable is AdbTlsException) return throwable

        // 收集整个 cause 链中的错误信息
        val messages = buildString {
            generateSequence(throwable) { it.cause }.forEach { cause ->
                cause.message?.let { append(it.lowercase()).append('\n') }
            }
        }

        // 检查 BoringSSL / Conscrypt 常见未授权或拒绝 Alert 标记
        if (messages.contains("certificate_required") ||
            messages.contains("unknown_ca") ||
            messages.contains("access_denied") ||
            messages.contains("certificate_unknown") ||
            messages.contains("bad_certificate")
        ) {
            return AdbTlsException.PeerUnverified(
                "设备拒绝 TLS 连接：证书未信任或未在设备端确认配对授权", throwable
            )
        }

        // 超时拦截
        if (generateSequence(throwable) { it.cause }.any { it is InterruptedByTimeoutException }) {
            return AdbTlsException.HandshakeTimeout("TLS 握手超时", throwable)
        }

        // 细化 SSL 异常
        val rootCause = generateSequence(throwable) { it.cause }
            .firstOrNull { it is SSLHandshakeException || it is SSLProtocolException || it is SSLException }

        return when (rootCause) {
            is SSLPeerUnverifiedException -> AdbTlsException.PeerUnverified("对端证书验证失败: ${throwable.message}", throwable)
            is SSLHandshakeException -> AdbTlsException.HandshakeFailed("TLS 握手失败: ${throwable.message}", throwable)
            is SSLProtocolException -> AdbTlsException.ProtocolError("TLS 协议错误: ${throwable.message}", throwable)
            is SSLException -> AdbTlsException.ProtocolError("SSL 传输异常: ${throwable.message}", throwable)
            else -> AdbTlsException.NetworkError("TLS 传输层异常: ${throwable.message}", throwable)
        }
    }
}
