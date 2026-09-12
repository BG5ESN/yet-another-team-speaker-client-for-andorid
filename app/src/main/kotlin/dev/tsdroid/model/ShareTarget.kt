package dev.tsdroid.model

/** 文件要分享到哪里：当前频道，或某个私聊对象。 */
sealed class ShareTarget {
    data object Channel : ShareTarget()
    data class PrivateMessage(val userId: Int, val nickname: String) : ShareTarget()
}
