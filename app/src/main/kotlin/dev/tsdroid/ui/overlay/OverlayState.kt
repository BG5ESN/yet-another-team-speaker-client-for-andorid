package dev.tsdroid.ui.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Job

/**
 * 悬浮窗的显示状态 + 说话人防抖的中间状态，集中放一处。
 *
 * 原先这 14 个字段散在 TsConnectionService 的字段区里，跟窗口对象（overlayView /
 * LayoutParams）、连接状态、Service 生命周期标志（isStopping / restartRequestedWhileStopping）
 * 混在一起，读代码时很难分清"哪几个是给悬浮窗用的"。
 *
 * 分两类：
 *  · Compose 观察的部分用 mutableStateOf —— 写在这些字段上的改动会驱动悬浮窗重组；
 *  · 防抖 / 位置的中间变量是普通字段 —— 只有 Service 自己的逻辑读写。
 *
 * 这里只放**状态**，不放行为：说话人防抖那套时序（等多久、谁覆盖谁）仍然写在 Service 里，
 * 本次不搬，避免行为变化。
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

    // ── 说话人防抖的中间状态 ──
    var pendingSpeakerId: Int? = null
    var speakerUpdateJob: Job? = null
    var pendingLocalSpeaking: Boolean? = null
    var localSpeakingJob: Job? = null

    // ── 拖动位置（窗口位置记忆）──
    var positionBeforeExpand: Pair<Int, Int>? = null
    var lastSavedX = 100
    var lastSavedY = 300
}
