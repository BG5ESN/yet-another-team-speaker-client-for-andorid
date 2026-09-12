package dev.tsdroid.model

/**
 * 一条聊天消息（频道或私聊）。
 *
 * 纯数据、无 Android 依赖。原先定义在 ServerViewModel.kt 里，导致 data 层、ui 层、
 * bridge 层想用它就得反向 import viewmodel 包 —— 这是本工程最主要的一处分层倒置。
 * 收进 model/ 之后依赖方向变成单向：ui/viewmodel/data/bridge → model。
 */
data class ChatMessage(
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMe: Boolean = false,
    val isPrivate: Boolean = false,
    val senderId: Int = 0,
    val fileAttachment: FileAttachment? = null,
)

/** 消息里附带的文件/图片附件。 */
data class FileAttachment(
    val fileName: String,
    val fileSize: Long,
    val fileId: String,
    val isImage: Boolean,
    val channelId: Long = 0L,
)
