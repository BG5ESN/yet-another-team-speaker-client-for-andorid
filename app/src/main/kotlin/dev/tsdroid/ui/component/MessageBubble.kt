package dev.tsdroid.ui.component

import android.content.Intent
import android.graphics.Typeface
import android.text.Html
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.tsdroid.model.DownloadState
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import android.webkit.MimeTypeMap
import androidx.compose.ui.unit.dp
import dev.tsdroid.han.R
import coil.compose.AsyncImage
import dev.tslib.BBCode
import dev.tsdroid.model.ChatMessage
import dev.tsdroid.model.FileAttachment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Match BBCode [img]URL[/img]
private val IMG_BBCODE = Regex("""\[img](.*?)\[/img]""", RegexOption.IGNORE_CASE)
// Match HTML <img src="URL"> or <img src='URL'>
private val IMG_HTML = Regex("""<img\s+[^>]*src=["']([^"']+)["'][^>]*/?>""", RegexOption.IGNORE_CASE)
// Match standalone image URLs (common extensions)
private val IMG_URL = Regex("""(?<!\S)(https?://\S+\.(?:png|jpe?g|gif|webp|bmp|svg))(?!\S)""", RegexOption.IGNORE_CASE)
// Match bare URLs (not wrapped in BBCode [url])
private val URL_REGEX = Regex("""https?://\S+""")

// YouTube video ID extraction patterns
private val YOUTUBE_PATTERNS = listOf(
    Regex("""(?:https?://)?(?:www\.)?youtube\.com/watch\?.*?v=([a-zA-Z0-9_-]{11})"""),
    Regex("""(?:https?://)?youtu\.be/([a-zA-Z0-9_-]{11})"""),
    Regex("""(?:https?://)?(?:www\.)?youtube\.com/shorts/([a-zA-Z0-9_-]{11})"""),
    Regex("""(?:https?://)?(?:www\.)?youtube\.com/embed/([a-zA-Z0-9_-]{11})"""),
    Regex("""(?:https?://)?(?:www\.)?youtube\.com/live/([a-zA-Z0-9_-]{11})"""),
)
// Dailymotion video ID extraction patterns
private val DAILYMOTION_PATTERNS = listOf(
    Regex("""(?:https?://)?(?:www\.)?dailymotion\.com/video/([a-zA-Z0-9]+)"""),
    Regex("""(?:https?://)?dai\.ly/([a-zA-Z0-9]+)"""),
)

