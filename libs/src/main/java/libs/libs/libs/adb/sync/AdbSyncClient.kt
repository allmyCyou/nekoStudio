package libs.libs.libs.adb.sync

import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.connect.AdbStream
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

public open class AdbSyncClient(
    @PublishedApi internal val connection: AdbConnection
) {
    public companion object {
        public const val MAX_SYNC_DATA_SIZE: Int = 64 * 1024 - SyncCommand.HEADER_SIZE // 65,528 字节
    }

    protected suspend fun openSyncStream(): AdbStream {
        return connection.openStream("sync:")
            ?: throw IllegalStateException("Failed to open ADB sync service")
    }

    protected suspend fun openSyncReader(): Pair<AdbStream, SyncStreamReader> {
        val stream = openSyncStream()
        return stream to SyncStreamReader(stream)
    }

    protected fun sanitizeMtime(mtime: Long): Int {
        val seconds = if (mtime > 9_999_999_999L) mtime / 1000 else mtime
        return seconds.toInt()
    }

    public suspend fun stat(remotePath: String): FileStat = withContext(Dispatchers.IO) {
        val (stream, reader) = openSyncReader()
        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            val reqHeader = SyncCommand.createHeader(SyncCommand.ID_STAT, pathBytes.size)

            stream.write(reqHeader + pathBytes)

            val respHeaderBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
            val (id, mode) = SyncCommand.parseHeader(respHeaderBytes)

            check(id == SyncCommand.ID_STAT) { "Unexpected STAT response tag: $id" }

            val statBytes = reader.readExactBytes(8)
            val buf = ByteBuffer.wrap(statBytes).order(ByteOrder.LITTLE_ENDIAN)

            val size = buf.int.toLong() and 0xFFFFFFFFL
            val mtime = buf.int.toLong() and 0xFFFFFFFFL

            FileStat(remotePath, mode, size, mtime)
        } finally {
            stream.close()
        }
    }

    public suspend fun list(remotePath: String): List<DirectoryEntry> = withContext(Dispatchers.IO) {
        val (stream, reader) = openSyncReader()
        val entries = mutableListOf<DirectoryEntry>()

        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            stream.write(SyncCommand.createHeader(SyncCommand.ID_LIST, pathBytes.size) + pathBytes)

            while (true) {
                val headerBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                val (id, mode) = SyncCommand.parseHeader(headerBytes)

                when (id) {
                    SyncCommand.ID_DENT -> {
                        val dentBytes = reader.readExactBytes(12)
                        val buf = ByteBuffer.wrap(dentBytes).order(ByteOrder.LITTLE_ENDIAN)
                        
                        val size = buf.int.toLong() and 0xFFFFFFFFL
                        val mtime = buf.int.toLong() and 0xFFFFFFFFL
                        val nameLen = buf.int

                        val nameBytes = reader.readExactBytes(nameLen)
                        val name = String(nameBytes, Charsets.UTF_8)

                        if (name != "." && name != ".." && !name.contains("/") && !name.contains("\\")) {
                            entries.add(DirectoryEntry(name, mode, size, mtime))
                        }
                    }
                    SyncCommand.ID_DONE -> break
                    else -> throw IllegalStateException("Unexpected LIST response tag: $id")
                }
            }
        } finally {
            stream.close()
        }

        entries
    }

    /**
     * V1 Push (SEND)
     */
    public suspend fun push(
        channel: ByteReadChannel,
        remotePath: String,
        totalSize: Long = -1L,
        mode: Int = FilePermissions.DEFAULT_MODE,
        mtime: Long = System.currentTimeMillis() / 1000,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        val (stream, reader) = openSyncReader()
        try {
            val destinationStr = "$remotePath,$mode"
            val destBytes = destinationStr.toByteArray(Charsets.UTF_8)

            // 小文件合并发送优化
            if (totalSize in 0 until MAX_SYNC_DATA_SIZE) {
                val expectedSize = totalSize.toInt()
                val dataBuffer = ByteArray(expectedSize)
                
                var readTotal = 0
                while (readTotal < expectedSize) {
                    val read = channel.readAvailable(dataBuffer, readTotal, expectedSize - readTotal)
                    if (read < 0) break
                    readTotal += read
                }

                val totalPacketLen = SyncCommand.HEADER_SIZE + destBytes.size + 
                                     SyncCommand.HEADER_SIZE + readTotal + 
                                     SyncCommand.HEADER_SIZE
                
                val packetBuf = ByteBuffer.allocate(totalPacketLen).order(ByteOrder.LITTLE_ENDIAN)
                
                // 1. SEND Header + Dest
                packetBuf.put(SyncCommand.ID_SEND.toByteArray(Charsets.US_ASCII))
                packetBuf.putInt(destBytes.size)
                packetBuf.put(destBytes)

                // 2. DATA Header + Content
                packetBuf.put(SyncCommand.ID_DATA.toByteArray(Charsets.US_ASCII))
                packetBuf.putInt(readTotal)
                packetBuf.put(dataBuffer, 0, readTotal)

                // 3. DONE Header
                packetBuf.put(SyncCommand.ID_DONE.toByteArray(Charsets.US_ASCII))
                packetBuf.putInt(sanitizeMtime(mtime))

                stream.write(packetBuf.array())
                onProgress?.invoke(readTotal.toLong(), totalSize)
            } else {
                // 标准分块传输
                stream.write(SyncCommand.createHeader(SyncCommand.ID_SEND, destBytes.size) + destBytes)

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
            }

            val respHeaderBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
            val (id, len) = SyncCommand.parseHeader(respHeaderBytes)

            if (id == SyncCommand.ID_FAIL) {
                val errorMsg = String(reader.readExactBytes(len), Charsets.UTF_8)
                throw IllegalStateException("Push failed: $errorMsg")
            }

            check(id == SyncCommand.ID_OKAY) { "Unexpected push response tag: $id" }
        } finally {
            stream.close()
        }
    }

    /**
     * V1 Pull (RECV)
     */
    public suspend fun pull(
        remotePath: String,
        channel: ByteWriteChannel,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        val fileStat = stat(remotePath)
        check(fileStat.exists) { "Remote file does not exist: $remotePath" }

        val (stream, reader) = openSyncReader()
        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            stream.write(SyncCommand.createHeader(SyncCommand.ID_RECV, pathBytes.size) + pathBytes)

            var bytesRead = 0L

            while (true) {
                val headerBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                val (id, len) = SyncCommand.parseHeader(headerBytes)

                when (id) {
                    SyncCommand.ID_DATA -> {
                        reader.readToChannel(channel, len)
                        bytesRead += len
                        onProgress?.invoke(bytesRead, fileStat.size)
                    }
                    SyncCommand.ID_DONE -> break
                    SyncCommand.ID_FAIL -> {
                        val errorMsg = String(reader.readExactBytes(len), Charsets.UTF_8)
                        throw IllegalStateException("Pull failed: $errorMsg")
                    }
                    else -> throw IllegalStateException("Unexpected pull response tag: $id")
                }
            }
            channel.flush()
        } finally {
            stream.close()
        }
    }
}
