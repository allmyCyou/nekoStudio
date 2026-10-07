package libs.libs.libs.adb.sync

import libs.libs.libs.adb.connect.AdbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

public class AdbSyncClientV2(
    connection: AdbConnection
) : AdbSyncClient(connection) {

    private var isV2SupportedCache: Boolean? = null

    /**
     * 检查设备连接是否支持 Sync V2 协议
     */
    public suspend fun isV2Supported(): Boolean {
        isV2SupportedCache?.let { return it }
        val supported = try {
            val (stream, reader) = openSyncReader()
            try {
                val requestBytes = SyncCommandV2.createStatOrListRequestV2(
                    id = SyncCommandV2.ID_STA2,
                    remotePath = "/"
                )
                stream.write(requestBytes)
                val respHeader = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                val (id, _) = SyncCommand.parseHeader(respHeader)
                id == SyncCommandV2.ID_STA2 || id == SyncCommandV2.ID_LSTA
            } finally {
                stream.close()
            }
        } catch (_: Exception) {
            false
        }
        isV2SupportedCache = supported
        return supported
    }

    /**
     * V2 Stat (STA2)
     */
    public suspend fun statV2(remotePath: String): FileStatV2 = withContext(Dispatchers.IO) {
        if (isV2Supported()) {
            val (stream, reader) = openSyncReader()
            try {
                val requestBytes = SyncCommandV2.createStatOrListRequestV2(
                    id = SyncCommandV2.ID_STA2,
                    remotePath = remotePath
                )
                stream.write(requestBytes)

                val respHeader = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                val (id, error) = SyncCommand.parseHeader(respHeader)

                if (id == SyncCommandV2.ID_STA2 || id == SyncCommandV2.ID_LSTA) {
                    // STA2 结构全长 72 字节 (4B id + 4B error + 64B stat payload)
                    // respHeader 已读 8 字节 (id + error)，只需再读 64 字节
                    val remainingBytes = reader.readExactBytes(64)
                    
                    val fullPayload = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(error)
                        .put(remainingBytes)
                    
                    (fullPayload.flip() as ByteBuffer)
                    return@withContext fullPayload.parseSyncStatV2(remotePath)
                }
            } catch (_: Exception) {
                // 发生异常自动降级到 V1
            } finally {
                stream.close()
            }
        }

        stat(remotePath).toFileStatV2()
    }

    /**
     * V2 List (LST2)
     */
    public suspend fun listV2(remotePath: String): List<FileStatV2> = withContext(Dispatchers.IO) {
        if (isV2Supported()) {
            val (stream, reader) = openSyncReader()
            val entries = mutableListOf<FileStatV2>()

            try {
                val requestBytes = SyncCommandV2.createStatOrListRequestV2(
                    id = SyncCommandV2.ID_LST2,
                    remotePath = remotePath
                )
                stream.write(requestBytes)

                var isV2Valid = false

                while (true) {
                    val headerBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                    val (id, error) = SyncCommand.parseHeader(headerBytes)

                    when (id) {
                        SyncCommandV2.ID_DNT2 -> {
                            isV2Valid = true
                            
                            // AOSP DNT2 结构全长 76 字节 + 文件名:
                            // [4B "DNT2"][4B error][64B stat payload][4B namelen][namelen 字节 name]
                            // headerBytes 已读 8 字节(id + error)，还需读 68 字节(64B stat + 4B namelen)
                            val remainingBytes = reader.readExactBytes(68)
                            
                            // 提取最后 4 字节的 namelen
                            val buf = ByteBuffer.wrap(remainingBytes).order(ByteOrder.LITTLE_ENDIAN)
                            val statPayloadBytes = ByteArray(64)
                            buf.get(statPayloadBytes)
                            val nameLen = buf.int

                            val nameBytes = reader.readExactBytes(nameLen)
                            val fileName = String(nameBytes, Charsets.UTF_8)

                            if (fileName != "." && fileName != ".." && !fileName.contains("/") && !fileName.contains("\\")) {
                                val fullPath = if (remotePath.endsWith("/")) "$remotePath$fileName" else "$remotePath/$fileName"
                                
                                val statBuf = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(error)
                                    .put(statPayloadBytes)
                                
                                (statBuf.flip() as ByteBuffer)
                                entries.add(statBuf.parseSyncStatV2(fullPath))
                            }
                        }
                        SyncCommandV2.ID_LST2, SyncCommand.ID_DONE -> {
                            isV2Valid = true
                            break
                        }
                        else -> break
                    }
                }

                if (isV2Valid) return@withContext entries
            } catch (_: Exception) {
                // Fallback 到 V1
            } finally {
                stream.close()
            }
        }

        // Fallback 到 V1 List 并转换封装
        val v1Entries = list(remotePath)
        v1Entries.map { dent ->
            FileStatV2(
                path = if (remotePath.endsWith("/")) "$remotePath${dent.name}" else "$remotePath/${dent.name}",
                error = 0, dev = 0, ino = 0, mode = dent.mode, nlink = 1,
                uid = 0, gid = 0, size = dent.size, atime = dent.mtime, mtime = dent.mtime, ctime = dent.mtime
            )
        }
    }

    /**
     * 执行纯正的 ADB Sync V2 Push (SND2)
     */
    public suspend fun pushV2(
        inputStream: InputStream,
        remotePath: String,
        totalSize: Long = -1L,
        mode: Int = FilePermissions.DEFAULT_MODE,
        flags: Int = SyncFlags.FLAG_NONE,
        mtime: Long = System.currentTimeMillis() / 1000,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        val (stream, reader) = openSyncReader()
        try {
            // 1. 发送 SND2 12字节 Header + 路径
            val requestBytes = SyncCommandV2.createSendRequestV2(remotePath, mode, flags)
            stream.write(requestBytes)

            // 2. 循环推送数据块 (使用 12 字节的 V2 DATA Header)
            val buffer = ByteArray(MAX_SYNC_DATA_SIZE)
            var bytesWritten = 0L
            var read: Int

            while (inputStream.read(buffer).also { read = it } != -1) {
                if (read > 0) {
                    // V2 标准 DATA 包头: [DATA][flags][size]
                    val dataHeaderV2 = SyncCommandV2.createDataHeaderV2(read, flags)
                    
                    // 分开写入，避免内存复制与GC开销
                    stream.write(dataHeaderV2)
                    stream.write(buffer, 0, read)

                    bytesWritten += read
                    onProgress?.invoke(bytesWritten, totalSize)
                }
            }

            // 3. 发送 V2 DONE 包头: [DONE][flags][mtime]
            val doneHeaderV2 = SyncCommandV2.createDoneHeaderV2(mtime, flags)
            stream.write(doneHeaderV2)

            // 4. 读取 adbd 服务端最终响应 Header (8 字节: [ID][len])
            val respHeaderBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
            val (id, len) = SyncCommand.parseHeader(respHeaderBytes)

            when (id) {
                SyncCommand.ID_OKAY -> {
                    // 传输成功
                }
                SyncCommand.ID_FAIL -> {
                    // 修复：读取字节数组后转换为 UTF-8 字符串
                    val errorBytes = reader.readExactBytes(len)
                    val errorMsg = String(errorBytes, Charsets.UTF_8)
                    throw IOException("Adbd V2 Push FAIL: $errorMsg")
                }
                else -> {
                    throw IOException("Unexpected V2 response: id=$id, length=$len")
                }
            }
        } finally {
            runCatching { stream.close() }
        }
    }

    /**
     * V2 Pull (RCV2)
     */
    public suspend fun pullV2(
        remotePath: String,
        outputStream: OutputStream,
        flags: Int = SyncFlags.FLAG_NONE,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        if (isV2Supported()) {
            val fileStat = statV2(remotePath)
            if (fileStat.exists) {
                var v2Success = false
                val (stream, reader) = openSyncReader()
                try {
                    val requestBytes = SyncCommandV2.createSendRequestV2(
                        remotePath = remotePath,
                        mode = 0,
                        flags = flags
                    )
                    stream.write(requestBytes)

                    var bytesRead = 0L
                    var isV2Valid = false

                    while (true) {
                        val headerBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                        val (id, len) = SyncCommand.parseHeader(headerBytes)

                        when (id) {
                            SyncCommand.ID_DATA -> {
                                isV2Valid = true
                                reader.readToStream(outputStream, len)
                                bytesRead += len
                                onProgress?.invoke(bytesRead, fileStat.size)
                            }
                            SyncCommand.ID_DONE -> {
                                v2Success = isV2Valid
                                break
                            }
                            SyncCommand.ID_FAIL -> {
                                val errorMsg = String(reader.readExactBytes(len), Charsets.UTF_8)
                                throw IllegalStateException("Pull V2 (RCV2) failed: $errorMsg")
                            }
                            else -> break
                        }
                    }
                    outputStream.flush()
                } catch (e: IllegalStateException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    stream.close()
                }

                if (v2Success) return@withContext
            }
        }

        pull(remotePath, outputStream, onProgress)
    }
}
