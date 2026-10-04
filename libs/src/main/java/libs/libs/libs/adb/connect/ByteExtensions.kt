package libs.libs.libs.adb.connect

@Suppress("NOTHING_TO_INLINE")
internal inline fun ByteArray.readIntLe(offset: Int = 0): Int {
    return (this[offset].toInt() and 0xFF) or
           ((this[offset + 1].toInt() and 0xFF) shl 8) or
           ((this[offset + 2].toInt() and 0xFF) shl 16) or
           ((this[offset + 3].toInt() and 0xFF) shl 24)
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun Int.toIntLeBytes(): ByteArray {
    return byteArrayOf(
        this.toByte(),
        (this shr 8).toByte(),
        (this shr 16).toByte(),
        (this shr 24).toByte()
    )
}
