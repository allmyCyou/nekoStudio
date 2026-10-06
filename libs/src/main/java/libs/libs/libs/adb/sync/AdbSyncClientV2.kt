package libs.libs.libs.adb.sync

import libs.libs.libs.adb.connect.AdbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

public class AdbSyncClientV2(
    connection: AdbConnection
) : AdbSyncClient(connection) {

    private var isV2SupportedCache: Boolean? = null

    /**
     * 检查当前连接是否支持 Sync V2 协议
     */
    public suspend fun isV2Supported(): Boolean {
        isV2SupportedCache?.let { return it }
        val supported = try {
            // 使用根目录探针测试 STA2
            val (stream, reader) = openSyncReader()
            try {
                val requestBytes = SyncCommandV2.createRequestV2(
                    id = SyncCommandV2.ID_STA2,
                    mode = 0,
                    flags = SyncFlags.FLAG_NONE,
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

    public suspend fun statV2(remotePath: String): FileStatV2 = withContext(Dispatchers.IO) {
        if (isV2Supported()) {
            val (stream, reader) = openSyncReader()
            try {
                val requestBytes = SyncCommandV2.createRequestV2(
                    id = SyncCommandV2.ID_STA2,
                    mode = 0,
                    flags = SyncFlags.FLAG_NONE,
                    remotePath = remotePath
                )
                stream.write(requestBytes)

                val respHeader = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                val (id, _) = SyncCommand.parseHeader(respHeader)

                if (id == SyncCommandV2.ID_STA2 || id == SyncCommandV2.ID_LSTA) {
                    val payload = reader.readExactBytes(68)
                    return@withContext ByteBuffer.wrap(payload)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .parseSyncStatV2(remotePath)
                }
            } catch (_: Exception) {
                // 失败则 Fallback
            } finally {
                stream.close()
            }
        }

        stat(remotePath).toFileStatV2()
    }

    public suspend fun listV2(remotePath: String): List<FileStatV2> = withContext(Dispatchers.IO) {
        if (isV2Supported()) {
            val (stream, reader) = openSyncReader()
            val entries = mutableListOf<FileStatV2>()

            try {
                val requestBytes = SyncCommandV2.createRequestV2(
                    id = SyncCommandV2.ID_LST2,
                    mode = 0,
                    flags = SyncFlags.FLAG_NONE,
                    remotePath = remotePath
                )
                stream.write(requestBytes)

                var isV2Valid = false

                while (true) {
                    val headerBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                    val (id, nameLen) = SyncCommand.parseHeader(headerBytes)

                    when (id) {
                        SyncCommandV2.ID_DNT2 -> {
                            isV2Valid = true
                            val statBytes = reader.readExactBytes(68)
                            val nameBytes = reader.readExactBytes(nameLen)
                            val fileName = String(nameBytes, Charsets.UTF_8)

                            if (fileName != "." && fileName != "..") {
                                val fullPath = if (remotePath.endsWith("/")) "$remotePath$fileName" else "$remotePath/$fileName"
                                val fileStat = ByteBuffer.wrap(statBytes)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .parseSyncStatV2(fullPath)
                                entries.add(fileStat)
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
            } finally {
                stream.close()
            }
        }

        // Fallback 到 V1
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
     * V2 Push (SND2)
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
        // 先判断设备是否真正支持 Sync V2 且无压缩 Feature Flag，避免盲目发送 SND2 导致 Socket 爆满
        if (flags == SyncFlags.FLAG_NONE && isV2Supported()) {
            var v2Success = false
            val (stream, reader) = openSyncReader()
            try {
                val requestBytes = SyncCommandV2.createRequestV2(SyncCommandV2.ID_SND2, mode, flags, remotePath)
                stream.write(requestBytes)

                val buffer = ByteArray(MAX_SYNC_DATA_SIZE)
                var bytesWritten = 0L
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    if (read > 0) {
                        val dataHeader = SyncCommand.createHeader(SyncCommand.ID_DATA, read)
                        val payload = if (read == buffer.size) buffer else buffer.copyOf(read)

                        stream.write(dataHeader + payload)
                        bytesWritten += read
                        onProgress?.invoke(bytesWritten, totalSize)
                    }
                }

                val doneHeader = SyncCommand.createHeader(SyncCommand.ID_DONE, sanitizeMtime(mtime))
                stream.write(doneHeader)

                val respHeaderBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                val (id, len) = SyncCommand.parseHeader(respHeaderBytes)

                if (id == SyncCommand.ID_OKAY) {
                    v2Success = true
                } else if (id == SyncCommand.ID_FAIL) {
                    val errorMsg = String(reader.readExactBytes(len), Charsets.UTF_8)
                    throw IllegalStateException("Push V2 (SND2) failed: $errorMsg")
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (_: Exception) {
                // 网络/协议报错则降级到 V1
            } finally {
                stream.close()
            }

            if (v2Success) return@withContext
        }

        // 降级回退到 V1 Push
        push(inputStream, remotePath, totalSize, mode, mtime, onProgress)
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
        if (flags == SyncFlags.FLAG_NONE && isV2Supported()) {
            var v2Success = false
            val (stream, reader) = openSyncReader()
            try {
                val fileStat = statV2(remotePath)
                if (fileStat.exists) {
                    val requestBytes = SyncCommandV2.createRequestV2(SyncCommandV2.ID_RCV2, 0, flags, remotePath)
                    stream.write(requestBytes)

                    var bytesRead = 0L
                    var isV2Valid = false

                    while (true) {
                        val headerBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                        val (id, len) = SyncCommand.parseHeader(headerBytes)

                        when (id) {
                            SyncCommand.ID_DATA -> {
                                isV2Valid = true
                                val chunk = reader.readExactBytes(len)
                                outputStream.write(chunk)
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
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (_: Exception) {
            } finally {
                stream.close()
            }

            if (v2Success) return@withContext
        }

        // 降级回退到 V1 Pull
        pull(remotePath, outputStream, onProgress)
    }
}
