package libs.libs.libs.adb.public

public object AdbCommand {
    // 基础 ADB 报文指令 (小端序 32 位整型 const 常量，确保 compiler 生成 tableswitch)
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

    // ADB 协议版本号
    public const val A_VERSION: Int = 0x01000000 // 基础协议版本 1.0
    public const val A_VERSION_SKIP_CHECKSUM: Int = 0x01000001 // USB 物理连接专用 / 跳过 CRC32 校验版本

    // 最大 Payload 限制
    public const val MAX_PAYLOAD: Int = 1024 * 1024 // 现代 ADB 默认为 1MB

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
    public fun calculateChecksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) {
            sum += (b.toInt() and 0xFF)
        }
        return sum
    }
}
