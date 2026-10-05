package libs.libs.libs.adb.tls

import java.io.IOException

/**
 * ADB TLS 业务异常基类及子类封装
 */
public sealed class AdbTlsException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** 握手失败：通常是设备拒绝证书或未建立 TLS 配对授权 */
    public class HandshakeFailed(message: String, cause: Throwable) : AdbTlsException(message, cause)
    
    /** 设备拒绝授权或未完成无线调试配对 */
    public class PeerUnverified(message: String, cause: Throwable) : AdbTlsException(message, cause)
    
    /** TLS 协议级别错误 */
    public class ProtocolError(message: String, cause: Throwable) : AdbTlsException(message, cause)
    
    /** 握手过程超时 */
    public class HandshakeTimeout(message: String, cause: Throwable) : AdbTlsException(message, cause)
    
    /** 网络连接或传输层异常 */
    public class NetworkError(message: String, cause: Throwable) : AdbTlsException(message, cause)
}
