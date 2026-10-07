package libs.libs.libs.adb.sync

import libs.libs.libs.adb.connect.AdbConnection
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                val (id, error) = SyncCommand.parseHeader(respHeader)

                if (id == SyncCommandV2.ID_STA2 || id == SyncCommandV2.ID_LSTA) {
                    val remainingBytes = reader.readExactBytes(64)
                    
                    val fullPayload = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(error)
                        .put(remainingBytes)
                    
                    (fullPayload.flip() as ByteBuffer)
                    return@withContext fullPayload.parseSyncStatV2(remotePath)
                }
            } catch (_: Exception) {
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
                    val (id, error) = SyncCommand.parseHeader(headerBytes)

                    when (id) {
                        SyncCommandV2.ID_DNT2 -> {
                            isV2Valid = true
                            
                            val remainingBytes = reader.readExactBytes(68)
                            
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
            } finally {
                stream.close()
            }
        }

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
        channel: ByteReadChannel,
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
                val requestBytes = SyncCommandV2.createRequestV2(SyncCommandV2.ID_SND2, mode, flags, remotePath)
                stream.write(requestBytes)

                val buffer = ByteArray(MAX_SYNC_DATA_SIZE)
                var bytesWritten = 0L

                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
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
            } finally {
                stream.close()
            }

            if (v2Success) return@withContext
        }

        push(channel, remotePath, totalSize, mode, mtime, onProgress)
    }

    /**
     * V2 Pull (RCV2)
     */
    public suspend fun pullV2(
        remotePath: String,
        channel: ByteWriteChannel,
        flags: Int = SyncFlags.FLAG_NONE,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        if (isV2Supported()) {
            val fileStat = statV2(remotePath)
            if (fileStat.exists) {
                var v2Success = false
                val (stream, reader) = openSyncReader()
                try {
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
                                reader.readToChannel(channel, len)
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
                    channel.flush()
                } catch (e: IllegalStateException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    stream.close()
                }

                if (v2Success) return@withContext
            }
        }

        pull(remotePath, channel, onProgress)
    }
}
