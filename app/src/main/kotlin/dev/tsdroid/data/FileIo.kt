package dev.tsdroid.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import dev.tsdroid.bridge.FileCache
import dev.tsdroid.bridge.TsClient
import dev.tsdroid.han.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件 IO 工具（无状态）：把字节流搬到磁盘或系统下载目录、抽样解码图片、交给外部应用打开。
 * 从 ServerViewModel 抽出（批次4）。不持有任何 UI 状态，不知道"当前频道"是什么。
 */
internal object FileIo {
    private const val TAG = "FileIo"

    /**
     * 流式下载到应用缓存目录：边收边写盘，不把整份文件读进内存，大文件（几十~几百 MB）不会 OOM。
     * 失败时删掉半成品。
     * @return 缓存 Uri 与写入字节数（命中已有缓存时为 -1）；失败返回 null
     */
    suspend fun streamToCache(
        fileCache: FileCache,
        client: TsClient,
        channelId: Long,
        remotePath: String,
        host: String,
        cachePath: String,
    ): Pair<Uri, Long>? {
        // 已有缓存直接用
        fileCache.getUri(host, cachePath)?.let { return it to -1L }

        val sink = fileCache.openSink(host, cachePath) ?: return null
        var written = 0L
        val total = try {
            client.downloadFileStreaming(channelId, remotePath) { chunk ->
                sink.output.write(chunk)
                written += chunk.size
            }
        } finally {
            try { sink.output.close() } catch (_: Exception) {}
        }
        if (total == null) {
            fileCache.abortSink(sink.uri)
            Log.w(TAG, "streamToCache failed: $remotePath (已写 $written 字节)")
            return null
        }
        fileCache.commitSink(sink.uri)
        Log.i(TAG, "streamToCache ok: $remotePath ($written 字节)")
        return sink.uri to written
    }

    /** 把已落盘的缓存文件复制进系统下载目录（流式复制，不占内存） */
    suspend fun copyToDownloads(context: Context, fileName: String, src: Uri): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val dst = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return@withContext null
                context.contentResolver.openInputStream(src)?.use { input ->
                    context.contentResolver.openOutputStream(dst)?.use { output ->
                        input.copyTo(output, bufferSize = 256 * 1024)
                    }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(dst, values, null, null)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.file_saved, fileName), Toast.LENGTH_SHORT).show()
                }
                dst
            } catch (e: Exception) {
                Log.w(TAG, "copyToDownloads failed for $fileName", e)
                null
            }
        }

    /** 从已落盘的缓存 Uri 里抽样解码图片（不需要整份字节进内存） */
    fun decodeSampledFromUri(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val resolver = context.contentResolver
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val width = bounds.outWidth
            val height = bounds.outHeight
            var sampleSize = 1
            if (width > maxDimension || height > maxDimension) {
                var half = maxOf(width, height) / 2
                while (half / sampleSize >= maxDimension) sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            Log.w(TAG, "decodeSampledFromUri failed", e)
            null
        }
    }

    /** 交给外部应用打开（ACTION_VIEW）；没有能处理的 App 就静默算了 */
    fun openUri(context: Context, uri: Uri, fileName: String) {
        try {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