private fun extractThumbnailUrl(url: String): String? {
    for (pattern in YOUTUBE_PATTERNS) {
        pattern.find(url)?.groupValues?.get(1)?.let { id ->
            return "https://img.youtube.com/vi/$id/hqdefault.jpg"
        }
    }
    for (pattern in DAILYMOTION_PATTERNS) {
        pattern.find(url)?.groupValues?.get(1)?.let { id ->
            return "https://www.dailymotion.com/thumbnail/video/$id"
        }
    }
    return null
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    showLinkThumbnails: Boolean = false,
    autoLoadImages: Boolean = true,
    onDownload: ((FileAttachment) -> StateFlow<DownloadState>)? = null,
    modifier: Modifier = Modifier,
) {
    val isMe = message.isMe
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val parsed = remember(message.text, linkColor, codeBackground, showLinkThumbnails) {
        parseMessage(message.text, linkColor, codeBackground, showLinkThumbnails)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (isMe) 48.dp else 0.dp,
                end = if (isMe) 0.dp else 48.dp,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isMe)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
        ) {
            if (!isMe) {
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (message.fileAttachment != null) {
                FileAttachmentCard(message.fileAttachment, onDownload, autoLoadImages)
            } else {
                // Text content
                if (parsed.text.isNotBlank()) {
                    Text(
                        text = parsed.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Video thumbnails
                for (thumbUrl in parsed.thumbnailUrls) {
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                }

                // Images
                for (imageUrl in parsed.imageUrls) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = stringResource(R.string.image),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }

            Text(
                text = timeFormat.format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class ParsedMessage(
    val text: AnnotatedString,
    val imageUrls: List<String>,
    val thumbnailUrls: List<String> = emptyList(),
)

@Composable
private fun FileAttachmentCard(
    attachment: FileAttachment,
    onDownload: ((FileAttachment) -> StateFlow<DownloadState>)? = null,
    autoLoadImages: Boolean = true,
) {
    val context = LocalContext.current
    var downloadStateFlow by remember { mutableStateOf<StateFlow<DownloadState>?>(null) }
    val downloadState = downloadStateFlow?.collectAsStateWithLifecycle()?.value ?: DownloadState.Idle

    // Auto-load images (served from disk cache if available)
    LaunchedEffect(attachment, autoLoadImages) {
        if (autoLoadImages && attachment.isImage && onDownload != null && downloadStateFlow == null) {
            downloadStateFlow = onDownload(attachment)
        }
    }

    // Auto-open non-image files after download completes
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Done && !attachment.isImage && downloadState.fileUri != null) {
            openFile(context, downloadState.fileUri, attachment.fileName)
        }
    }

    // If download completed with an image, show it inline
    if (downloadState is DownloadState.Done && downloadState.image != null) {
        val uri = downloadState.fileUri
        androidx.compose.foundation.Image(
            bitmap = downloadState.image,
            contentDescription = attachment.fileName,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (uri != null) Modifier.clickable { openFile(context, uri, attachment.fileName) }
                    else Modifier
                ),
            contentScale = ContentScale.FillWidth,
        )
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .then(
                when {
                    onDownload != null && downloadState is DownloadState.Idle ->
                        Modifier.clickable { downloadStateFlow = onDownload(attachment) }
                    downloadState is DownloadState.Done && downloadState.fileUri != null -> {
                        val uri = downloadState.fileUri
                        Modifier.clickable { openFile(context, uri, attachment.fileName) }
                    }
                    else -> Modifier
                }
            ),
    ) {
        when (downloadState) {
            is DownloadState.Downloading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                )
            }
            else -> {
                Icon(
                    imageVector = if (attachment.isImage) Icons.Default.Image else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (attachment.channelId > 0 && attachment.fileId.isEmpty())
                    stringResource(R.string.file_attachment_server, formatFileSize(context, attachment.fileSize))
                else
                    stringResource(R.string.file_attachment_info, formatFileSize(context, attachment.fileSize)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (downloadState) {
                is DownloadState.Idle -> {
                    if (onDownload != null) {
                        Text(
                            text = stringResource(R.string.tap_to_view),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                is DownloadState.Downloading -> {
                    Text(
                        text = stringResource(R.string.downloading),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is DownloadState.Done -> {
                    Text(
                        text = stringResource(R.string.tap_to_open),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is DownloadState.Error -> {
                    Text(
                        text = downloadState.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (onDownload != null && downloadState is DownloadState.Idle) {
            Icon(
                imageVector = if (attachment.isImage) Icons.Default.Image else Icons.Default.Download,
                contentDescription = stringResource(R.string.download),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun openFile(context: android.content.Context, uri: android.net.Uri, fileName: String) {
    try {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

private fun formatFileSize(context: android.content.Context, bytes: Long): String {
    if (bytes < 1024) return context.getString(R.string.file_size_bytes, bytes)
    val kb = bytes / 1024.0
    if (kb < 1024) return context.getString(R.string.file_size_kb, kb)
    val mb = kb / 1024.0
    if (mb < 1024) return context.getString(R.string.file_size_mb, mb)
    val gb = mb / 1024.0
    return context.getString(R.string.file_size_gb, gb)
}

private fun parseMessage(rawText: String, linkColor: Color, codeBackground: Color, showThumbnails: Boolean = false): ParsedMessage {
    val imageUrls = mutableListOf<String>()

    // Extract image URLs from all formats
    IMG_BBCODE.findAll(rawText).forEach { imageUrls.add(it.groupValues[1]) }
    IMG_HTML.findAll(rawText).forEach { imageUrls.add(it.groupValues[1]) }

    // Remove [img] and <img> tags from text
    var cleanText = IMG_BBCODE.replace(rawText, "")
    cleanText = IMG_HTML.replace(cleanText, "")

    // Extract standalone image URLs
    IMG_URL.findAll(cleanText).forEach { imageUrls.add(it.groupValues[1]) }
    cleanText = IMG_URL.replace(cleanText, "")

    // Convert remaining BBCode to annotated text with clickable links
    val displayText = try {
        // Replace <code>/<pre> with <tt> (supported by Html.fromHtml → TypefaceSpan monospace)
        val html = BBCode.toHtml(cleanText)
            .replace("<pre><code>", "<tt>")
            .replace("</code></pre>", "</tt>")
            .replace("<code>", "<tt>")
            .replace("</code>", "</tt>")
        val spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        val fullText = spanned.toString()
        val trimmedStart = fullText.length - fullText.trimStart().length
        val plainText = fullText.trim()

        val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)

        // Collect link ranges from URLSpans (BBCode [url] tags)
        data class LinkRange(val start: Int, val end: Int, val url: String)
        val linkRanges = mutableListOf<LinkRange>()
        for (span in spanned.getSpans(0, spanned.length, URLSpan::class.java)) {
            val start = spanned.getSpanStart(span) - trimmedStart
            val end = spanned.getSpanEnd(span) - trimmedStart
            if (start >= 0 && end <= plainText.length && start < end) {
                linkRanges.add(LinkRange(start, end, span.url))
            }
        }

        // Detect bare URLs not already covered by a URLSpan
        URL_REGEX.findAll(plainText).forEach { match ->
            val alreadyCovered = linkRanges.any { lr ->
                match.range.first >= lr.start && match.range.last < lr.end
            }
            if (!alreadyCovered) {
                linkRanges.add(LinkRange(match.range.first, match.range.last + 1, match.value))
            }
        }

        linkRanges.sortBy { it.start }

        buildAnnotatedString {
            append(plainText)

            // Bold and italic from StyleSpan
            for (span in spanned.getSpans(0, spanned.length, StyleSpan::class.java)) {
                val start = spanned.getSpanStart(span) - trimmedStart
                val end = spanned.getSpanEnd(span) - trimmedStart
                if (start >= 0 && end <= plainText.length && start < end) {
                    when (span.style) {
                        Typeface.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                        Typeface.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                        Typeface.BOLD_ITALIC -> addStyle(
                            SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), start, end
                        )
                    }
                }
            }

            // Strikethrough
            for (span in spanned.getSpans(0, spanned.length, StrikethroughSpan::class.java)) {
                val start = spanned.getSpanStart(span) - trimmedStart
                val end = spanned.getSpanEnd(span) - trimmedStart
                if (start >= 0 && end <= plainText.length && start < end) {
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                }
            }

            // Monospace code (from <tt> → TypefaceSpan)
            for (span in spanned.getSpans(0, spanned.length, TypefaceSpan::class.java)) {
                if (span.family == "monospace") {
                    val start = spanned.getSpanStart(span) - trimmedStart
                    val end = spanned.getSpanEnd(span) - trimmedStart
                    if (start >= 0 && end <= plainText.length && start < end) {
                        addStyle(
                            SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
                            start, end
                        )
                    }
                }
            }

            // Links
            for (lr in linkRanges) {
                addStyle(linkStyle, lr.start, lr.end)
                addLink(LinkAnnotation.Url(lr.url), lr.start, lr.end)
            }
        }
    } catch (_: Exception) {
        AnnotatedString(BBCode.strip(cleanText))
    }

    val thumbnailUrls = if (showThumbnails) {
        URL_REGEX.findAll(rawText).mapNotNull { extractThumbnailUrl(it.value) }.distinct().toList()
    } else {
        emptyList()
    }

    return ParsedMessage(displayText, imageUrls.distinct(), thumbnailUrls)
}
