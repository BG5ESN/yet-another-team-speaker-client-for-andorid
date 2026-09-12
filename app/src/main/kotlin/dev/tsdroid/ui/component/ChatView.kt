package dev.tsdroid.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tsdroid.model.ChatMessage
import dev.tsdroid.model.DownloadState
import dev.tsdroid.model.FileAttachment
import kotlinx.coroutines.flow.StateFlow

private const val INITIAL_PAGE = 15
private const val PAGE_SIZE = 20

@Composable
fun ChatView(
    messages: List<ChatMessage>,
    showLinkThumbnails: Boolean = false,
    autoLoadImages: Boolean = true,
    onDownload: ((FileAttachment) -> StateFlow<DownloadState>)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var visibleCount by remember { mutableIntStateOf(INITIAL_PAGE) }

    // reverseLayout=true: index 0 = bottom (newest), high index = top (oldest)
    val displayMessages = remember(messages, visibleCount) {
        messages.takeLast(visibleCount.coerceAtMost(messages.size)).asReversed()
    }

    // Auto-scroll to bottom on new messages (only if already near bottom)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && listState.firstVisibleItemIndex <= 3) {
            listState.animateScrollToItem(0)
        }
    }

    // Load more when scrolling near the top (high indices in reversed layout)
    val totalMessages by rememberUpdatedState(messages.size)
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to info.totalItemsCount
        }.collect { (lastVisible, totalItems) ->
            if (totalItems > 0 && lastVisible >= totalItems - 3 && visibleCount < totalMessages) {
                visibleCount = (visibleCount + PAGE_SIZE).coerceAtMost(totalMessages)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        reverseLayout = true,
    ) {
        items(displayMessages, key = { it.timestamp }) { message ->
            MessageBubble(
                message = message,
                showLinkThumbnails = showLinkThumbnails,
                autoLoadImages = autoLoadImages,
                onDownload = onDownload,
            )
        }
    }
}
