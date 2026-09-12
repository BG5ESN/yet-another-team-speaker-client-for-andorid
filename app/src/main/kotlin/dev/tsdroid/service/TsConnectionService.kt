package dev.tsdroid.service

import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.compose.ui.graphics.ImageBitmap
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.tsdroid.MainActivity
import dev.tsdroid.ui.overlay.FloatingOverlayContent
import dev.tsdroid.ui.overlay.OverlayState
import dev.tsdroid.han.R
import dev.tsdroid.TsDroidApp
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.AvatarCache
import dev.tsdroid.bridge.TsClient
import dev.tslib.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TsConnectionService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    private val serviceViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val viewModelStore: ViewModelStore get() = serviceViewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    companion object {
        private const val TAG = "TsConnService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.flammedemon.ts6droid.DISCONNECT"
        private const val ACTION_TOGGLE_MUTE = "com.flammedemon.ts6droid.TOGGLE_MUTE"
        private const val AVATAR_REFRESH_INTERVAL_MS = 30000L // 30 seconds

        var instance: TsConnectionService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        val tsClient: TsClient get() = this@TsConnectionService.tsClient
        val audioBridge: AudioBridge get() = this@TsConnectionService.audioBridge
        val service: TsConnectionService get() = this@TsConnectionService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val tsClient = TsClient()
    lateinit var audioBridge: AudioBridge
        private set

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    /** 悬浮窗状态（显示状态 + 说话人防抖中间量），定义见 ui/overlay/OverlayState.kt */
    private val overlayState = OverlayState()

    private lateinit var avatarCache: AvatarCache

    private var isIntentionalDisconnect = false
    private var latestStartId = 0
    @Volatile private var isStopping = false
    @Volatile private var restartRequestedWhileStopping = false

    override fun onCreate() {
        super.onCreate()
        isStopping = false
        restartRequestedWhileStopping = false
        instance = this
        Log.d(TAG, "Foreground Service Created")
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        avatarCache = AvatarCache(applicationContext.cacheDir)
        audioBridge = AudioBridge(applicationContext, tsClient)
        audioBridge.initialize()
        
        // Load saved floating window position
        loadSavedPosition()

        // Listen for audio events, talk status, and play per-user mixing
        tsClient.events.onEach { event ->
            when (event.type) {
                "audio_received" -> {
                    val userId = (event.data["user_id"] as? Number)?.toInt() ?: return@onEach
                    // 语音包序号：用来去重/重排（老 .so 没这个字段时传 -1）
                    val packetId = (event.data["packet_id"] as? Number)?.toInt() ?: -1
                    val data = event.data["data"]
                    if (data is ByteArray) {
                        audioBridge.playAudio(userId, packetId, data)
                    } else if (data is Array<*>) {
                        val bytes = ByteArray(data.size) { (data[it] as? Number)?.toByte() ?: 0 }
                        audioBridge.playAudio(userId, packetId, bytes)
                    }
                }
            }
        }.launchIn(serviceScope)

        // 悬浮窗的"当前说话人"由音频能量驱动（AudioBridge.loudestSpeakerId，解码 PCM 算 dBFS）。
        // 以前靠 talk_status_* 事件（一次性）或原生快照 is_talking（"最近 300ms 收到语音包"）：
        // 后者在对方客户端持续发流时会常亮、不跟着人动。
        tsClient.users
            .combine(audioBridge.loudestSpeakerId) { users, loudest -> users to loudest }
            .onEach { (users, loudest) ->
                val myId = tsClient.clientId
                // 谁在说话由音频能量决定（AudioBridge 解码 PCM 算 dBFS + 250ms 保持）；
                // 多人同时说话取"最响的那个"，不再取无序哈希表里的最后一个（会乱跳/黏住某个人）
                val speakerId = loudest?.takeIf { it != myId }
                val speaker = speakerId?.let { id -> users.firstOrNull { u -> u.id == id } }
                if (speakerId == overlayState.activeSpeakerId) return@onEach

                // 立即切换，不做防抖 —— 这里原来还有一层 500ms 的 delay，属于**重复防抖**：
                // 源头 loudestSpeakerId 已经带了 250ms 保持（AudioBridge 的 RING_HANGOVER_MS），
                // "字与字之间的间隙让 icon 闪"这件事在源头就兜住了。再叠 500ms 的结果是
                // 悬浮窗要近 600ms 才反应过来（实测反馈"反应好慢"）。
                if (speakerId != null) {
                    Log.i(TAG, "悬浮窗说话人 -> $speakerId (${findUserNickname(speakerId)})")
                    overlayState.activeSpeakerId = speakerId
                    overlayState.activeSpeakerName = findUserNickname(speakerId)
                    val uid = speaker?.uid
                    // 先看服务器给的头像标记（client_flag_avatar）：为空就是"这个人没设头像"，
                    // 此时请求 /avatar_xxx 服务器会回 0x0806 FileInvalidPath —— 不要问不存在的文件
                    val hasAvatar = !uid.isNullOrEmpty() && !speaker?.avatarId.isNullOrEmpty()
                    if (hasAvatar) {
                        val cached = avatarCache.getAvatar(uid!!)
                        overlayState.activeSpeakerAvatar = cached
                        if (cached == null) {
                            // 头像只能异步补：先亮出身份（名字），加载完若还是他在说就换上头像
                            serviceScope.launch(Dispatchers.IO) {
                                avatarCache.loadAvatar(uid, tsClient)
                                val avatar = avatarCache.getAvatar(uid)
                                withContext(Dispatchers.Main) {
                                    if (overlayState.activeSpeakerId == speakerId) {
                                        overlayState.activeSpeakerAvatar = avatar
                                    }
                                }
                            }
                        }
                    } else {
                        overlayState.activeSpeakerAvatar = null
                    }
                } else {
                    Log.i(TAG, "悬浮窗说话人 -> 清空（没人说话）")
                    overlayState.activeSpeakerId = null
                    overlayState.activeSpeakerName = null
                    overlayState.activeSpeakerAvatar = null
                }
            }
            .launchIn(serviceScope)

        tsClient.state.onEach { state ->
            overlayState.connected = state == dev.tslib.ConnectionState.CONNECTED
            updateOverlayChannelName()
            updateNotification()
        }.launchIn(serviceScope)

        tsClient.users.onEach {
            updateOverlayChannelName()
            refreshActiveSpeakerName()
        }.launchIn(serviceScope)

        tsClient.channels.onEach {
            updateOverlayChannelName()
        }.launchIn(serviceScope)
        
        // 本地说话：直接映射，不做防抖（源头 audioBridge.isLocalVoiceActive 已带 250ms 保持）
        audioBridge.isLocalVoiceActive.onEach { isSpeaking ->
            overlayState.delayedLocalSpeaking = isSpeaking

            // Force refresh local user avatar when speaking starts
            if (isSpeaking) {
                val myId = tsClient.clientId
                val localUser = tsClient.users.value.find { it.id == myId }
                val localUid = localUser?.uid
                if (!localUid.isNullOrEmpty()) {
                    serviceScope.launch(Dispatchers.IO) {
                        // Force refresh local avatar
                        avatarCache.clearMemoryCache(localUid)
                        avatarCache.loadAvatar(localUid, tsClient)
                        val avatar = avatarCache.getAvatar(localUid)
                        withContext(Dispatchers.Main) {
                            if (overlayState.activeSpeakerId == myId) {
                                overlayState.activeSpeakerAvatar = avatar
                            }
                        }
                    }
                }
            }
        }.launchIn(serviceScope)
        
        // Periodic avatar refresh — force re-download ALL user avatars to keep them fresh
        serviceScope.launch {
            while (true) {
                delay(AVATAR_REFRESH_INTERVAL_MS)
                val myId = tsClient.clientId
                if (myId == null) continue
                
                val currentUsers = tsClient.users.value
                val currentChannelId = currentUsers.find { it.id == myId }?.channelId
                val channelUsers = currentUsers.filter { it.channelId == currentChannelId }
                
                // Collect all UIDs in the current channel
                val uids = channelUsers.mapNotNull { it.uid }.filter { it.isNotEmpty() }.toList()
                if (uids.isEmpty()) continue
                
                serviceScope.launch(Dispatchers.IO) {
                    // Clear memory cache for all channel users to force re-download
                    avatarCache.clearMemoryCache(*uids.toTypedArray())
                    
                    // Re-download all avatars
                    for (uid in uids) {
                        avatarCache.loadAvatar(uid, tsClient)
                    }
                    
                    // Update the current speaker avatar if someone is speaking
                    val currentSpeakerId = overlayState.activeSpeakerId
                    if (currentSpeakerId != null) {
                        val speakerUser = currentUsers.find { it.id == currentSpeakerId }
                        val speakerUid = speakerUser?.uid
                        if (!speakerUid.isNullOrEmpty()) {
                            val updatedAvatar = avatarCache.getAvatar(speakerUid)
                            withContext(Dispatchers.Main) {
                                if (overlayState.activeSpeakerId == currentSpeakerId) {
                                    overlayState.activeSpeakerAvatar = updatedAvatar
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            Log.d(TAG, "Ignoring sticky restart without an explicit intent")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        latestStartId = startId
        startServiceForeground()

        if (isStopping) {
            if (intent.action != ACTION_DISCONNECT) {
                Log.d(TAG, "Start requested while service is stopping; will reopen after disconnect completes")
                restartRequestedWhileStopping = true
            }
            return START_NOT_STICKY
        }

        if (instance == null) {
            instance = this
        }
        
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
            }
            ACTION_TOGGLE_MUTE -> {
                audioBridge.toggleMute()
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    private fun startServiceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), foregroundServiceType())
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun foregroundServiceType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return type
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TsConnectionService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val muteIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TsConnectionService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val serverName = tsClient.serverInfo.value?.name ?: getString(R.string.connecting)
        val muteLabel = getString(if (audioBridge.isMuted.value) R.string.notif_unmute else R.string.notif_mute)

        return NotificationCompat.Builder(this, TsDroidApp.CHANNEL_ID_CONNECTION)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(serverName)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, muteLabel, muteIntent)
            .addAction(0, getString(R.string.disconnect), disconnectIntent)
            .build()
    }

    fun hasActiveConnection(address: String? = null): Boolean {
        return !isStopping &&
            tsClient.isConnected &&
            (address == null || tsClient.serverAddress == address)
    }

    suspend fun connect(address: String, identity: Identity, nickname: String, password: String?): Throwable? {
        if (isStopping) {
            return IllegalStateException("Connection service is still stopping. Please try again.")
        }

        isIntentionalDisconnect = false
        return try {
            tsClient.connect(address, identity, nickname, password)
            audioBridge.startCapture(serviceScope)
            // Sync initial mute state with server
            if (audioBridge.isMuted.value) {
                tsClient.setInputMuted(true)
            }
            // Start event loop
            tsClient.startEventLoop()
            null
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Connection error", e)
            cleanupFailedConnection()
            e
        }
    }

    fun disconnect() {
        disconnectAndStop()
    }

    private fun disconnectAndStop() {
        if (isStopping) return
        val stopStartId = latestStartId
        prepareToStop()
        isIntentionalDisconnect = true
        serviceScope.launch(Dispatchers.IO) {
            try {
                tsClient.disconnect()
            } finally {
                withContext(Dispatchers.Main) {
                    finishStopOrRestart(stopStartId)
                }
            }
        }
    }

    private fun prepareToStop() {
        isStopping = true
        if (instance == this) {
            instance = null
        }
        hideFloatingWindow()
        audioBridge.stopCapture()
    }

    private fun cleanupFailedConnection() {
        hideFloatingWindow()
        audioBridge.stopCapture()
        isStopping = false
        restartRequestedWhileStopping = false
        instance = this
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun finishStopOrRestart(stopStartId: Int) {
        if (restartRequestedWhileStopping || latestStartId != stopStartId) {
            restartRequestedWhileStopping = false
            isStopping = false
            instance = this
            updateNotification()
            return
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopStartId != 0) {
            stopSelf(stopStartId)
        } else {
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed; disconnecting foreground TS session")
        disconnectAndStop()
        super.onTaskRemoved(rootIntent)
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    fun showFloatingWindow() {
        Log.d(TAG, "showFloatingWindow called")
        if (!hasOverlayPermission()) {
            Log.w(TAG, "Cannot show floating window because overlay permission is missing")
            return
        }
        if (overlayView != null) {
            Log.d(TAG, "showFloatingWindow skipped: already visible")
            return
        }

        val displayMetrics = resources.displayMetrics
        val widthPx = (280 * displayMetrics.density).toInt()
        val heightPx = (350 * displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            gravity = Gravity.TOP or Gravity.START
            x = overlayState.lastSavedX
            y = overlayState.lastSavedY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@TsConnectionService)
            setViewTreeViewModelStoreOwner(this@TsConnectionService)
            setViewTreeSavedStateRegistryOwner(this@TsConnectionService)
            
            setContent {
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val density = androidx.compose.ui.platform.LocalDensity.current
                val screenWidthDp = configuration.screenWidthDp.dp
                val screenHeightDp = configuration.screenHeightDp.dp
                
                val channels by tsClient.channels.collectAsStateWithLifecycle()
                val users by tsClient.users.collectAsStateWithLifecycle()
                val isMicMuted by audioBridge.isMuted.collectAsStateWithLifecycle()
                val isOutputMuted by audioBridge.isOutputMuted.collectAsStateWithLifecycle()
                val isLocalVoiceActive by audioBridge.isLocalVoiceActive.collectAsStateWithLifecycle()
                
                // 本地说话直接映射：源头已有 250ms 保持，这里原来又 delay 一次
                // （和上层的 500ms 叠起来总计上秒），是第三层重复延迟
                LaunchedEffect(isLocalVoiceActive) {
                    overlayState.delayedLocalSpeaking = isLocalVoiceActive
                }
                
                FloatingOverlayContent(
                    connected = overlayState.connected,
                    channelName = overlayState.channelName,
                    activeSpeakerName = overlayState.activeSpeakerName,
                    activeSpeakerAvatar = overlayState.activeSpeakerAvatar,
                    isLocalVoiceActive = overlayState.delayedLocalSpeaking,
                    isExpanded = overlayState.expanded,
                    onToggleExpand = { 
                        overlayLayoutParams?.let { layout ->
                            if (!overlayState.expanded) {
                                // Saving position before expanding
                                overlayState.positionBeforeExpand = Pair(layout.x, layout.y)
                                Log.d(TAG, "Saved position before expand: ${overlayState.positionBeforeExpand}")
                            } else {
                                // Restore position when collapsing
                                overlayState.positionBeforeExpand?.let { (x, y) ->
                                    layout.x = x
                                    layout.y = y
                                    try {
                                        windowManager.updateViewLayout(this, layout)
                                    } catch (_: Exception) {}
                                    Log.d(TAG, "Restored position after collapse: ($x, $y)")
                                }
                                overlayState.positionBeforeExpand = null
                            }
                        }
                        overlayState.expanded = !overlayState.expanded 
                    },
                    onDrag = { dx, dy ->
                        overlayLayoutParams?.let { layout ->
                            val metrics = resources.displayMetrics
                            val maxX = metrics.widthPixels - this.width
                            val maxY = metrics.heightPixels - this.height

                            layout.x = (layout.x + dx.toInt()).coerceIn(0, maxOf(0, maxX))
                            layout.y = (layout.y + dy.toInt()).coerceIn(0, maxOf(0, maxY))
                            
                            // Update cached position for persistence
                            overlayState.lastSavedX = layout.x
                            overlayState.lastSavedY = layout.y
                            
                            try {
                                windowManager.updateViewLayout(this, layout)
                            } catch (_: Exception) {}
                        }
                    },
                    onSizeChange = { w, h ->
                        overlayLayoutParams?.let { layout ->
                            val metrics = resources.displayMetrics
                            val maxX = metrics.widthPixels - w
                            val maxY = metrics.heightPixels - h

                            val newX = layout.x.coerceIn(0, maxOf(0, maxX))
                            val newY = layout.y.coerceIn(0, maxOf(0, maxY))

                            if (layout.x != newX || layout.y != newY) {
                                layout.x = newX
                                layout.y = newY
                                try {
                                    windowManager.updateViewLayout(this, layout)
                                } catch (_: Exception) {}
                            }
                        }
                    },
                    channels = channels,
                    users = users,
                    isMicMuted = isMicMuted,
                    isOutputMuted = isOutputMuted,
                    onToggleMic = { audioBridge.toggleMute() },
                    onToggleOutput = { audioBridge.toggleOutputMute() },
                    onChannelClick = { channelId -> tsClient.moveToChannel(channelId) },
                    onClose = { hideFloatingWindow() },
                    // 原先这四个是 Composable 从 Service 成员里隐式捕获的，现在显式传入
                    myId = tsClient.clientId,
                    activeSpeakerId = overlayState.activeSpeakerId,
                    avatarCache = avatarCache,
                    tsClient = tsClient,
                )
            }
        }

        overlayView = composeView
        overlayLayoutParams = params
        windowManager.addView(composeView, params)
    }

    private fun updateOverlayChannelName() {
        val myId = tsClient.clientId ?: return
        val currentChannelId = tsClient.users.value.find { it.id == myId }?.channelId
        overlayState.channelName = currentChannelId?.let { channelId ->
            tsClient.channels.value.find { it.id == channelId }?.name
        }
    }

    private fun refreshActiveSpeakerName() {
        overlayState.activeSpeakerName = overlayState.activeSpeakerId?.let { findUserNickname(it) }
    }

    private fun findUserNickname(userId: Int): String? {
        return tsClient.users.value.firstOrNull { it.id == userId }?.nickname
    }

    fun hideFloatingWindow() {
        Log.d(TAG, "hideFloatingWindow called")
        overlayView?.let { view ->
            try {
                // Save current position before removing the view
                overlayLayoutParams?.let { params ->
                    overlayState.lastSavedX = params.x
                    overlayState.lastSavedY = params.y
                    saveCachedPosition()
                }
                windowManager.removeViewImmediate(view)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        overlayLayoutParams = null
        overlayState.expanded = false
    }
    
    private fun saveCachedPosition() {
        val prefs = getSharedPreferences("floating_window_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("position_x", overlayState.lastSavedX)
            putInt("position_y", overlayState.lastSavedY)
            apply()
        }
        Log.d(TAG, "Saved floating window position: ($overlayState.lastSavedX, $overlayState.lastSavedY)")
    }
    
    private fun loadSavedPosition() {
        val prefs = getSharedPreferences("floating_window_prefs", Context.MODE_PRIVATE)
        overlayState.lastSavedX = prefs.getInt("position_x", 100)
        overlayState.lastSavedY = prefs.getInt("position_y", 300)
        Log.d(TAG, "Loaded floating window position: ($overlayState.lastSavedX, $overlayState.lastSavedY)")
    }

    override fun onDestroy() {
        instance = null
        serviceViewModelStore.clear()
        hideFloatingWindow()
        audioBridge.stopCapture()
        try {
            tsClient.disconnect()
        } catch (e: Throwable) {
            Log.w(TAG, "Best-effort disconnect during service destroy failed", e)
        }
        audioBridge.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}

