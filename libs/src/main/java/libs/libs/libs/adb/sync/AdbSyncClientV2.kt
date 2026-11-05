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

    public suspend fun isV2Supported(): Boolean {
        isV2SupportedCache?.let { return it }
        val supported = try {
            val (stream, reader) = openSyncReader()
            try {
                // 使用合法的 V2 STA2 请求探测：id + path_length + path
                stream.write(SyncCommandV2.createSyncRequest(SyncCommandV2.ID_STA2, "/"))
                val respHeader = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                val (id, _) = SyncCommand.parseHeader(respHeader)
                // 设备可能返回 STA2 或 LSTA（取决于是否跟随符号链接）
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
                stream.write(SyncCommandV2.createSyncRequest(SyncCommandV2.ID_STA2, remotePath))

                val respHeader = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                val (id, error) = SyncCommand.parseHeader(respHeader)

                if (id == SyncCommandV2.ID_STA2 || id == SyncCommandV2.ID_LSTA) {
                    // sync_stat_v2 总长 72 字节，id(4) + error(4) + 64 字节 stat payload
                    // 已读 8 字节，再读 64 字节
                    val statPayload = reader.readExactBytes(64)
                    val buf = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(error)
                        .put(statPayload)
                    buf.flip()
                    return@withContext buf.parseSyncStatV2(remotePath)
                }
            } catch (_: Exception) {
                // 降级
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
                stream.write(SyncCommandV2.createSyncRequest(SyncCommandV2.ID_LST2, remotePath))

                var isV2Valid = false
                while (true) {
                    val headerBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                    val (id, error) = SyncCommand.parseHeader(headerBytes)

                    when (id) {
                        SyncCommandV2.ID_DNT2 -> {
                            isV2Valid = true
                            // sync_dent_v2 总长 76 字节，id(4) + error(4) + 64 字节 stat + namelen(4)
                            // 已读 8 字节，再读 68 字节
                            val remaining = reader.readExactBytes(68)
                            val buf = ByteBuffer.wrap(remaining).order(ByteOrder.LITTLE_ENDIAN)
                            val statPayload = ByteArray(64)
                            buf.get(statPayload)
                            val nameLen = buf.int

                            val nameBytes = reader.readExactBytes(nameLen)
                            val fileName = String(nameBytes, Charsets.UTF_8)

                            if (fileName != "." && fileName != ".." &&
                                !fileName.contains("/") && !fileName.contains("\\")
                            ) {
                                val fullPath = if (remotePath.endsWith("/")) {
                                    "$remotePath$fileName"
                                } else {
                                    "$remotePath/$fileName"
                                }
                                val statBuf = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(error)
                                    .put(statPayload)
                                statBuf.flip()
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
                // 降级
            } finally {
                stream.close()
            }
        }
        // V1 降级
        val v1Entries = list(remotePath)
        v1Entries.map { dent ->
            FileStatV2(
                path = if (remotePath.endsWith("/")) "$remotePath${dent.name}"
                       else "$remotePath/${dent.name}",
                error = 0, dev = 0, ino = 0, mode = dent.mode, nlink = 1,
                uid = 0, gid = 0, size = dent.size,
                atime = dent.mtime, mtime = dent.mtime, ctime = dent.mtime
            )
        }
    }

    public suspend fun pushV2(
        inputStream: InputStream,
        remotePath: String,
        totalSize: Long = -1L,
        mode: Int = FilePermissions.DEFAULT_MODE,
        flags: Int = SyncFlags.FLAG_NONE,
        mtime: Long = System.currentTimeMillis() / 1000,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        if (isV2Supported()) {
            var v2Success = false
            val (stream, reader) = openSyncReader()
            try {
                // 1. 发送 SyncRequest (id=SND2, path)
                stream.write(SyncCommandV2.createSyncRequest(SyncCommandV2.ID_SND2, remotePath))
                // 2. 发送 SND2 模式协商 (id=SND2, mode, flags)
                stream.write(SyncCommandV2.createSendV2(mode, flags))

                // 3. 发送数据块
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

                // 4. 发送 DONE
                stream.write(SyncCommand.createHeader(SyncCommand.ID_DONE, sanitizeMtime(mtime)))

                // 5. 读取最终响应
                val resp = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                val (id, len) = SyncCommand.parseHeader(resp)
                if (id == SyncCommand.ID_OKAY) {
                    v2Success = true
                } else if (id == SyncCommand.ID_FAIL) {
                    val msg = String(reader.readExactBytes(len), Charsets.UTF_8)
                    throw IllegalStateException("SND2 failed: $msg")
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (_: Exception) {
                // 网络异常，降级
            } finally {
                stream.close()
            }
            if (v2Success) return@withContext
        }
        push(inputStream, remotePath, totalSize, mode, mtime, onProgress)
    }

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
                    // 1. 发送 SyncRequest (id=RCV2, path)
                    stream.write(SyncCommandV2.createSyncRequest(SyncCommandV2.ID_RCV2, remotePath))
                    // 2. 发送 RCV2 flags (id=RCV2, flags)
                    stream.write(SyncCommandV2.createRecvV2(flags))

                    var bytesRead = 0L
                    var isV2Valid = false
                    while (true) {
                        val header = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                        val (id, len) = SyncCommand.parseHeader(header)
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
                                val msg = String(reader.readExactBytes(len), Charsets.UTF_8)
                                throw IllegalStateException("RCV2 failed: $msg")
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