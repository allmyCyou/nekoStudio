package libs.libs.libs.adb.usb.accessory

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream

public class AdbUsbAccessoryManager(private val usbManager: UsbManager) {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null

    /**
     * 发送 AOA 握手控制命令，指示目标 USB 设备切换到 USB Accessory 模式
     */
    public suspend fun initAccessoryMode(
        device: UsbDevice,
        manufacturer: String = "NekoStudio",
        model: String = "AdbBridge",
        description: String = "ADB USB Accessory Mode",
        version: String = "1.0",
        uri: String = "https://github.com/allmyCyou/nekoStudio",
        serial: String = "Neko-001"
    ): Boolean = withContext(Dispatchers.IO) {
        val connection: UsbDeviceConnection = usbManager.openDevice(device) ?: return@withContext false

        try {
            // 1. 查询设备支持的 AOA 协议版本 (Request 51)
            val versionBuffer = ByteArray(2)
            val len = connection.controlTransfer(
                0xC0, // USB_DIR_IN | USB_TYPE_VENDOR
                51,   // ACCESSORY_GET_PROTOCOL
                0, 0, versionBuffer, 2, 2000
            )

            if (len < 2) {
                return@withContext false
            }

            val protocolVersion = (versionBuffer[1].toInt() shl 8) or (versionBuffer[0].toInt() and 0xFF)
            if (protocolVersion < 1) { // 必须支持 AOA 1.0 或 2.0
                return@withContext false
            }

            // 2. 发送 AOA 标识字符串信息 (Request 52)
            sendAccessoryString(connection, 0, manufacturer)
            sendAccessoryString(connection, 1, model)
            sendAccessoryString(connection, 2, description)
            sendAccessoryString(connection, 3, version)
            sendAccessoryString(connection, 4, uri)
            sendAccessoryString(connection, 5, serial)

            // 3. 触发设备重连并进入 Accessory 模式 (Request 53)
            val result = connection.controlTransfer(
                0x40, // USB_DIR_OUT | USB_TYPE_VENDOR
                53,   // ACCESSORY_START
                0, 0, null, 0, 2000
            )

            result >= 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            connection.close()
        }
    }

    private fun sendAccessoryString(
        connection: UsbDeviceConnection,
        index: Int,
        str: String
    ) {
        val bytes = "$str\u0000".toByteArray(Charsets.UTF_8)
        connection.controlTransfer(
            0x40, // USB_DIR_OUT | USB_TYPE_VENDOR
            52,   // ACCESSORY_SEND_STRING
            0, index, bytes, bytes.size, 2000
        )
    }

    /**
     * 连接已处于 Accessory 模式的设备
     */
    public suspend fun openAccessory(accessory: UsbAccessory): Boolean = withContext(Dispatchers.IO) {
        close()

        val pfd = usbManager.openAccessory(accessory) ?: return@withContext false
        this@AdbUsbAccessoryManager.fileDescriptor = pfd
        this@AdbUsbAccessoryManager.inputStream = FileInputStream(pfd.fileDescriptor)
        this@AdbUsbAccessoryManager.outputStream = FileOutputStream(pfd.fileDescriptor)

        true
    }

    /**
     * 从 Accessory 文件流读取包
     */
    public suspend fun readPacket(): AdbPacket = withContext(Dispatchers.IO) {
        val stream = inputStream ?: throw IllegalStateException("Accessory stream not opened")

        val headerBytes = ByteArray(AdbPacket.HEADER_SIZE)
        readExactly(stream, headerBytes, AdbPacket.HEADER_SIZE)

        val header = AdbPacket.parseHeader(headerBytes)
        check(header.isValid) { "Invalid ADB packet header over USB Accessory" }

        val payload = if (header.dataLength > 0) {
            ByteArray(header.dataLength).also { readExactly(stream, it, header.dataLength) }
        } else {
            ByteArray(0)
        }

        AdbPacket(
            command = header.command,
            arg0 = header.arg0,
            arg1 = header.arg1,
            payload = payload
        )
    }

    /**
     * 向 Accessory 文件流写入包
     */
    public suspend fun writePacket(packet: AdbPacket, skipChecksum: Boolean = true) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IllegalStateException("Accessory stream not opened")
        val bytes = packet.toByteArray(skipChecksum = skipChecksum)
        stream.write(bytes)
        stream.flush()
    }

    private fun readExactly(stream: FileInputStream, buffer: ByteArray, length: Int) {
        var bytesRead = 0
        while (bytesRead < length) {
            val count = stream.read(buffer, bytesRead, length - bytesRead)
            if (count == -1) throw IllegalStateException("USB Accessory stream closed unexpectedly")
            bytesRead += count
        }
    }

    public fun close() {
        try {
            inputStream?.close()
            outputStream?.close()
            fileDescriptor?.close()
        } catch (_: Exception) {
        } finally {
            inputStream = null
            outputStream = null
            fileDescriptor = null
        }
    }

    public val isConnected: Boolean get() = fileDescriptor != null
}
