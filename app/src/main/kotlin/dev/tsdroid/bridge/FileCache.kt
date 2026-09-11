package dev.tsdroid.bridge

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap

class FileCache(private val context: Context) {

    companion object {
        private const val TAG = "FileCache"
        private const val BASE_FOLDER = "TS6 Droid"
    }

    private val contentResolver = context.contentResolver

    /**
     * Retourne le fichier depuis le cache local, ou null s'il n'existe pas.
     * Cherche dans Documents/TS6 Droid/{serverHost}/{relativePath}
     */
    fun get(serverHost: String, relativePath: String): ByteArray? {
        val uri = findFileUri(serverHost, relativePath) ?: return null
        return try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached file $relativePath", e)
            null
        }
    }

    /**
     * Enregistre les bytes dans Documents/TS6 Droid/{serverHost}/{relativePath}.
     * Crée le fichier via MediaStore. Écrase si existe déjà.
     */
    fun put(serverHost: String, relativePath: String, data: ByteArray) {
        try {
            // Delete existing file if any
            findFileUri(serverHost, relativePath)?.let {
                contentResolver.delete(it, null, null)
            }

            val fileName = relativePath.substringAfterLast('/')
            val subDir = relativePath.substringBeforeLast('/', "")
            val relPath = buildString {
                append("Documents/$BASE_FOLDER/")
                append(serverHost)
                if (subDir.isNotEmpty()) {
                    append("/")
                    append(subDir)
                }
            }

            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"

            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType)
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relPath)
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }

            val collection = MediaStore.Files.getContentUri("external")
            val uri = contentResolver.insert(collection, values) ?: run {
                Log.w(TAG, "Failed to insert file into MediaStore: $relativePath")
                return
            }

            contentResolver.openOutputStream(uri)?.use { it.write(data) }

            values.clear()
            values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache $relativePath", e)
        }
    }

    /** 流式写入目标：先开 MediaStore 条目，边收边写，最后 commit */
    class Sink(val uri: android.net.Uri, val output: java.io.OutputStream)

    private fun relPathFor(serverHost: String, relativePath: String): String {
        val subDir = relativePath.substringBeforeLast('/', "")
        return buildString {
            append("Documents/$BASE_FOLDER/")
            append(serverHost)
            if (subDir.isNotEmpty()) {
                append("/")
                append(subDir)
            }
        }
    }

    /**
     * 打开一个落盘目标（大文件分块下载用）：先删同名旧文件，再插 MediaStore 条目。
     * 调用方边收边写，最后必须 commitSink（失败则 abortSink）。
     */
    fun openSink(serverHost: String, relativePath: String): Sink? {
        return try {
            findFileUri(serverHost, relativePath)?.let { contentResolver.delete(it, null, null) }

            val fileName = relativePath.substringAfterLast('/')
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"

            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType)
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relPathFor(serverHost, relativePath))
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri("external")
            val uri = contentResolver.insert(collection, values) ?: run {
                Log.w(TAG, "openSink: insert failed for $relativePath")
                return null
            }
            val out = contentResolver.openOutputStream(uri) ?: run {
                contentResolver.delete(uri, null, null)
                Log.w(TAG, "openSink: openOutputStream failed for $relativePath")
                return null
            }
            Sink(uri, out)
        } catch (e: Exception) {
            Log.w(TAG, "openSink failed for $relativePath", e)
            null
        }
    }

    /** 打开一个"系统下载目录"目标（不经应用缓存，避免大文件占两份盘） */
    fun openDownloadsSink(fileName: String): Sink? {
        return try {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run {
                Log.w(TAG, "openDownloadsSink: insert failed for $fileName")
                return null
            }
            val out = contentResolver.openOutputStream(uri) ?: run {
                contentResolver.delete(uri, null, null)
                return null
            }
            Sink(uri, out)
        } catch (e: Exception) {
            Log.w(TAG, "openDownloadsSink failed for $fileName", e)
            null
        }
    }

    /** 写入完成：把 IS_PENDING 置 0，让别人能看见 */
    fun commitSink(uri: android.net.Uri) {
        try {
            val values = ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) }
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "commitSink failed", e)
        }
    }

    /** 写入失败：删掉半成品，别留个残缺文件 */
    fun abortSink(uri: android.net.Uri) {
        try {
            contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "abortSink failed", e)
        }
    }

    /**
     * Vérifie si le fichier existe dans le cache.
     */
    fun exists(serverHost: String, relativePath: String): Boolean {
        return findFileUri(serverHost, relativePath) != null
    }

    fun getUri(serverHost: String, relativePath: String): android.net.Uri? {
        return findFileUri(serverHost, relativePath)
    }

    private fun findFileUri(serverHost: String, relativePath: String): android.net.Uri? {
        val fileName = relativePath.substringAfterLast('/')
        val subDir = relativePath.substringBeforeLast('/', "")
        val relPath = buildString {
            append("Documents/$BASE_FOLDER/")
            append(serverHost)
            if (subDir.isNotEmpty()) {
                append("/")
                append(subDir)
            }
            append("/")
        }

        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, relPath)

        return try {
            contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    ContentUris.withAppendedId(collection, id)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query MediaStore for $relativePath", e)
            null
        }
    }
}
