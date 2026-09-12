package dev.tsdroid.model

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 附件下载 / 预览的 UI 状态。
 *
 * 已知欠债：Done 里直接揣着 ImageBitmap 与 Uri，使"模型"带上了 Android/Compose 依赖。
 * 本次**原样搬迁**（零行为改动）；后续批次再收敛成"模型只存资源标识、Bitmap 由 UI 层持有"。
 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data object Downloading : DownloadState()
    data class Done(val image: ImageBitmap?, val fileUri: Uri? = null) : DownloadState()
    data class Error(val message: String) : DownloadState()
}
