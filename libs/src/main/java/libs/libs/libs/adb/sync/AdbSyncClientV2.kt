package libs.libs.libs.adb.sync

import libs.libs.libs.adb.connect.AdbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB Sync V2 协议客户端
 * 负责原生的 STA2, LST2, SND2, RCV2 指令传输
 */
public class AdbSyncClientV2(
    connection: AdbConnection
) : AdbSyncClient(connection) {

    public val supportsStatV2: Boolean get() = connection.hasFeature("stat_v2")
    public val supportsLsV2: Boolean get() = connection.hasFeature("ls_v2")
    public val supportsSendV2: Boolean get() = connection.hasFeature("send_v2") || connection.hasFeature("sendrecv_v2")
    public val supportsRecvV2: Boolean get() = connection.hasFeature("recv_v2") || connection.hasFeature("sendrecv_v2")

    /**
     * V2 Stat (STA2)
     */
    public suspend fun statV2(remotePath: String): FileStatV2 = withContext(Dispatchers.IO) {
        if (!supportsStatV2) {
            val v1 = stat(remotePath)
            return@withContext v1.toFileStatV2()
        }

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

            check(id == SyncCommandV2.ID_STA2 || id == SyncCommandV2.ID_LSTA) { "Unexpected STA2 response tag: $id" }

            val payload = reader.readExactBytes(68)
            ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).parseSyncStatV2(remotePath)
        } finally {
            stream.close()
        }
    }

    /**
     * V2 Directory List (LST2 / DNT2)
     */
    public suspend fun listV2(remotePath: String): List<FileStatV2> = withContext(Dispatchers.IO) {
        if (!supportsLsV2) {
            val v1Entries = list(remotePath)
            return@withContext v1Entries.map { dent ->
                FileStatV2(
                    path = if (remotePath.endsWith("/")) "$remotePath${dent.name}" else "$remotePath/${dent.name}",
                    error = 0, dev = 0, ino = 0, mode = dent.mode, nlink = 1,
                    uid = 0, gid = 0, size = dent.size, atime = dent.mtime, mtime = dent.mtime, ctime = dent.mtime
                )
            }
        }

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

            while (true) {
                val headerBytes = reader.readExactBytes(SyncCommand.HEADER_SIZE)
                val (id, nameLen) = SyncCommand.parseHeader(headerBytes)

                when (id) {
                    SyncCommandV2.ID_DNT2 -> {
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
                    SyncCommand.ID_DONE -> break
                    else -> throw IllegalStateException("Unexpected LST2 response tag: $id")
                }
            }
        } finally {
            stream.close()
        }

        entries
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
        require(flags == SyncFlags.FLAG_NONE) {
            "Compression flags ($flags) are not supported in raw stream implementation."
        }

        if (!supportsSendV2) {
            return@withContext push(inputStream, remotePath, totalSize, mode, mtime, onProgress)
        }

        val (stream, reader) = openSyncReader()
        try {
            val requestBytes = SyncCommandV2.createRequestV2(SyncCommandV2.ID_SND2, mode, flags, remotePath)
            stream.write(requestBytes)

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
                throw IllegalStateException("Push V2 (SND2) failed: $errorMsg")
            }

            check(id == SyncCommand.ID_OKAY) { "Unexpected SND2 response tag: $id" }
        } finally {
            stream.close()
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
        require(flags == SyncFlags.FLAG_NONE) {
            "Compression flags ($flags) are not supported in raw stream implementation."
        }

        if (!supportsRecvV2) {
            return@withContext pull(remotePath, outputStream, onProgress)
        }

        val (stream, reader) = openSyncReader()
        try {
            val fileStat = statV2(remotePath)
            check(fileStat.exists) { "Remote file does not exist: $remotePath" }

            val requestBytes = SyncCommandV2.createRequestV2(SyncCommandV2.ID_RCV2, 0, flags, remotePath)
            stream.write(requestBytes)

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
                        throw IllegalStateException("Pull V2 (RCV2) failed: $errorMsg")
                    }
                    else -> throw IllegalStateException("Unexpected pull V2 response tag: $id")
                }
            }
            outputStream.flush()
        } finally {
            stream.close()
        }
    }
}
