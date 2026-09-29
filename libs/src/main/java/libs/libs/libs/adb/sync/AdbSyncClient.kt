package libs.libs.libs.adb.sync

import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.connect.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB Sync V1 协议客户端
 * 纯粹处理协议 V1 指令: STAT, LIST (DENT), SEND, RECV
 */
public open class AdbSyncClient(
    @PublishedApi internal val connection: AdbConnection
) {

    protected suspend fun openSyncStream(): AdbStream {
        return connection.openStream("sync:")
            ?: throw IllegalStateException("Failed to open ADB sync: service")
    }

    protected suspend fun openSyncReader(): Pair<AdbStream, SyncStreamReader> {
        val stream = openSyncStream()
        return stream to SyncStreamReader(stream)
    }

    protected fun sanitizeMtime(mtime: Long): Int {
        // 如果误传了毫秒时间戳 (大于 10 位数)，自动转换为秒级
        val seconds = if (mtime > 9_999_999_999L) mtime / 1000 else mtime
        return seconds.toInt()
    }

    /**
     * V1 Stat (STAT)
     */
    public suspend fun stat(remotePath: String): FileStat = withContext(Dispatchers.IO) {
        val (stream, reader) = openSyncReader()
        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            val reqHeader = SyncCommand.createHeader(SyncCommand.ID_STAT, pathBytes.size)

            stream.write(reqHeader + pathBytes)

            val respHeaderBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
            val (id, _) = SyncCommand.parseHeader(respHeaderBytes)

            check(id == SyncCommand.ID_STAT) { "Unexpected STAT response tag: $id" }

            val statBytes = reader.readExactBytes(12)
            val buf = ByteBuffer.wrap(statBytes).order(ByteOrder.LITTLE_ENDIAN)

            val mode = buf.int
            val size = buf.int.toLong() and 0xFFFFFFFFL
            val mtime = buf.int.toLong() and 0xFFFFFFFFL

            FileStat(remotePath, mode, size, mtime)
        } finally {
            stream.close()
        }
    }

    /**
     * V1 Directory List (LIST / DENT)
     */
    public suspend fun list(remotePath: String): List<DirectoryEntry> = withContext(Dispatchers.IO) {
        val (stream, reader) = openSyncReader()
        val entries = mutableListOf<DirectoryEntry>()

        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            stream.write(SyncCommand.createHeader(SyncCommand.ID_LIST, pathBytes.size) + pathBytes)

            while (true) {
                val headerBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                val (id, _) = SyncCommand.parseHeader(headerBytes)

                when (id) {
                    SyncCommand.ID_DENT -> {
                        val dentBytes = reader.readExactBytes(16)
                        val buf = ByteBuffer.wrap(dentBytes).order(ByteOrder.LITTLE_ENDIAN)
                        val mode = buf.int
                        val size = buf.int.toLong() and 0xFFFFFFFFL
                        val mtime = buf.int.toLong() and 0xFFFFFFFFL
                        val nameLen = buf.int

                        val nameBytes = reader.readExactBytes(nameLen)
                        val name = String(nameBytes, Charsets.UTF_8)

                        if (name != "." && name != "..") {
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
        inputStream: InputStream,
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
            stream.write(SyncCommand.createHeader(SyncCommand.ID_SEND, destBytes.size) + destBytes)

            val buffer = ByteArray(64 * 1024)
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
        outputStream: OutputStream,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        val (stream, reader) = openSyncReader()
        try {
            val fileStat = stat(remotePath)
            check(fileStat.exists) { "Remote file does not exist: $remotePath" }

            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            stream.write(SyncCommand.createHeader(SyncCommand.ID_RECV, pathBytes.size) + pathBytes)

            var bytesRead = 0L

            while (true) {
                val headerBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                val (id, len) = SyncCommand.parseHeader(headerBytes)

                when (id) {
                    SyncCommand.ID_DATA -> {
                        val chunk = reader.readExactBytes(len)
                        outputStream.write(chunk)
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
            outputStream.flush()
        } finally {
            stream.close()
        }
    }
}
