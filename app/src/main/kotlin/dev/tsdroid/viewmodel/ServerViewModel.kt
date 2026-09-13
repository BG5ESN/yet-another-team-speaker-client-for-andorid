package dev.tsdroid.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import dev.tsdroid.bridge.AvatarCache
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.audio.AudioRouteDevice
import dev.tsdroid.bridge.audio.FOLLOW_SYSTEM
import dev.tsdroid.bridge.IconCache
import dev.tsdroid.bridge.TsClient
import dev.tsdroid.model.ChatMessage
import dev.tsdroid.model.DownloadState
import dev.tsdroid.model.FileAttachment
import dev.tsdroid.model.MessageParser
import dev.tsdroid.model.ParsedMessage
import dev.tsdroid.model.TsFileEntry
import dev.tsdroid.han.R
import dev.tsdroid.data.BookmarkStore
import dev.tsdroid.data.MessageStore
import dev.tsdroid.data.FileTransferController
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.service.TsConnectionService
import dev.tslib.Channel
import dev.tslib.ConnectionState
import dev.tslib.Event
import dev.tslib.ServerInfo
import dev.tslib.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServerViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ServerViewModel"

        /** 聊天里要按图片渲染的扩展名（上传和分享两条路径共用一份） */
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    }

    private val messageStore = MessageStore(application)
    private val bookmarkStore = BookmarkStore(application)
    private val settingsStore = SettingsStore(application)
    private val iconCache = IconCache(application.cacheDir)
    private val avatarCache = AvatarCache(application.cacheDir)
    private var serverAddress: String? = null
    private var saveJob: Job? = null
    // In-memory cache: avoids re-reading from disk + re-decoding for the same image
    private var tsClient: TsClient? = null
    private var audioBridge: AudioBridge? = null
    private var connectionService: TsConnectionService? = null

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    // Raw users from the server (isTalking always false in snapshots)
    private val _rawUsers = MutableStateFlow<List<User>>(emptyList())

    // Set of currently talking user IDs (tracked via talk_status events)
    private val _talkingUserIds = MutableStateFlow<Set<Int>>(emptySet())
    val talkingUserIds: StateFlow<Set<Int>> = _talkingUserIds.asStateFlow()
    
    // Track local mic state for local user talking highlight
    // ⚠️ 这个镜像**不能**删掉改用 isLocalVoiceActive：init{} 里那个 combine 在 ViewModel
    //    构造时就求值参数，那时 audioBridge 还是 null，换成 getter 会永久绑到 idle 流上，
    //    说话圈再也不亮。镜像的作用是给 combine 一个"构造时就存在、之后还能更新"的流。
    private val _isLocalTalking = MutableStateFlow(false)

    private val _mutedUserIds = MutableStateFlow<Set<Int>>(emptySet())
    val mutedUserIds: StateFlow<Set<Int>> = _mutedUserIds.asStateFlow()

    // Users with isTalking patched from talk status events
    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    private val _channelIcons = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val channelIcons: StateFlow<Map<Long, ImageBitmap>> = _channelIcons.asStateFlow()

    private val _avatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val avatars: StateFlow<Map<String, ImageBitmap>> = _avatars.asStateFlow()

    private val _channelMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val channelMessages: StateFlow<List<ChatMessage>> = _channelMessages.asStateFlow()

    private val _privateMessages = MutableStateFlow<Map<Int, List<ChatMessage>>>(emptyMap())
    val privateMessages: StateFlow<Map<Int, List<ChatMessage>>> = _privateMessages.asStateFlow()

    // Separate PTT mode from actual mute state
    private val _isPttMode = MutableStateFlow(true) // true = PTT, false = voice activity
    val isPttMode: StateFlow<Boolean> = _isPttMode.asStateFlow()

    private val _isOutputMuted = MutableStateFlow(false)
    val isOutputMuted: StateFlow<Boolean> = _isOutputMuted.asStateFlow()

    // 输出设备列表与当前选择。和 _isLocalTalking 同理用镜像而不是 getter：
    // audioBridge 是可空的，Compose 需要一个"构造时就存在、之后还能更新"的流。
    private val _routeDevices = MutableStateFlow<List<AudioRouteDevice>>(emptyList())
    /** 可选的输出设备（扬声器 / 听筒 / 耳机 / 蓝牙），插拔时自动刷新 */
    val routeDevices: StateFlow<List<AudioRouteDevice>> = _routeDevices.asStateFlow()

    private val _routeSelectedId = MutableStateFlow(FOLLOW_SYSTEM)
    /** 当前选定的输出设备 id；[FOLLOW_SYSTEM] = 跟随系统自动路由 */
    val routeSelectedId: StateFlow<Int> = _routeSelectedId.asStateFlow()

    /**
     * 未绑定服务时的回落流。**必须是同一个实例**：
     * 写成 `?: MutableStateFlow(false)` 会每次读都新建一个对象 —— Compose 每次重组
     * 都拿到不同的 StateFlow，于是不停取消旧收集、开新收集、再重组，空转。
     * 悬浮窗在"未连接/已断开"时会一直走这条回落路径，等于持续空转。
     */
    private val idleVoiceActive = MutableStateFlow(false)

    /** 本地是否在说话（源头是 AudioBridge，未绑定时回落到 idleVoiceActive）。 */
    val isLocalVoiceActive: StateFlow<Boolean> get() = audioBridge?.isLocalVoiceActive ?: idleVoiceActive

    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    val connectionState: StateFlow<Int> = _connectionState.asStateFlow()

    // Unread message counters
    private val _unreadChannel = MutableStateFlow(0)
    val unreadChannel: StateFlow<Int> = _unreadChannel.asStateFlow()
    private val _unreadPrivate = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val unreadPrivate: StateFlow<Map<Int, Int>> = _unreadPrivate.asStateFlow()

    /** 输出通道：false = 媒体策略（默认），true = 通信策略 */
    val useCommunicationChannel: StateFlow<Boolean> = settingsStore.useCommunicationChannel
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 通话是否独占声音（false = 和音乐并存）*/
    val exclusiveAudio: StateFlow<Boolean> = settingsStore.exclusiveAudio
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val audioGain: StateFlow<Float> = settingsStore.audioGain
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    /** 说话圈门限（dBFS，默认 -40）：设置页可调，改了立刻生效 */
    val ringThresholdDb: StateFlow<Float> = settingsStore.ringThresholdDb
        .stateIn(viewModelScope, SharingStarted.Eagerly, -40f)

    val showLinkThumbnails: StateFlow<Boolean> = settingsStore.showLinkThumbnails
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val autoLoadImages: StateFlow<Boolean> = settingsStore.autoLoadImages
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val enableFloatingWindow: StateFlow<Boolean> = settingsStore.enableFloatingWindow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val noiseSuppression: StateFlow<Boolean> = settingsStore.noiseSuppression
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // File manager state
    /** Permission hints for the current channel (bitflags from Channel.PERM_*) */
    val currentChannelPermissions: StateFlow<Long> = combine(_channels, _rawUsers) { channels, users ->
        val myId = tsClient?.clientId ?: return@combine 0L
        val channelId = users.find { it.id == myId }?.channelId ?: return@combine 0L
        channels.find { it.id == channelId }?.permissionHints ?: 0L
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    // Track which channels we've already queried permissions for
    private val queriedPermChannels = mutableSetOf<Long>()

    // Track chat visibility to avoid incrementing unread for visible tab
    var isChatOpen = false
    var activeChatTab = 0
    var activePmUserId: Int? = null

    private var bound = false

    init {
        // Combine raw users + talking set to produce patched user list
        viewModelScope.launch {
            combine(_rawUsers, _talkingUserIds, _isLocalTalking) { users, talking, localTalking ->
                val myId = tsClient?.clientId
                
                users.map { user ->
                    val isLocallyTalking = (user.id == myId && localTalking)
                    // 远端圈：判据 = 解码后的真实音频能量（AudioBridge 算 dBFS + 250ms 保持）。
                    // 不要再用"最近 300ms 收到语音包"：对方客户端开麦/环境噪声时它会一直发流，
                    // 圈就会常亮、不跟着人动（实测踩过）。talk_status_* 事件也不能用（一次性）。
                    val shouldBeTalking = isLocallyTalking || talking.contains(user.id)
                    
                    if (shouldBeTalking && !user.isTalking) user.withTalking(true)
                    else if (!shouldBeTalking && user.isTalking) user.withTalking(false)
                    else user
                }
            }.collect { _users.value = it }
        }
        // When current channel changes, query permissions if not already known
        viewModelScope.launch {
            combine(_channels, _rawUsers) { channels, users ->
                val myId = tsClient?.clientId
                if (myId == null) return@combine null
                val channelId = users.find { it.id == myId }?.channelId ?: return@combine null
                val hints = channels.find { it.id == channelId }?.permissionHints ?: 0L
                channelId to hints
            }
                // 只关心 (channelId, hints) 的变化：上游每次刷新都送新对象，
                // 不去重的话这里会被拖着每帧跑一遍（曾把主线程拖到 30% CPU）
                .distinctUntilChanged()
                .collect { pair ->
                val (channelId, hints) = pair ?: return@collect
                if (hints == 0L && channelId !in queriedPermChannels) {
                    queriedPermChannels.add(channelId)
                    Log.i(TAG, "No permission hints for channel $channelId, querying permoverview...")
                    try {
                        tsClient?.queryChannelPermissions(channelId)
                    } catch (e: Exception) {
                        Log.e(TAG, "queryChannelPermissions failed", e)
                    }
                }
            }
        }
    }

    fun bindToService() {
        if (bound) return
        
        viewModelScope.launch {
            var attempts = 0
            while (TsConnectionService.instance == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }
            
            val service = TsConnectionService.instance
            if (service == null) {
                Log.e(TAG, "Failed to bind to TsConnectionService: instance is null")
                return@launch
            }

            tsClient = service.tsClient
            audioBridge = service.audioBridge
            audioBridge?.setMutedUserIds(_mutedUserIds.value)
            audioBridge?.gateTransmissionByVoiceActivity = !_isPttMode.value
            // 恢复上次选的输出设备（设备已不在时 AudioRouteManager 会自动回退跟随系统）
            restoreRouteDevice()
            // initialize() 在 service 里比这里先跑，已经把焦点抢了 —— 这里同步开关，
            // 若存的是"并存"，setter 会顺带把手里的焦点放掉
            audioBridge?.exclusiveAudio = settingsStore.exclusiveAudio.first()
            audioBridge?.useCommunicationChannel = settingsStore.useCommunicationChannel.first()
            connectionService = service
            queriedPermChannels.clear()

            viewModelScope.launch {
                service.tsClient.channels.collect { channels ->
                    Log.d(TAG, "Channels updated: ${channels.size}")
                    _channels.value = channels
                    loadChannelIcons(channels)
                }
            }
            viewModelScope.launch {
                service.tsClient.users.collect {
                    _rawUsers.value = it
                    loadAvatars(it)
                }
            }
            viewModelScope.launch {
                var bookmarkUpdated = false
                service.tsClient.serverInfo.collect { info ->
                    _serverInfo.value = info
                    if (info != null && !bookmarkUpdated) {
                        bookmarkUpdated = true
                        val addr = serverAddress ?: service.tsClient.serverAddress ?: ""
                        if (addr.isNotEmpty()) {
                            bookmarkStore.updateServerInfo(addr, info.name, info.iconId)
                            // Download server icon if needed
                            if (info.iconId != 0L) {
                                iconCache.loadIcon(info.iconId, service.tsClient)
                            }
                        }
                    }
                }
            }
            viewModelScope.launch {
                service.tsClient.state.collect { _connectionState.value = it }
            }
            // 消费端原来跑在主线程（viewModelScope 默认 Main），主线程一忙（Compose 重组、
            // 布局、GC）就消费不过来，通道缓冲攒满 64 条后新事件被静默丢掉 —— 丢的可能就是
            // text_message。解析是纯逻辑、状态写入是 StateFlow（线程安全），所以把消费挪到
            // Default 线程，让主线程不再成为这条链路的瓶颈。
            //
            // ⚠️ 必须用 launch(Dispatchers.Default)，**不能**写成 events.flowOn(...)：
            //    flowOn 对 SharedFlow 不生效（Operator Fusion，SharedFlow 没有上游），
            //    Kotlin 会以 deprecation 报错；即使压掉警告也是静默无效，消费仍在主线程。
            viewModelScope.launch(Dispatchers.Default) {
                service.tsClient.events.collect { handleEvent(it) }
            }
            viewModelScope.launch {
                service.tsClient.commandErrors.collect { message ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            // Load persisted messages
            viewModelScope.launch {
                val addr = service.tsClient.serverAddress
                if (!addr.isNullOrEmpty()) {
                    serverAddress = addr
                    val (channelMsgs, privateMsgs) = messageStore.load(addr)
                    if (channelMsgs.isNotEmpty()) _channelMessages.value = channelMsgs.map { migrateMessage(it) }
                    if (privateMsgs.isNotEmpty()) _privateMessages.value = privateMsgs.mapValues { (_, msgs) -> msgs.map { migrateMessage(it) } }
                }
            }
            // Start audio capture if not already running
            if (!service.audioBridge.isCapturing.value) {
                service.audioBridge.startCapture(viewModelScope, noiseSuppression.value)
            }
            // Apply persisted audio gain
            service.audioBridge.gainFactor = audioGain.value
            // Observe audio gain changes and apply live
            viewModelScope.launch {
                audioGain.collect { gain ->
                    service.audioBridge.gainFactor = gain
                }
            }
            // 说话圈：门限（落盘值）→ 音频桥；"谁在说话"（能量判据）→ 频道树的圈
            service.audioBridge.setRingThresholdDb(ringThresholdDb.value.toDouble())
            viewModelScope.launch {
                ringThresholdDb.collect { db -> service.audioBridge.setRingThresholdDb(db.toDouble()) }
            }
            viewModelScope.launch {
                service.audioBridge.speakingUserIds.collect { ids -> _talkingUserIds.value = ids }
            }
            // Observe audio state for local talking status
            viewModelScope.launch {
                service.audioBridge.isLocalVoiceActive.collect { _isLocalTalking.value = it }
            }
            viewModelScope.launch {
                service.audioBridge.isOutputMuted.collect { _isOutputMuted.value = it }
            }
            // 输出设备：列表随插拔刷新，选中项跟着 AudioBridge 走
            viewModelScope.launch {
                service.audioBridge.routeDevices.collect { _routeDevices.value = it }
            }
            viewModelScope.launch {
                service.audioBridge.routeSelectedId.collect { _routeSelectedId.value = it }
            }

            // Start event loop (guarded by AtomicBoolean — safe if already running)
            service.tsClient.startEventLoop()
            
            bound = true
        }
    }

    private fun loadChannelIcons(channels: List<Channel>) {
        val client = tsClient ?: return
        val iconIds = channels.mapNotNull { ch ->
            if (ch.iconId != 0L) ch.iconId else null
        }.distinct()

        for (iconId in iconIds) {
            if (iconCache.getIcon(iconId) != null) {
                // Already in memory, make sure it's in the flow
                continue
            }
            viewModelScope.launch {
                iconCache.loadIcon(iconId, client)
                val icon = iconCache.getIcon(iconId)
                if (icon != null) {
                    _channelIcons.value = _channelIcons.value + (iconId to icon)
                }
            }
        }
        // Also emit icons already cached in memory
        val cached = mutableMapOf<Long, ImageBitmap>()
        for (iconId in iconIds) {
            iconCache.getIcon(iconId)?.let { cached[iconId] = it }
        }
        if (cached.isNotEmpty() && cached != _channelIcons.value) {
            _channelIcons.value = _channelIcons.value + cached
        }
    }

    private fun loadAvatars(users: List<User>) {
        val client = tsClient ?: return
        for (user in users) {
            val uid = user.uid ?: continue
            if (uid.isEmpty()) continue
            if (user.isQuery) continue
            if (user.avatarId.isNullOrEmpty()) continue  // no avatar on server
            if (avatarCache.getAvatar(uid) != null) continue
            if (avatarCache.hasNoAvatar(uid)) continue
            Log.d(TAG, "loadAvatars: launching download for ${user.nickname}")
            viewModelScope.launch {
                avatarCache.loadAvatar(uid, client)
                val avatar = avatarCache.getAvatar(uid)
                if (avatar != null) {
                    _avatars.value = _avatars.value + (uid to avatar)
                    Log.i(TAG, "loadAvatars: avatar loaded for ${user.nickname}")
                }
            }
        }
    }

    private fun handleEvent(event: Event) {
        try {
            when (event.type) {
                // talk_status_start / talk_status_stop 不再用于圈：
                // 该事件是一次性的（连接瞬间爆发一次就没了），且判据是"收到语音包"不是"有声音"。
                // 现在由 AudioBridge.speakingUserIds（解码 PCM 能量）统一驱动。
                // 消息本身交给 MessageParser（纯逻辑、可单测），这里只负责把结果落进状态。
                "text_message" -> applyParsedMessage(
                    MessageParser.parse(event, tsClient?.clientId) { Log.d(TAG, it) },
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleEvent", e)
        }
    }

    /**
     * 把归约出来的消息落进状态。副作用只在这一层：
     * 追加到对应列表 → 落盘 → 未读计数（正开着对应 tab 时不加未读）。
     */
    private fun applyParsedMessage(parsed: ParsedMessage?) {
        when (parsed) {
            null -> Unit
            is ParsedMessage.Channel -> {
                _channelMessages.value = _channelMessages.value + parsed.message
                scheduleSave()
                // Only increment if chat is closed or not on channel tab
                if (!isChatOpen || activeChatTab != 0) {
                    _unreadChannel.value = _unreadChannel.value + 1
                }
            }
            is ParsedMessage.Private -> {
                val id = parsed.userId
                val current = _privateMessages.value.toMutableMap()
                current[id] = (current[id] ?: emptyList()) + parsed.message
                _privateMessages.value = current
                scheduleSave()
                // Only increment if chat is closed or not on this user's PM
                if (!isChatOpen || activeChatTab != 1 || activePmUserId != id) {
                    val unread = _unreadPrivate.value.toMutableMap()
                    unread[id] = (unread[id] ?: 0) + 1
                    _unreadPrivate.value = unread
                }
            }
        }
    }

    /** Re-parse old saved messages that contain ts3file:// but have no fileAttachment. */
    private fun migrateMessage(msg: ChatMessage): ChatMessage {
        if (msg.fileAttachment != null) return msg
        val attachment = MessageParser.parseFileAttachment(msg.text) ?: return msg
        return msg.copy(text = attachment.fileName, fileAttachment = attachment)
    }

    fun setChatState(open: Boolean, tab: Int, pmUserId: Int? = null) {
        isChatOpen = open
        activeChatTab = tab
        activePmUserId = pmUserId
        // Clear unread for the now-visible tab
        if (open) {
            if (tab == 0) clearUnreadChannel()
            else if (tab == 1 && pmUserId != null) clearUnreadPrivateUser(pmUserId)
        }
    }

    fun clearUnreadChannel() { _unreadChannel.value = 0 }
    fun clearUnreadPrivateUser(userId: Int) {
        val unread = _unreadPrivate.value.toMutableMap()
        unread.remove(userId)
        _unreadPrivate.value = unread
    }

    fun sendChannelMessage(text: String) {
        if (text.isBlank()) return
        tsClient?.sendChannelMessage(text)
        _channelMessages.value = _channelMessages.value + ChatMessage(
            sender = getApplication<Application>().getString(R.string.me_sender), text = text, isMe = true,
        )
        scheduleSave()
    }

    fun sendPrivateMessage(userId: Int, text: String) {
        if (text.isBlank()) return
        tsClient?.sendPrivateMessage(userId, text)
        val msg = ChatMessage(
            sender = getApplication<Application>().getString(R.string.me_sender), text = text, isMe = true, isPrivate = true, senderId = userId,
        )
        val current = _privateMessages.value.toMutableMap()
        current[userId] = (current[userId] ?: emptyList()) + msg
        _privateMessages.value = current
        scheduleSave()
    }

    fun moveToChannel(channelId: Long) {
        tsClient?.moveToChannel(channelId)
    }

    /**
     * 拖动增益滑块期间只改内存值（立即生效、**不落盘**）。
     *
     * 拖一次滑块会触发几十次 onValueChange，每次都写 DataStore 是纯浪费 IO；
     * 松手时才走 [setAudioGain] 落盘。UI 在拖动期间用本地值显示，避免和
     * DataStore 里的旧值来回打架。
     */
    fun setAudioGainLive(gain: Float) {
        audioBridge?.gainFactor = gain
    }

    fun setAudioGain(gain: Float) {
        audioBridge?.gainFactor = gain
        viewModelScope.launch { settingsStore.setAudioGain(gain) }
    }

    fun setShowLinkThumbnails(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setShowLinkThumbnails(enabled) }
    }

    fun setAutoLoadImages(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoLoadImages(enabled) }
    }

    fun setEnableFloatingWindow(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setEnableFloatingWindow(enabled) }
    }

    fun setNoiseSuppression(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNoiseSuppression(enabled) }
    }

    fun toggleVoiceMode() {
        val newPttMode = !_isPttMode.value
        _isPttMode.value = newPttMode
        // When switching to PTT mode, mute. When switching to VA, unmute.
        audioBridge?.setMuted(newPttMode)
        // VA 模式：发送要过 VAD 门（静音时不编码不上行）；PTT 模式按住就发，不门控
        audioBridge?.gateTransmissionByVoiceActivity = !newPttMode
    }

    fun toggleOutputMute() {
        audioBridge?.toggleOutputMute()
    }

    /**
     * 通话独占声音开关。
     * 开：抢焦点，音乐被暂停（原有行为）；关：不抢，两者并存。
     */
    /**
     * 切换输出通道。
     * 开：通信策略（STRATEGY_PHONE）—— 独立 output thread，切设备不影响音乐，
     *     音量键改控通话音量；代价是音质（窄带 + AEC）且设备候选里没有 A2DP。
     * 关：媒体策略（STRATEGY_MEDIA）—— 原行为。
     */
    fun setUseCommunicationChannel(enabled: Boolean) {
        audioBridge?.useCommunicationChannel = enabled
        viewModelScope.launch { settingsStore.setUseCommunicationChannel(enabled) }
    }

    fun setExclusiveAudio(enabled: Boolean) {
        audioBridge?.exclusiveAudio = enabled
        viewModelScope.launch { settingsStore.setExclusiveAudio(enabled) }
    }

    /**
     * 选择通话音频的输出设备。传 [FOLLOW_SYSTEM] 交回系统自动路由。
     *
     * 只有真的选到了设备才落盘 —— 否则会把一次失败的点击记成"跟随系统"，
     * 下次通话就悄悄把用户的选择清掉了。
     */
    fun selectRouteDevice(id: Int) {
        val dev = audioBridge?.selectRouteDevice(id)
        when {
            dev != null -> viewModelScope.launch {
                settingsStore.setRouteDevice(dev.id, dev.type, dev.productName)
            }
            id == FOLLOW_SYSTEM -> viewModelScope.launch {
                settingsStore.setRouteDevice(FOLLOW_SYSTEM, 0, "")
            }
        }
    }

    /**
     * 把落盘的设备选择恢复到 AudioBridge。
     *
     * 除了 id 还存 type + 商品名：蓝牙耳机重连后 id 会变，只认 id 会认不出那副耳机。
     * 认不出来时 AudioRouteManager 回退"跟随系统"，不会哑掉。
     */
    private suspend fun restoreRouteDevice() {
        val id = settingsStore.routeDeviceId.first()
        val type = settingsStore.routeDeviceType.first()
        val name = settingsStore.routeDeviceName.first()
        audioBridge?.restoreRouteDevice(id, type, name)
    }

    fun toggleMuteUser(clientId: Int) {
        val updated = if (clientId in _mutedUserIds.value) {
            _mutedUserIds.value - clientId
        } else {
            _mutedUserIds.value + clientId
        }
        _mutedUserIds.value = updated
        audioBridge?.setMutedUserIds(updated)
    }

    fun setPushToTalk(pressed: Boolean) {
        // Only changes mute state, NOT isPttMode — avoids UI recomposition swap
        audioBridge?.setMuted(!pressed)
    }

    private fun currentChannelId(): Long {
        val myId = tsClient?.clientId ?: return 0
        return _rawUsers.value.find { it.id == myId }?.channelId ?: 0
    }

    // ------------------------------ 文件传输 ------------------------------
    // 实现全在 FileTransferController（批次4 从本类抽出）。这里只做转发：
    // ① UI 的调用点一行不用改；② "把消息插进聊天流"这类跨模块动作留在 ViewModel 里。

    private val fileTransfer = FileTransferController(
        appContext = getApplication(),
        scope = viewModelScope,
        client = { tsClient },
        channelId = { currentChannelId() },
        serverAddress = { serverAddress },
    )

    val fileManagerOpen: StateFlow<Boolean> get() = fileTransfer.fileManagerOpen
    val fileList: StateFlow<List<TsFileEntry>> get() = fileTransfer.fileList
    val currentFilePath: StateFlow<String> get() = fileTransfer.currentFilePath
    val fileManagerLoading: StateFlow<Boolean> get() = fileTransfer.fileManagerLoading
    val previewImageBytes: StateFlow<ByteArray?> get() = fileTransfer.previewImageBytes
    val previewImageName: StateFlow<String?> get() = fileTransfer.previewImageName

    fun toggleFileManager() = fileTransfer.toggleFileManager()
    fun closeFileManager() = fileTransfer.closeFileManager()
    fun refreshFileList() = fileTransfer.refreshFileList()
    fun navigateToFolder(folderName: String) = fileTransfer.navigateToFolder(folderName)
    fun navigateUp() = fileTransfer.navigateUp()
    fun deleteFileInChannel(name: String) = fileTransfer.deleteFile(name)
    fun renameFileInChannel(oldName: String, newName: String) = fileTransfer.renameFile(oldName, newName)
    fun createDirectoryInChannel(dirName: String) = fileTransfer.createDirectory(dirName)
    fun uploadFileToChannel(fileName: String, data: ByteArray) = fileTransfer.uploadToCurrentDirectory(fileName, data)
    fun downloadFileFromManager(fileName: String) = fileTransfer.downloadFromManager(fileName)
    fun downloadAttachment(attachment: FileAttachment): StateFlow<DownloadState> = fileTransfer.downloadAttachment(attachment)
    fun previewImageFile(fileName: String) = fileTransfer.previewImage(fileName)
    fun closePreview() = fileTransfer.closePreview()

    /**
     * 上传文件到当前频道，并把它作为一条聊天消息发出去。
     * 文件部分交给控制器，消息部分留在这 —— 两边各自只碰自己那份状态。
     */
    fun uploadAndSendFile(fileName: String, data: ByteArray, isPrivate: Boolean, targetId: Int?) {
        val ch = currentChannelId()
        if (ch == 0L) return
        viewModelScope.launch {
            if (!fileTransfer.upload(ch, "/$fileName", data)) return@launch
            val url = fileTransfer.buildTs3FileUrl(ch, fileName, data.size.toLong())
            appendFileMessage(fileName, data.size.toLong(), url, isPrivate, targetId)
        }
    }

    /** 分享文件管理器里已有的文件：不重复上传，只把链接发到聊天 */
    fun shareFile(targetUserId: Int?, fileName: String, fileSize: Long) {
        val ch = currentChannelId()
        if (ch == 0L) return
        val url = fileTransfer.buildTs3FileUrl(ch, fileName, fileSize, fileTransfer.currentFilePath.value)
        appendFileMessage(fileName, fileSize, url, targetUserId != null, targetUserId)
    }

    /** 把"我发了个文件"这条消息插进聊天流并发往服务器（附带落盘）。 */
    private fun appendFileMessage(
        fileName: String,
        fileSize: Long,
        ts3Url: String,
        isPrivate: Boolean,
        targetId: Int?,
    ) {
        val meSender = getApplication<Application>().getString(R.string.me_sender)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val isImage = ext in IMAGE_EXTENSIONS
        val attachment = FileAttachment(fileName, fileSize, fileId = "", isImage, channelId = currentChannelId())
        if (isPrivate && targetId != null) {
            tsClient?.sendPrivateMessage(targetId, ts3Url)
            val current = _privateMessages.value.toMutableMap()
            current[targetId] = (current[targetId] ?: emptyList()) +
                ChatMessage(sender = meSender, text = fileName, isMe = true, isPrivate = true, senderId = targetId, fileAttachment = attachment)
            _privateMessages.value = current
        } else {
            tsClient?.sendChannelMessage(ts3Url)
            _channelMessages.value = _channelMessages.value +
                ChatMessage(sender = meSender, text = fileName, isMe = true, fileAttachment = attachment)
        }
        scheduleSave()
    }

    fun disconnect() {
        saveNow()
        queriedPermChannels.clear()
        connectionService?.disconnect()
    }

    override fun onCleared() {
        saveNow()
        bound = false
        tsClient = null
        audioBridge = null
        connectionService = null
        _routeDevices.value = emptyList()
        _routeSelectedId.value = FOLLOW_SYSTEM
        super.onCleared()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(2000)
            saveNow()
        }
    }

    private fun saveNow() {
        saveJob?.cancel()
        val addr = serverAddress ?: return
        messageStore.save(addr, _channelMessages.value, _privateMessages.value)
    }
}

/** Create a copy of User with isTalking changed (User fields are final). */
private fun User.withTalking(talking: Boolean): User = User(
    id, uid, databaseId, channelId, nickname, clientType,
    talking, isInputMuted, isOutputMuted, hasInputHardware, hasOutputHardware,
    isAway, isRecording, isPrioritySpeaker, isChannelCommander, isTalker,
    talkPower, awayMessage, serverGroups, channelGroup,
    platform, version, country, description, avatarId, iconId,
)
