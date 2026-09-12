package dev.tsdroid.data

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.asImageBitmap
import dev.tsdroid.bridge.FileCache
import dev.tsdroid.bridge.TsClient
import dev.tsdroid.han.R
import dev.tsdroid.model.DownloadState
import dev.tsdroid.model.FileAttachment
import dev.tsdroid.model.TsFileEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 频道文件传输 + 文件管理器（批次4 从 ServerViewModel 抽出）。
 *
 * 只管"文件"这一件事：列目录、上传、下载、图片预览、附件落盘。
 * **不碰聊天消息** —— "我发了个文件"那条消息插进聊天流是 ViewModel 的活
 * （见 ServerViewModel.appendFileMessage），所以依赖是单向的：ViewModel → 这里。
 *
 * 连接和当前频道都会变，因此 client / channelId / serverAddress 一律用 lambda 现取、
 * 不缓存 —— 免得重连之后手里还攥着上一个 TsClient。
 */
class FileTransferController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val client: () -> TsClient?,
    private val channelId: () -> Long,
    private val serverAddress: () -> String?,
) {
    private companion object {
        const val TAG = "FileTransfer"
    }

    private val fileCache = FileCache(appContext)

    /** 附件下载结果的内存缓存：key = "host/path"。只有 Done 会被复用。 */
    private val downloadCache = mutableMapOf<String, StateFlow<DownloadState>>()

    private val _fileManagerOpen = MutableStateFlow(false)
    val fileManagerOpen: StateFlow<Boolean> = _fileManagerOpen.asStateFlow()

    private val _fileList = MutableStateFlow<List<TsFileEntry>>(emptyList())
    val fileList: StateFlow<List<TsFileEntry>> = _fileList.asStateFlow()

    private val _currentFilePath = MutableStateFlow("/")
    val currentFilePath: StateFlow<String> = _currentFilePath.asStateFlow()

    private val _fileManagerLoading = MutableStateFlow(false)
    val fileManagerLoading: StateFlow<Boolean> = _fileManagerLoading.asStateFlow()

    private val _previewImageBytes = MutableStateFlow<ByteArray?>(null)
    val previewImageBytes: StateFlow<ByteArray?> = _previewImageBytes.asStateFlow()

    private val _previewImageName = MutableStateFlow<String?>(null)
    val previewImageName: StateFlow<String?> = _previewImageName.asStateFlow()

    // ------------------------------ 文件管理器 ------------------------------

    fun toggleFileManager() {
        if (_fileManagerOpen.value) {
            closeFileManager()
        } else {
            _fileManagerOpen.value = true
            refreshFileList()
        }
    }

    fun closeFileManager() {
        _fileManagerOpen.value = false
        _currentFilePath.value = "/"
        _fileList.value = emptyList()
    }

    fun refreshFileList() {
        val c = client() ?: run { Log.w(TAG, "refreshFileList: tsClient is null"); return }
        val ch = channelId()
        if (ch == 0L) { Log.w(TAG, "refreshFileList: channelId is 0"); return }
        Log.d(TAG, "refreshFileList: channelId=$ch path=${_currentFilePath.value}")
        _fileManagerLoading.value = true
        scope.launch {
            val files = c.listFiles(ch, _currentFilePath.value)
            Log.d(TAG, "refreshFileList: got ${files?.size ?: "null"} files")
            _fileList.value = files ?: emptyList()
            _fileManagerLoading.value = false
        }
    }

    fun navigateToFolder(folderName: String) {
        val current = _currentFilePath.value
        _currentFilePath.value = if (current.endsWith("/")) "$current$folderName/" else "$current/$folderName/"
        refreshFileList()
    }

    fun navigateUp() {
        val current = _currentFilePath.value.trimEnd('/')
        if (current == "" || current == "/") return
        val parent = current.substringBeforeLast('/', "/")
        _currentFilePath.value = if (parent.endsWith("/")) parent else "$parent/"
        refreshFileList()
    }

    fun deleteFile(name: String) {
        val c = client() ?: return
        val ch = channelId()
        if (ch == 0L) return
        c.deleteFile(ch, _currentFilePath.value + name)
        scope.launch { delay(500); refreshFileList() }
    }

    fun renameFile(oldName: String, newName: String) {
        val c = client() ?: return
        val ch = channelId()
        if (ch == 0L) return
        val path = _currentFilePath.value
        c.renameFile(ch, path + oldName, path + newName)
        scope.launch { delay(500); refreshFileList() }
    }

    fun createDirectory(dirName: String) {
        val c = client() ?: run { Log.w(TAG, "createDirectory: tsClient is null"); return }
        val ch = channelId()
        if (ch == 0L) { Log.w(TAG, "createDirectory: channelId is 0"); return }
        val fullPath = _currentFilePath.value + dirName
        Log.d(TAG, "createDirectory: channelId=$ch path=$fullPath")
        try {
            c.createDirectory(ch, fullPath)
        } catch (e: Exception) {
            Log.e(TAG, "createDirectory failed", e)
        }
        scope.launch { delay(500); refreshFileList() }
    }

    /** 上传到文件管理器当前目录（不产生聊天消息） */
    fun uploadToCurrentDirectory(fileName: String, data: ByteArray) {
        val c = client() ?: return
        val ch = channelId()
        if (ch == 0L) return
        val path = _currentFilePath.value + fileName
        scope.launch {
            if (c.uploadFile(ch, path, data, overwrite = true)) {
                delay(500)
                refreshFileList()
            }
        }
    }

    /** 从文件管理器下载：边收边写进系统下载目录（大文件不再先缓存再复制，省一半磁盘） */
    fun downloadFromManager(fileName: String) {
        val c = client() ?: return
        val ch = channelId()
        if (ch == 0L) return
        val fullName = _currentFilePath.value.trimStart('/') + fileName
        scope.launch(Dispatchers.IO) {
            val sink = fileCache.openDownloadsSink(fileName) ?: return@launch
            var written = 0L
            val total = try {
                c.downloadFileStreaming(ch, "/$fullName") { chunk ->
                    sink.output.write(chunk)
                    written += chunk.size
                }
            } finally {
                try { sink.output.close() } catch (_: Exception) {}
            }
            if (total == null) {
                fileCache.abortSink(sink.uri)
                Log.w(TAG, "文件管理器下载失败: $fullName (已写 $written 字节)")
                return@launch
            }
            fileCache.commitSink(sink.uri)
            Log.i(TAG, "文件管理器下载完成: $fullName ($written 字节)")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.file_saved, fileName),
                    Toast.LENGTH_SHORT,
                ).show()
                FileIo.openUri(appContext, sink.uri, fileName)
            }
        }
    }

    // --------------------------- 聊天附件 / 图片预览 ---------------------------

    fun downloadAttachment(attachment: FileAttachment): StateFlow<DownloadState> {
        val host = serverAddress()?.substringBefore(':') ?: "unknown"
        val cachePath = attachment.fileName.trimStart('/')
        val cacheKey = "$host/$cachePath"

        // 已经拿到的结果直接复用
        downloadCache[cacheKey]?.let { existing ->
            if (existing.value is DownloadState.Done) return existing
        }

        val state = MutableStateFlow<DownloadState>(DownloadState.Downloading)
        val c = client() ?: run {
            state.value = DownloadState.Error("Pas connecté")
            return state
        }
        val ch = if (attachment.channelId != 0L) attachment.channelId else channelId()

        downloadCache[cacheKey] = state

        scope.launch(Dispatchers.IO) {
            // 分块流式下载（边收边落盘），大文件也不进内存
            val got = FileIo.streamToCache(fileCache, c, ch, "/${attachment.fileName}", host, cachePath)
            if (got == null) {
                downloadCache.remove(cacheKey)
                state.value = DownloadState.Error("Échec du téléchargement")
                return@launch
            }
            val (localUri, _) = got
            if (attachment.isImage) {
                val bmp = FileIo.decodeSampledFromUri(appContext, localUri, 1280)
                state.value = DownloadState.Done(bmp?.asImageBitmap(), localUri)
            } else {
                val dlUri = FileIo.copyToDownloads(appContext, attachment.fileName.substringAfterLast('/'), localUri)
                state.value = DownloadState.Done(null, dlUri)
            }
        }
        return state
    }

    fun previewImage(fileName: String) {
        val c = client() ?: return
        val ch = channelId()
        if (ch == 0L) return
        val fullName = _currentFilePath.value.trimStart('/') + fileName
        val host = serverAddress()?.substringBefore(':') ?: "unknown"
        val cachePath = fullName.trimStart('/')

        scope.launch(Dispatchers.IO) {
            val got = FileIo.streamToCache(fileCache, c, ch, "/$fullName", host, cachePath) ?: return@launch
            val bytes = appContext.contentResolver.openInputStream(got.first)?.use { it.readBytes() }
            if (bytes != null) {
                _previewImageBytes.value = bytes
                _previewImageName.value = fileName
            }
        }
    }

    fun closePreview() {
        _previewImageBytes.value = null
        _previewImageName.value = null
    }

    // ------------------------ 给 ViewModel 组装聊天消息用 ------------------------

    /** 上传一个文件到指定频道路径。@return 是否成功 */
    suspend fun upload(channelId: Long, path: String, data: ByteArray): Boolean =
        client()?.uploadFile(channelId, path, data, overwrite = true) ?: false

    /**
     * 构造 TeamSpeak 客户端认识的 ts3file:// 链接（聊天里发出去的就是这个字符串）。
     * path 默认根目录：上传的文件落在根目录；分享文件管理器里的文件时传当前目录。
     */
    fun buildTs3FileUrl(channelId: Long, fileName: String, fileSize: Long, path: String = "/"): String {
        val addr = serverAddress() ?: "localhost"
        val host = addr.substringBefore(':')
        val port = addr.substringAfter(':', "9987")
        val fileDateTime = System.currentTimeMillis() / 1000
        return "ts3file://${host}?port=${port}&channel=${channelId}" +
            "&path=${path}&filename=${fileName}&isDir=0&size=${fileSize}&fileDateTime=${fileDateTime}"
    }
}
