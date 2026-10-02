package libs.libs.libs.adb.pair

import java.nio.charset.StandardCharsets

public object AdbProtoUtils {

    /**
     * 将 RSA 公钥封装为符合 AOSP 规范的 PeerInfo 字节数组 (8192 字节)
     */
    public fun createClientPeerInfo(rsaPublicKey: String): ByteArray {
        val keyBytes = rsaPublicKey.toByteArray(StandardCharsets.UTF_8)
        val peerInfo = PeerInfo(
            type = PeerInfo.ADB_RSA_PUB_KEY,
            data = keyBytes
        )
        return peerInfo.toByteArray()
    }

    /**
     * 解析解密后的 8192 字节 PeerInfo，提取服务端公钥或身份标识
     */
    public fun parseServerPeerInfo(decryptedData: ByteArray): String {
        require(decryptedData.size == PeerInfo.MAX_PEER_INFO_SIZE) {
            "Invalid PeerInfo length: expected ${PeerInfo.MAX_PEER_INFO_SIZE}, got ${decryptedData.size}"
        }
        val peerInfo = PeerInfo.fromByteArray(decryptedData)
        
        // 提取 C-Style 字符串并截断末尾 \0
        val rawString = String(peerInfo.data, StandardCharsets.UTF_8)
        val nullIndex = rawString.indexOf('\u0000')
        return if (nullIndex >= 0) rawString.substring(0, nullIndex) else rawString
    }
}
