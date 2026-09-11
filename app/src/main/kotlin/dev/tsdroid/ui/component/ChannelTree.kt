package dev.tsdroid.ui.component

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tslib.Channel
import dev.tslib.ChannelTree as JChannelTree
import dev.tslib.User

sealed class TreeItem {
    data class ChannelNode(val channel: Channel, val depth: Int) : TreeItem()
    data class UserNode(val user: User, val depth: Int) : TreeItem()
}

@Composable
fun ChannelTree(
    channels: List<Channel>,
    users: List<User>,
    onChannelClick: (Long) -> Unit,
    onUserClick: ((User) -> Unit)? = null,
    onUserLongClick: ((User) -> Unit)? = null,
    mutedUserIds: Set<Int> = emptySet(),
    channelIcons: Map<Long, ImageBitmap> = emptyMap(),
    userAvatars: Map<String, ImageBitmap> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    // Filter nulls early — JNI arrays can contain null elements
    @Suppress("USELESS_CAST")
    val safeUsers = remember(users) { users.filterNotNull() }
    val treeItems = remember(channels, safeUsers) {
        val items = buildTreeItems(channels, safeUsers)
        items
    }
    val userCountByChannel = remember(safeUsers) {
        safeUsers.groupingBy { it.channelId }.eachCount()
    }

    LazyColumn(modifier = modifier) {
        items(treeItems, key = { item ->
            when (item) {
                is TreeItem.ChannelNode -> "ch_${item.channel.id}"
                is TreeItem.UserNode -> "usr_${item.user.id}"
            }
        }) { item ->
            when (item) {
                is TreeItem.ChannelNode -> ChannelRow(
                    channel = item.channel,
                    depth = item.depth,
                    userCount = userCountByChannel[item.channel.id] ?: 0,
                    onClick = { onChannelClick(item.channel.id) },
                    icon = channelIcons[item.channel.iconId],
                )
                is TreeItem.UserNode -> UserItem(
                    user = item.user,
                    avatar = item.user.uid?.let { userAvatars[it] },
                    modifier = Modifier.fillMaxWidth().padding(start = (item.depth * 24 + 32).dp),
                    onClick = onUserClick?.let { { it(item.user) } },
                    onToggleMute = onUserLongClick?.let { { it(item.user) } },
                    isLocallyMuted = item.user.id in mutedUserIds,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    depth: Int,
    userCount: Int,
    onClick: () -> Unit,
    icon: ImageBitmap? = null,
) {
    val spacer = remember(channel.name) { parseSpacer(channel.name) }

    if (spacer != null) {
        when (spacer.type) {
            SpacerType.REPEAT -> {
                HorizontalDivider(
                    modifier = Modifier.padding(
                        start = (depth * 24).dp, top = 4.dp, bottom = 4.dp, end = 8.dp,
                    ),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            else -> {
                val align = when (spacer.type) {
                    SpacerType.CENTER -> TextAlign.Center
                    SpacerType.RIGHT -> TextAlign.End
                    else -> TextAlign.Start
                }
                val hasText = spacer.displayText.isNotEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = (depth * 24).dp,
                            top = if (hasText) 4.dp else 2.dp,
                            bottom = if (hasText) 4.dp else 2.dp,
                            end = 8.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = spacer.displayText.ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = align,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(
                    start = (depth * 24).dp,
                    top = 6.dp,
                    bottom = 6.dp,
                    end = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    if (userCount > 0) Icons.Default.FolderOpen else Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (userCount > 0) {
                Text(
                    text = "($userCount)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun buildTreeItems(channels: List<Channel>, users: List<User>): List<TreeItem> {
    if (channels.isEmpty()) return emptyList()

    // Filter out any null elements that may come from JNI
    @Suppress("USELESS_CAST")
    val safeChannels = channels.filterNotNull()
    @Suppress("USELESS_CAST")
    val safeUsers = users.filterNotNull()

    if (safeChannels.isEmpty()) return emptyList()

    val tree = JChannelTree.fromChannels(safeChannels.toTypedArray())
    val usersByChannel = safeUsers.groupBy { it.channelId }


    val items = mutableListOf<TreeItem>()

    fun addChannel(channel: Channel, depth: Int) {
        items.add(TreeItem.ChannelNode(channel, depth))
        // Add users in this channel
        usersByChannel[channel.id]?.forEach { user ->
            items.add(TreeItem.UserNode(user, depth + 1))
        }
        // Add sub-channels (filter nulls from JNI array)
        tree.getChildren(channel.id)?.filterNotNull()?.forEach { child ->
            addChannel(child, depth + 1)
        }
    }

    tree.roots?.filterNotNull()?.forEach { root -> addChannel(root, 0) }
    tree.close()
    return items
}