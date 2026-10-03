package libs.libs.libs.adb.pair

import libs.libs.libs.adb.key.AdbKeyManager

public class AdbPairingManager(
    private val keyManager: AdbKeyManager
) {
    private val client = AdbPairingClient(keyManager)

    /**
     * 发起 SPAKE2 6 位无线配对码配对
     *
     * @param deviceName 自定义设备名称，传入 null 则保持当前已加载的名称
     */
    public suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        deviceName: String? = null,
        listener: AdbPairingListener? = null
    ): Boolean {
        return client.pair(host, port, pairingCode, deviceName, listener)
    }

    /**
     * 发起 SPAKE2 无线配对（基于 Result 包装）
     *
     * @param deviceName 自定义设备名称，传入 null 则保持当前已加载的名称
     */
    public suspend fun pairWithResult(
        host: String,
        port: Int,
        pairingCode: String,
        deviceName: String? = null
    ): Result<String> {
        var resultPubKey: String? = null
        var error: Throwable? = null

        val success = client.pair(host, port, pairingCode, deviceName, object : AdbPairingListener {
            override fun onPairingStarted() {}
            override fun onPairingSuccess(peerPublicKey: String?) {
                resultPubKey = peerPublicKey ?: keyManager.getAdbPublicKeyString()
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
