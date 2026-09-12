package dev.tsdroid.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import dev.tsdroid.han.R
import dev.tslib.User
import dev.tsdroid.ui.theme.avatarColorFor

import androidx.compose.foundation.clickable
import androidx.compose.material3.IconButton

@Composable
fun UserItem(
    user: User,
    avatar: ImageBitmap? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onToggleMute: (() -> Unit)? = null,
    isLocallyMuted: Boolean = false,
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick?.invoke() }
                .padding(vertical = 3.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarWithRing(
                avatar = avatar,
                nickname = user.nickname,
                isTalking = user.isTalking,
                isRecording = user.isRecording,
                isInputMuted = user.isInputMuted || !user.hasInputHardware,
                isOutputMuted = user.isOutputMuted,
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = user.nickname,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                color = when {
                    user.isTalking -> MaterialTheme.colorScheme.primary
                    user.isAway -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )

            // Detail status icons on the right
            if (user.isRecording) {
                Icon(
                    Icons.Default.RadioButtonChecked,
                    contentDescription = stringResource(R.string.status_recording),
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFFF44336),
                )
                Spacer(Modifier.width(2.dp))
            }
            if (user.isAway) {
                Icon(
                    Icons.Default.NightsStay,
                    contentDescription = stringResource(R.string.status_away),
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFFFF9800),
                )
                Spacer(Modifier.width(2.dp))
            }
            
            // Inline mute toggle button instead of long press menu
            if (onToggleMute != null) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = stringResource(if (isLocallyMuted) R.string.unmute_user else R.string.mute_user),
                        modifier = Modifier.size(16.dp),
                        tint = if (isLocallyMuted) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            } else if (isLocallyMuted) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = stringResource(R.string.user_muted),
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFFFF9800),
                )
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

@Composable
private fun AvatarWithRing(
    avatar: ImageBitmap?,
    nickname: String,
    isTalking: Boolean,
    isRecording: Boolean,
    isInputMuted: Boolean = false,
    isOutputMuted: Boolean = false,
) {
    val ringActive = isTalking || isRecording
    val ringColor = when {
        isRecording -> Color(0xFFF44336)
        isTalking -> Color(0xFF2196F3)
        else -> Color.Transparent
    }

    val ringModifier = if (ringActive) Modifier.border(
        width = 2.dp,
        color = ringColor,
        shape = CircleShape,
    ) else Modifier

    Box(
        modifier = Modifier.size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            Image(
                bitmap = avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .then(ringModifier),
                contentScale = ContentScale.Crop,
            )
        } else {
            val bgColor = avatarColorFor(nickname)
            val letter = nickname.firstOrNull()?.uppercase() ?: "?"
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(bgColor)
                    .then(ringModifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letter,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (isInputMuted) {
            Icon(
                Icons.Default.MicOff,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(1.dp),
                tint = Color(0xFFF44336),
            )
        }
        if (isOutputMuted) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomStart)
                    .offset(x = (-2).dp, y = 2.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(1.dp),
                tint = Color(0xFFFF9800),
            )
        }
    }
}
