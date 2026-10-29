package com.adb.kitty.provider

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.webkit.MimeTypeMap
import com.adb.kitty.R
import java.io.File
import java.io.FileNotFoundException

class AppDataFilesProvider : DocumentsProvider() {

    companion object {
        const val COLUMN_MT_EXTRAS = "mt_extras"
        const val COLUMN_MT_PATH = "mt_path"

        const val METHOD_SET_LAST_MODIFIED = "mt:setLastModified"
        const val METHOD_SET_PERMISSIONS = "mt:setPermissions"
        const val METHOD_CREATE_SYMLINK = "mt:createSymlink"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_CAPACITY_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            COLUMN_MT_PATH,
            COLUMN_MT_EXTRAS
        )
    }

    private lateinit var packageNameStr: String
    private lateinit var authority: String
    private lateinit var dataDir: File
    private var userDeDataDir: File? = null
    private var androidDataDir: File? = null
    private var androidObbDir: File? = null
    private var androidMediaDir: File? = null

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        packageNameStr = context.packageName
        authority = info.authority

        val filesDir = context.filesDir
        dataDir = filesDir.parentFile ?: filesDir

        val dataDirPath = dataDir.path
        if (dataDirPath.startsWith("/data/user/")) {
            userDeDataDir = File("/data/user_de/${dataDirPath.substring(11)}")
        }

        androidDataDir = context.getExternalFilesDir(null)?.parentFile
        androidObbDir = context.obbDir

        @Suppress("DEPRECATION")
        androidMediaDir = context.externalMediaDirs.firstOrNull()
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val ctx = context ?: return MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val appInfo = ctx.applicationInfo
        val appName = ctx.packageManager.getApplicationLabel(appInfo).toString()

        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        val stat = runCatching { StatFs(dataDir.path) }.getOrNull()
        val availableBytes = stat?.availableBytes ?: dataDir.freeSpace
        val capacityBytes = stat?.totalBytes ?: dataDir.totalSpace

        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, packageNameStr)
            add(Root.COLUMN_DOCUMENT_ID, packageNameStr)
            add(Root.COLUMN_TITLE, appName)
            add(Root.COLUMN_SUMMARY, ctx.getString(R.string.documents_name))
            add(Root.COLUMN_ICON, appInfo.icon)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_AVAILABLE_BYTES, availableBytes)
            add(Root.COLUMN_CAPACITY_BYTES, capacityBytes)
        }

        val rootsUri = DocumentsContract.buildRootsUri(authority)
        result.setNotificationUri(ctx.contentResolver, rootsUri)
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, documentId, null)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cleanParentId = parentDocumentId.removeSuffix("/")
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(cleanParentId)

        if (parent == null) {
            includeFile(result, "$cleanParentId/data", dataDir)
            androidDataDir?.takeIf { it.exists() }?.let { includeFile(result, "$cleanParentId/android_data", it) }
            androidObbDir?.takeIf { it.exists() }?.let { includeFile(result, "$cleanParentId/android_obb", it) }
            androidMediaDir?.takeIf { it.exists() }?.let { includeFile(result, "$cleanParentId/android_media", it) }
            userDeDataDir?.takeIf { it.exists() }?.let { includeFile(result, "$cleanParentId/user_de_data", it) }
        } else {
            parent.listFiles()?.forEach { child ->
                includeFile(result, "$cleanParentId/${child.name}", child)
            }
        }

        context?.let { ctx ->
            val childrenUri = DocumentsContract.buildChildDocumentsUri(authority, cleanParentId)
            result.setNotificationUri(ctx.contentResolver, childrenUri)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = getFileForDocId(documentId, checkExists = false)
            ?: throw FileNotFoundException("$documentId not found")
        val fileMode = parseFileMode(mode)
        return ParcelFileDescriptor.open(file, fileMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = getFileForDocId(parentDocumentId)
            ?: throw FileNotFoundException("Parent $parentDocumentId not found")

        var newFile = File(parent, displayName)
        var noConflictId = 2
        while (newFile.exists()) {
            newFile = File(parent, "$displayName ($noConflictId)")
            noConflictId++
        }

        val succeeded = runCatching {
            if (Document.MIME_TYPE_DIR == mimeType) newFile.mkdir() else newFile.createNewFile()
        }.getOrDefault(false)

        if (succeeded) {
            notifyChildDocumentsChanged(parentDocumentId)
            val prefix = if (parentDocumentId.endsWith("/")) parentDocumentId else "$parentDocumentId/"
            return prefix + newFile.name
        }
        throw FileNotFoundException("Failed to create document in $parentDocumentId with name $displayName")
    }

    override fun deleteDocument(documentId: String) {
        val parentDocId = getParentDocId(documentId)
        val file = getFileForDocId(documentId)
        if (file == null || !recursiveDelete(file)) {
            throw FileNotFoundException("Failed to delete document $documentId")
        }
        parentDocId?.let { notifyChildDocumentsChanged(it) }
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) {
        deleteDocument(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = getFileForDocId(documentId)
            ?: throw FileNotFoundException("Document $documentId not found")
        val target = File(file.parentFile, displayName)
        if (file.renameTo(target)) {
            getParentDocId(documentId)?.let { notifyChildDocumentsChanged(it) }
            val lastSlash = documentId.lastIndexOf('/', documentId.length - 2)
            return documentId.substring(0, lastSlash + 1) + displayName
        }
        throw FileNotFoundException("Failed to rename document $documentId to $displayName")
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String
    ): String {
        val sourceFile = getFileForDocId(sourceDocumentId)
        val targetDir = getFileForDocId(targetParentDocumentId)
        if (sourceFile != null && targetDir != null) {
            val targetFile = File(targetDir, sourceFile.name)
            if (!targetFile.exists() && sourceFile.renameTo(targetFile)) {
                notifyChildDocumentsChanged(sourceParentDocumentId)
                notifyChildDocumentsChanged(targetParentDocumentId)
                val prefix = if (targetParentDocumentId.endsWith("/")) targetParentDocumentId else "$targetParentDocumentId/"
                return prefix + targetFile.name
            }
        }
        throw FileNotFoundException("Failed to move document $sourceDocumentId to $targetParentDocumentId")
    }

    override fun getDocumentType(documentId: String): String {
        val file = getFileForDocId(documentId)
        return file?.let { resolveMimeType(it) } ?: Document.MIME_TYPE_DIR
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return documentId.startsWith(parentDocumentId)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val result = super.call(method, arg, extras)
        if (result != null || !method.startsWith("mt:") || extras == null) {
            return result
        }

        val out = Bundle()
        runCatching {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable("uri", Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable("uri")
            } ?: return null

            val pathSegments = uri.pathSegments
            val documentId = if (pathSegments.size >= 4) pathSegments[3] else pathSegments[1]

            when (method) {
                METHOD_SET_LAST_MODIFIED -> {
                    val file = getFileForDocId(documentId)
                    val time = extras.getLong("time")
                    out.putBoolean("result", file?.setLastModified(time) ?: false)
                }
                METHOD_SET_PERMISSIONS -> {
                    val file = getFileForDocId(documentId)
                    if (file == null) {
                        out.putBoolean("result", false)
                    } else {
                        val permissions = extras.getInt("permissions")
                        Os.chmod(file.path, permissions)
                        out.putBoolean("result", true)
                    }
                }
                METHOD_CREATE_SYMLINK -> {
                    val file = getFileForDocId(documentId, checkExists = false)
                    val path = extras.getString("path")
                    if (file != null && path != null) {
                        Os.symlink(path, file.path)
                        out.putBoolean("result", true)
                    } else {
                        out.putBoolean("result", false)
                    }
                }
                else -> {
                    out.putBoolean("result", false)
                    out.putString("message", "Unsupported method: $method")
                }
            }
        }.onFailure { e ->
            out.putBoolean("result", false)
            out.putString("message", e.message ?: e.toString())
        }
        return out
    }

    fun notifyRootsChanged() {
        val ctx = context ?: return
        val rootsUri = DocumentsContract.buildRootsUri(authority)
        ctx.contentResolver.notifyChange(rootsUri, null)
    }

    private fun notifyChildDocumentsChanged(parentDocumentId: String) {
        val ctx = context ?: return
        val childrenUri = DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)
        ctx.contentResolver.notifyChange(childrenUri, null)
    }

    private fun getParentDocId(docId: String): String? {
        val lastSlash = docId.lastIndexOf('/')
        return if (lastSlash != -1) docId.substring(0, lastSlash) else null
    }

    private fun getFileForDocId(docId: String, checkExists: Boolean = true): File? {
        var filename = if (docId.startsWith(packageNameStr)) {
            docId.substring(packageNameStr.length)
        } else {
            throw FileNotFoundException("$docId not found")
        }

        if (filename.startsWith("/")) {
            filename = filename.substring(1)
        }
        if (filename.isEmpty()) return null

        val slashIndex = filename.indexOf('/')
        val type = if (slashIndex == -1) filename else filename.substring(0, slashIndex)
        val subPath = if (slashIndex == -1) "" else filename.substring(slashIndex + 1)

        val targetFile = when {
            type.equalsIgnoreCase("data") -> File(dataDir, subPath)
            type.equalsIgnoreCase("android_data") -> androidDataDir?.let { File(it, subPath) }
            type.equalsIgnoreCase("android_obb") -> androidObbDir?.let { File(it, subPath) }
            type.equalsIgnoreCase("android_media") -> androidMediaDir?.let { File(it, subPath) }
            type.equalsIgnoreCase("user_de_data") -> userDeDataDir?.let { File(it, subPath) }
            else -> null
        } ?: throw FileNotFoundException("$docId not found")

        if (checkExists) {
            runCatching { Os.lstat(targetFile.path) }
                .onFailure { throw FileNotFoundException("$docId not found") }
        }
        return targetFile
    }

    private fun includeFile(result: MatrixCursor, docId: String, file: File?) {
        val target = file ?: getFileForDocId(docId)
        if (target == null) {
            result.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, packageNameStr)
                add(Document.COLUMN_DISPLAY_NAME, packageNameStr)
                add(Document.COLUMN_SIZE, 0L)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_LAST_MODIFIED, 0L)
                add(Document.COLUMN_FLAGS, 0)
            }
            return
        }

        var flags = 0
        if (target.isDirectory) {
            if (target.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (target.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }

        if (target.parentFile?.canWrite() == true) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }

        val path = target.path
        var addExtras = false
        val displayName = when {
            path == dataDir.path -> "data"
            androidDataDir != null && path == androidDataDir?.path -> "android_data"
            androidObbDir != null && path == androidObbDir?.path -> "android_obb"
            androidMediaDir != null && path == androidMediaDir?.path -> "android_media"
            userDeDataDir != null && path == userDeDataDir?.path -> "user_de_data"
            else -> {
                addExtras = true
                target.name
            }
        }

        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, displayName)
            add(Document.COLUMN_SIZE, target.length())
            add(Document.COLUMN_MIME_TYPE, resolveMimeType(target))
            add(Document.COLUMN_LAST_MODIFIED, target.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(COLUMN_MT_PATH, target.absolutePath)

            if (addExtras) {
                runCatching {
                    val stat: StructStat = Os.lstat(path)
                    val sb = StringBuilder("${stat.st_mode}|${stat.st_uid}|${stat.st_gid}")
                    if ((stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK) {
                        sb.append("|").append(Os.readlink(path))
                    }
                    add(COLUMN_MT_EXTRAS, sb.toString())
                }.onFailure { e ->
                    
                }
            }
        }
    }

    private fun resolveMimeType(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val extension = file.extension.lowercase()
        return if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }
    }

    private fun recursiveDelete(file: File): Boolean {
        if (file.isDirectory && !isSymbolicLink(file)) {
            file.listFiles()?.forEach { child ->
                if (!recursiveDelete(child)) return false
            }
        }
        return file.delete()
    }

    private fun isSymbolicLink(file: File): Boolean {
        return runCatching {
            val stat = Os.lstat(file.path)
            (stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK
        }.getOrDefault(false)
    }

    private fun String.equalsIgnoreCase(other: String): Boolean = this.equals(other, ignoreCase = true)

    private fun parseFileMode(mode: String): Int = when (mode) {
        "r" -> ParcelFileDescriptor.MODE_READ_ONLY
        "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
        "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        else -> throw IllegalArgumentException("Invalid mode: $mode")
    }
}
