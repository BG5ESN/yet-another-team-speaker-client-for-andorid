package dev.tsdroid.ui.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tsdroid.bridge.AvatarCache
import dev.tsdroid.bridge.TsClient
import dev.tsdroid.han.R
import dev.tsdroid.ui.theme.avatarColorFor
import dev.tslib.Channel
import dev.tslib.User

/**
 * 悬浮窗的 Compose 内容。
 *
 * 原先它是 TsConnectionService 的私有成员函数（321 行 Compose 塞在 Service 里），
 * 靠闭包隐式捕获 Service 的 tsClient / avatarCache / overlayActiveSpeakerId。
 * 搬到独立文件后依赖全部走参数（新增 myId / activeSpeakerId / avatarCache / tsClient 四个），
 * 于是：Service 不再依赖 ui 层，这个面板也能脱离 Service 单独预览和改动。
 *
 * 窗口本身（WindowManager、LayoutParams、拖动位置持久化、显示/隐藏）仍然归 Service 管，
 * 通过 onDrag / onSizeChange / onToggleExpand 这些回调传进来 —— 这里只负责画。
 */
@Composable
fun FloatingOverlayContent(
connected: Boolean,
channelName: String?,
activeSpeakerName: String?,
activeSpeakerAvatar: ImageBitmap?,
isLocalVoiceActive: Boolean,
isExpanded: Boolean,
onToggleExpand: () -> Unit,
onDrag: (Float, Float) -> Unit,
onSizeChange: (Int, Int) -> Unit,
channels: List<Channel>,
users: List<User>,
isMicMuted: Boolean,
isOutputMuted: Boolean,
onToggleMic: () -> Unit,
onToggleOutput: () -> Unit,
onChannelClick: (Long) -> Unit,
onClose: () -> Unit,
// ↓ 以下四个原先是从 TsConnectionService 的成员里"隐式捕获"的，
//   搬到独立文件后必须显式传入（这样才能离开 Service 独立编译与预览）
myId: Int?,
activeSpeakerId: Int?,
avatarCache: AvatarCache,
tsClient: TsClient,
) {
    val CardBackgroundTransparent = Color(0x991A1A1A) // ~60% alpha dark glass base
    val SurfaceMutedTransparent = Color(0x33FFFFFF) // Subdued element backgrounds

    // Find current channel users（myId 由调用方传入）
    val currentChannelId = users.find { it.id == myId }?.channelId
    val activeUsers = users.filter { it.channelId == currentChannelId }

    Box(
        modifier = Modifier
            .wrapContentSize()
            .background(Color.Transparent) // Force the root container token to be 100% transparent
            .onSizeChanged { size -> onSizeChange(size.width, size.height) }
    ) {
        if (!isExpanded) {
            // --- COLLAPSED AVATAR BUBBLE ---
            // Try to get local user avatar even when not speaking
            val localUser = myId?.let { users.find { u -> u.id == it } }
            val localUid = localUser?.uid
            
            // Check if local user is speaking:
            // 1. Local audio activity (may fail after screen off on some devices)
            // 2. Server-reported talk status (reliable even after screen off)
            val isLocalUserSpeaking = myId != null && (isLocalVoiceActive || activeSpeakerId == myId)
            // Check if remote user is speaking based on activeSpeakerName
            val isRemoteUserSpeaking = !activeSpeakerName.isNullOrEmpty() && (myId == null || activeSpeakerId != myId)
            // Combined speaking state
            val isSpeaking = isLocalUserSpeaking || isRemoteUserSpeaking
            
            // Determine which avatar to show in the bubble
            val displayAvatar = if (isLocalUserSpeaking) {
                // When local user is speaking, show our own avatar
                if (!localUid.isNullOrEmpty()) {
                    val cached = avatarCache.getAvatar(localUid)
                    if (cached != null) cached else activeSpeakerAvatar
                } else {
                    activeSpeakerAvatar
                }
            } else if (isRemoteUserSpeaking) {
                // When remote user is speaking, show their avatar
                activeSpeakerAvatar
            } else {
                // When nobody is speaking, still show local user avatar
                if (!localUid.isNullOrEmpty()) {
                    avatarCache.getAvatar(localUid)
                } else null
            }
            
            // 头像兜底用谁的名字：本地说话用自己的，远端说话用说话人的
            val displayNickname = when {
                isLocalUserSpeaking -> localUser?.nickname ?: activeSpeakerName
                isRemoteUserSpeaking -> activeSpeakerName
                else -> localUser?.nickname
            }

            val borderColor = if (isSpeaking) Color(0xFF2196F3) else Color(0x4DFFFFFF)
            val borderWidth = if (isSpeaking) 2.dp else 1.dp
            
            Surface(
                modifier = Modifier
                    .size(40.dp) // Make smaller
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    }
                    .clickable { onToggleExpand() },
                shape = CircleShape,
                color = CardBackgroundTransparent, // Semitransparent ring
                border = BorderStroke(borderWidth, borderColor)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Render Avatar Circle + Mini Speaker Waveform Indicator
                    if (displayAvatar != null) {
                        // Have an avatar available: show it
                        androidx.compose.foundation.Image(
                            bitmap = displayAvatar,
                            contentDescription = "Avatar",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop,
                            alpha = 1.0f
                        )
                    } else if (isSpeaking) {
                        // 没有头像（服务器上这个人就没设头像）：用首字头像兜底，和频道树同一套取色，
                        // 别显示一个跟谁都对不上的通用人形图标
                        val nick = displayNickname ?: "?"
                        val bgColor = avatarColorFor(nick)
                        Box(
                            modifier = Modifier.fillMaxSize().clip(CircleShape).background(bgColor),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = nick.firstOrNull()?.uppercase() ?: "?",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                        }
                    } else {
                        // No speaker: Show software logo
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "Open Panel",
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxSize(0.8f),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        } else {
            // --- EXPANDED MINIMALIST PANEL ---
            Card(
                modifier = Modifier
                    .width(200.dp)
                    .height(240.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackgroundTransparent), // Blends flawlessly over game/desktop backgrounds
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    // 1. Header Row (Title + Minimize Button)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount.x, dragAmount.y)
                                }
                            }
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (connected) Color(0xFF4CAF50) else Color(0xFFF44336),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = channelName ?: "Offline",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = onToggleExpand, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Minimize", tint = Color.White)
                        }
                    }

                    Divider(color = SurfaceMutedTransparent, thickness = 1.dp)

                    // 2. Simplified Channel User List (Scrollable, clean list items)
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        items(activeUsers) { user ->
                            val isSpeaking = if (user.id == myId) isLocalVoiceActive else user.id == activeSpeakerId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Use smaller avatar for the expanded list instead of green dot
                                var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
                                
                                LaunchedEffect(user.uid, user.avatarId) {
                                    // 没有 avatarId 的人根本不要去请求（服务器会回 FileInvalidPath）
                                    if (!user.uid.isNullOrEmpty() && !user.avatarId.isNullOrEmpty()) {
                                        val cached = avatarCache.getAvatar(user.uid)
                                        if (cached != null) {
                                            avatarBitmap = cached
                                        } else if (!avatarCache.hasNoAvatar(user.uid)) {
                                            avatarCache.loadAvatar(user.uid, tsClient)
                                            avatarBitmap = avatarCache.getAvatar(user.uid)
                                        }
                                    }
                                }
                                
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .border(
                                            width = if (isSpeaking) 2.dp else 0.dp,
                                            color = if (isSpeaking) Color(0xFF2196F3) else Color.Transparent,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (avatarBitmap != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = avatarBitmap!!,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        // 首字头像兜底（同频道树取色）
                                        val bgColor = avatarColorFor(user.nickname)
                                        Box(
                                            modifier = Modifier.fillMaxSize().clip(CircleShape).background(bgColor),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = user.nickname.firstOrNull()?.uppercase() ?: "?",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                            )
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = user.nickname,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSpeaking) Color.White else Color(0xCCFFFFFF),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Divider(color = SurfaceMutedTransparent, thickness = 1.dp)

                    // 4. Quick Actions Toolbar (Mute, Deafen, Disconnect) with alpha surfaces
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mic Mute Toggle
                        IconButton(
                            onClick = onToggleMic,
                            modifier = Modifier.background(
                                if (isMicMuted) Color(0x66F44336) else SurfaceMutedTransparent,
                                CircleShape
                            )
                        ) {
                            Icon(
                                imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Toggle Mic",
                                tint = Color.White
                            )
                        }

                        // Output Mute Toggle
                        IconButton(
                            onClick = onToggleOutput,
                            modifier = Modifier.background(
                                if (isOutputMuted) Color(0x66F44336) else SurfaceMutedTransparent,
                                CircleShape
                            )
                        ) {
                            Icon(
                                imageVector = if (isOutputMuted) Icons.Default.HeadsetOff else Icons.Default.Headset,
                                contentDescription = "Toggle Output",
                                tint = Color.White
                            )
                        }

                        // Disconnect
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.background(SurfaceMutedTransparent, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ExitToApp,
                                contentDescription = "Disconnect",
                                tint = Color(0xFFFF5252)
                            )
                        }
                    }
                }
            }
        }
    }
}
