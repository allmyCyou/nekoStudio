package libs.libs.libs.adb.pair

import libs.libs.libs.adb.key.AdbKeyManager

public class AdbPairingManager(
    private val keyManager: AdbKeyManager
) {
    private val client = AdbPairingClient(keyManager)

    /**
     * 发起 SPAKE2 6 位无线配对码配对
     */
    public suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        listener: AdbPairingListener? = null
    ): Boolean {
        return client.pair(host, port, pairingCode, listener)
    }

    /**
     * 发起 SPAKE2 无线配对（基于 Result 包装）
     */
    public suspend fun pairWithResult(
        host: String,
        port: Int,
        pairingCode: String
    ): Result<String> {
        var resultPubKey: String? = null
        var error: Throwable? = null

        val success = client.pair(host, port, pairingCode, object : AdbPairingListener {
            override fun onPairingStarted() {}
            override fun onPairingSuccess(peerPublicKey: String?) {
                // 明确区分，如果服务端没给就赋值 null 或给予准确标记，不要混淆为本地公钥
                resultPubKey = peerPublicKey
            }
            override fun onPairingFailed(throwable: Throwable) {
                error = throwable
            }
        })

        return if (success) {
            Result.success(resultPubKey ?: keyManager.getAdbPublicKeyString())
        } else {
            Result.failure(error ?: IllegalStateException("SPAKE2 pairing failed without exception details"))
        }
    }
}
