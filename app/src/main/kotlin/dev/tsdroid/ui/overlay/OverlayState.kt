package dev.tsdroid.ui.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 悬浮窗的显示状态，集中放一处。
 *
 * 原先这些字段散在 TsConnectionService 的字段区里，跟窗口对象（overlayView /
 * LayoutParams）、连接状态、Service 生命周期标志（isStopping / restartRequestedWhileStopping）
 * 混在一起，读代码时很难分清"哪几个是给悬浮窗用的"。
 *
 * 两类：
 *  · mutableStateOf 的部分 —— Compose 观察，写这些字段会驱动悬浮窗重组
 *  · 位置记忆是普通字段 —— 只有 Service 自己的逻辑读写
 *
 * 注：说话人"防抖"那套中间状态（pendingSpeakerId / speakerUpdateJob /
 * pendingLocalSpeaking / localSpeakingJob）已经删掉。源头 AudioBridge 自带 250ms 保持
 * （RING_HANGOVER_MS），原来的 500ms 防抖属于重复，会让悬浮窗反应慢近 600ms，
 * 而它的唯一作用"防字间闪"在源头已经做完了。
 */
class OverlayState {
    // ── Compose 观察 ──
    var connected by mutableStateOf(false)
    var channelName by mutableStateOf<String?>(null)
    var activeSpeakerId by mutableStateOf<Int?>(null)
    var activeSpeakerName by mutableStateOf<String?>(null)
    var activeSpeakerAvatar by mutableStateOf<ImageBitmap?>(null)
    var expanded by mutableStateOf(false)
    var delayedLocalSpeaking by mutableStateOf(false)

    // ── 拖动位置（窗口位置记忆）──
    var positionBeforeExpand: Pair<Int, Int>? = null
    var lastSavedX = 100
    var lastSavedY = 300
}
