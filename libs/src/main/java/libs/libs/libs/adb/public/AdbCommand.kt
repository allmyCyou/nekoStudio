package libs.libs.libs.adb.public

public object AdbCommand {
    // 基础 ADB 报文指令 (小端序 32 位整型 const 常量)
    public const val CMD_CNXN: Int = 0x4e584e43 // "CNXN"
    public const val CMD_AUTH: Int = 0x48545541 // "AUTH"
    public const val CMD_OPEN: Int = 0x4e45504f // "OPEN"
    public const val CMD_OKAY: Int = 0x59414b4f // "OKAY"
    public const val CMD_WRTE: Int = 0x45545257 // "WRTE"
    public const val CMD_CLSE: Int = 0x45534c43 // "CLSE"
    public const val CMD_STLS: Int = 0x534c5453 // "STLS" (Android 11+ TLS 握手指令)

    // AUTH 类型子参数
    public const val AUTH_TOKEN: Int = 1
    public const val AUTH_SIGNATURE: Int = 2
    public const val AUTH_RSAPUBLICKEY: Int = 3

    // STLS (StartTLS) 协议版本号
    public const val A_STLS_VERSION: Int = 0x01000000

    // ADB 协议版本号
    public const val A_VERSION_MIN: Int = 0x01000000 // 基础协议版本 1.0
    public const val A_VERSION_SKIP_CHECKSUM: Int = 0x01000001 // 跳过 CRC32 校验版本
    // 标准 AOSP 1.0.0 版本号
    public const val A_VERSION: Int = 0x01000000

    // 载荷与数据缓冲区限制
    public const val MAX_PAYLOAD_V1: Int = 4 * 1024 // 握手阶段 CNXN 载荷上限 (4KB)
    public const val MAX_PAYLOAD: Int = 1024 * 1024 // 默认 Stream Data Payload 限制 (1MB)
    public const val CONNECT_MAXDATA: Int = 1024 * 1024 // 宣告给对端的最大接收能力 (1MB)

    // 流量控制 (Delayed ACK) 相关常量
    public const val FEATURE_DELAYED_ACK: String = "delayed_ack"
    public const val INITIAL_DELAYED_ACK_BYTES: Int = 32 * 1024 * 1024 // 32MB 初始 Rx 窗口

    // kadb / AOSP 默认推荐的 ADB Feature 列表
    public val DEFAULT_FEATURES: List<String> = listOf(
        "shell_v2",
        "cmd",
        "abb_exec",
        "stat_v2",
        "ls_v2",
        "sendrecv_v2"
    )

    /**
     * 将 4 字节字符串解包为小端序 Int (工具辅助函数)
     */
    public fun unpack(str: String): Int {
        require(str.length == 4) { "ADB Command string must be exactly 4 characters" }
        return (str[0].code) or (str[1].code shl 8) or (str[2].code shl 16) or (str[3].code shl 24)
    }

    /**
     * 计算 command 的 magic 值 (command xor 0xFFFFFFFF)
     */
    public fun calculateMagic(command: Int): Int = command.inv()

    /**
     * 计算 payload 的简单累加校验和 (Sum of unsigned bytes)
     */
    public fun calculateChecksum(payload: ByteArray, offset: Int = 0, length: Int = payload.size): Int {
        var sum = 0
        val end = offset + length
        for (i in offset until end) {
            sum += (payload[i].toInt() and 0xFF)
        }
        return sum
    }

    /**
     * 构建 CNXN 握手 Banner Payload (例如: "host::features=shell_v2,cmd,...")
     */
    public fun buildConnectPayload(
        features: List<String> = DEFAULT_FEATURES,
        systemIdentity: String = "host::"
    ): ByteArray {
        val featureString = features.distinct().joinToString(",")
        val payloadStr = if (featureString.isNotBlank()) {
            "${systemIdentity}features=$featureString"
        } else {
            systemIdentity
        }
        val bytes = payloadStr.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PAYLOAD_V1) {
            "ADB connect banner payload is too long: ${bytes.size} > $MAX_PAYLOAD_V1"
        }
        return bytes
    }
}
