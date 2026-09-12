package dev.tsdroid.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import dev.tslib.AudioConfig
import dev.tslib.OpusCodec
import dev.tsdroid.bridge.audio.JitterBuffer
import dev.tsdroid.bridge.audio.VOICE_HANGOVER_MS
import dev.tsdroid.bridge.audio.dbfsOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class AudioBridge(
    private val context: Context,
    private val tsClient: TsClient,
) {
    companion object {
        private const val TAG = "AudioBridge"
        const val SAMPLE_RATE = 48000
        const val CODEC_OPUS_VOICE = 4
        private const val FRAME_SIZE_MS = 20
        /**
         * 被永久抢走焦点（AUDIOFOCUS_LOSS）后隔多久重抢。
         * 太短会和音乐 App 反复互抢，太长则语音哑太久 —— 800ms 是折中，
         * 实测不合适直接改这一个数就行。
         */
        private const val FOCUS_RETRY_DELAY_MS = 800L
        private const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000 // 960
        private const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2 // 16-bit PCM = 2 bytes/sample
        // AudioTrack 硬件缓冲（上限，不是常驻延迟）：给它足够空间吸收突发
        private const val PLAYBACK_BUFFER_FRAMES = 8
        // 真正决定延迟的是"在飞帧数"目标：3 帧 ≈ 60ms。太小会 underrun，太大会迟滞（像被拉长）
        private const val PLAYBACK_TARGET_FRAMES = 3
        // ── 说话圈（判据 = 解码后真实音频能量，不是"收到包"）──
        private const val RING_THRESHOLD_DB_DEFAULT = -40.0  // 门限默认 -40 dBFS
        private const val RING_HANGOVER_MS = VOICE_HANGOVER_MS  // 静音这么久才灭圈（防逐字闪）；值与门控保持时间同源
        private const val RING_PUBLISH_MIN_MS = 40L          // 状态最多 25Hz 推一次
        const val RING_THRESHOLD_DB_MIN = -60.0
        const val RING_THRESHOLD_DB_MAX = -15.0
    }

    private val audioConfig = AudioConfig()

    // Encoder for capture (our mic)
    private var encoder: OpusCodec? = null

    // Per-user decoders and raw opus queues for playback mixing
    private val userDecoders = ConcurrentHashMap<Int, OpusCodec>()
    /** 每用户抖动缓冲：按包号去重 + 重排 + 缺包隐藏 */
    private val userJitter = ConcurrentHashMap<Int, JitterBuffer>()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    // 已写入 AudioTrack 的帧总数（仅播放线程读写），配合播放头算"在飞帧数"
    private var writtenFrames: Long = 0L
    // 解码复用缓冲：只在播放线程（decodePacket）使用
    private val decodeBuffer = ShortArray(FRAME_SIZE_SAMPLES)

    // ── 诊断计数（每秒打印一次）──
    @Volatile private var recvFrames = 0L
    @Volatile private var playedFrames = 0L
    /** 诊断：播放循环外层迭代次数（不涨=循环死了） */
    @Volatile private var loopTicks = 0L
    /** 诊断：write() 抛异常次数 */
    @Volatile private var writeErrors = 0L
    /** 诊断：playbackHeadPosition 读取失败次数 */
    @Volatile private var headErrors = 0L
    /** 诊断：本实例创建过几个 AudioTrack（>1 说明被重复 init） */
    @Volatile private var trackCreates = 0
    private var statsJob: Job? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    // Dedicated single-thread scope for audio playback
    // 播放必须跑在实时优先级线程上：普通优先级 + 小缓冲 => 一被 GC/省电挤到就丢音（卡）
    private val playbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ts3-audio-out").apply {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
    }

    private val playbackScope = CoroutineScope(
        SupervisorJob() + playbackExecutor.asCoroutineDispatcher()
    )

    @Volatile
    var gainFactor: Float = 1.0f

    @Volatile
    private var mutedUserIds: Set<Int> = emptySet()

    private val _isMuted = MutableStateFlow(true) // Start muted (PTT default)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isOutputMuted = MutableStateFlow(false)
    val isOutputMuted: StateFlow<Boolean> = _isOutputMuted.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isLocalVoiceActive = MutableStateFlow(false)
    val isLocalVoiceActive: StateFlow<Boolean> = _isLocalVoiceActive.asStateFlow()

    // ── 说话圈：谁"真的有声音"（按解码后的 PCM 能量，不是"收到包"）──
    @Volatile private var ringThresholdDb: Double = RING_THRESHOLD_DB_DEFAULT
    /** userId -> 最近一次电平超过门限的时刻（墙钟 ms） */
    private val userAudibleAt = ConcurrentHashMap<Int, Long>()
    /** userId -> 平滑后的电平（dBFS），用于挑"最响的那个"和诊断 */
    private val _speakingUserIds = MutableStateFlow<Set<Int>>(emptySet())
    /** 当前"在说话"的用户（能量判据 + 250ms 保持） */
    val speakingUserIds: StateFlow<Set<Int>> = _speakingUserIds.asStateFlow()
    private val _loudestSpeakerId = MutableStateFlow<Int?>(null)
    /** 当前最响的说话人（悬浮窗用它决定显示谁） */
    val loudestSpeakerId: StateFlow<Int?> = _loudestSpeakerId.asStateFlow()
    private var ringPublishLastMs = 0L
    /** 本地（自己）麦克风最近一次超门限的时刻 */
    @Volatile private var localAudibleAtMs = 0L

    /**
     * VA（语音激活）模式下的发送门控：
     *  · false —— PTT 模式：按住就是要发，不做门控（保持原行为）
     *  · true  —— VA 模式：静音时不编码、不上行，只有判定"在说话"才发
     *
     * 由 ViewModel 按当前模式设置。模式本身的唯一真相在 ViewModel 的 isPttMode，
     * 这里只接收它的结论，避免同一件事有两处真相。
     */
    @Volatile
    var gateTransmissionByVoiceActivity: Boolean = false

    // ── 音频焦点：防止被其他 App（音乐/导航）duck 或抢占 ──
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    /** 重抢焦点用的主线程 Handler + 代次令牌（多次 LOSS 堆叠时只让最新那次生效） */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var focusRetryToken = 0

    private fun requestAudioFocus() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        // ⚠️ AUDIOFOCUS_LOSS 是**永久**失去，和 LOSS_TRANSIENT 语义完全不同：
                        //    系统此后**不会再回调 GAIN**，只把它当"静音一下"就会永远哑着。
                        //    而放视频/听歌触发的正是它 —— 音乐类 App 请求的是持久焦点
                        //    AUDIOFOCUS_GAIN，两个 GAIN 相撞、后到的赢，我们先被踢出局。
                        //    （实测：通话中放音乐 → TS3 被静音且再也不恢复。）
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            _isOutputMuted.value = true
                            retryAudioFocus()
                        }
                        // 暂时失去（来电、闹钟等）：静音等着，系统稍后会回调 GAIN
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> _isOutputMuted.value = true
                        // 拿回焦点 / 对方只是要求我们 duck（压低音量即可，照常出声）
                        AudioManager.AUDIOFOCUS_GAIN,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> _isOutputMuted.value = false
                    }
                }
                .build()
            audioFocusRequest = req
            Log.i(TAG, "AudioFocus request result=" + audioManager.requestAudioFocus(req))
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus failed", e)
        }
    }

    /**
     * 把音频焦点抢回来。
     *
     * 为什么必须有它：AUDIOFOCUS_LOSS 之后系统不会再还焦点，而"通话中放音乐/视频"必然触发它。
     * 延迟一小会儿再抢 —— 给对方一个收到 LOSS 并暂停的机会（音乐类 App 一般会暂停），
     * 也避免同一瞬间反复互抢。
     *
     * 抢回来之后我们恢复出声，而音乐那边收到 LOSS 会自己停 —— 这正好就是
     * "通话中语音优先"想要的效果（不需要改 USAGE 去跟系统要 ducking）。
     */
    private fun retryAudioFocus() {
        val token = ++focusRetryToken
        mainHandler.postDelayed({
            if (token != focusRetryToken) return@postDelayed   // 期间又来了新的 LOSS，交给新那次处理
            val req = audioFocusRequest ?: return@postDelayed
            try {
                val result = audioManager.requestAudioFocus(req)
                Log.i(TAG, "音频焦点重抢 result=$result")
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    _isOutputMuted.value = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "音频焦点重抢失败", e)
            }
        }, FOCUS_RETRY_DELAY_MS)
    }

    private fun abandonAudioFocus() {
        try {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {
        }
        audioFocusRequest = null
    }

    fun initialize() {
        Log.i(TAG, "initialize(): 旧轨道=${audioTrack?.hashCode()} 本实例已建轨道数=$trackCreates")
        try {
            // Explicitly release any old audio stream resources if lingering
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

            encoder = OpusCodec(audioConfig)
            startAudioStats()
            requestAudioFocus()
            initAudioTrack()
            startPlaybackLoop()
            Log.i(TAG, "initialize() 完成: track=${audioTrack?.hashCode()} state=${audioTrack?.state} " +
                    "playState=${audioTrack?.playState} head=${audioTrack?.playbackHeadPosition}")
        } catch (e: Exception) {
            android.util.Log.e("TS6_DEBUG", "Caught audio initialization friction safely", e)
            // We don't throw here to prevent JE_AppCustomException
        }
    }

    @SuppressLint("MissingPermission")
    fun startCapture(scope: CoroutineScope, noiseSuppressionEnabled: Boolean = true) {
        if (_isCapturing.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot start capture: RECORD_AUDIO permission is missing")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_SIZE_BYTES * 4),
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord is not initialized")
            record.release()
            return
        }
        try {
            record.startRecording()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start microphone capture", e)
            record.release()
            return
        }
        audioRecord = record
        _isCapturing.value = true
        noiseSuppressor?.release()
        noiseSuppressor = null
        if (noiseSuppressionEnabled && NoiseSuppressor.isAvailable()) {
            try {
                NoiseSuppressor.create(record.audioSessionId)?.also {
                    noiseSuppressor = it
                    Log.i(TAG, "NoiseSuppressor enabled (session=${record.audioSessionId})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create NoiseSuppressor", e)
            }
        } else {
            Log.i(TAG, "NoiseSuppressor skipped: enabled=$noiseSuppressionEnabled, available=${NoiseSuppressor.isAvailable()}")
        }

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(FRAME_SIZE_SAMPLES)
            val codec = encoder ?: run {
                Log.e(TAG, "Cannot start capture: Opus encoder is not initialized")
                _isCapturing.value = false
                return@launch
            }
            while (isActive && _isCapturing.value) {
                val read = try {
                    audioRecord?.read(buffer, 0, FRAME_SIZE_SAMPLES) ?: break
                } catch (e: Throwable) {
                    Log.e(TAG, "Microphone read failed", e)
                    break
                }
                if (read < 0) {
                    Log.e(TAG, "Microphone read returned error $read")
                    break
                }
                if (read == FRAME_SIZE_SAMPLES && !_isMuted.value) {
                    // 本地圈用同一把尺子（同一个门限值）：麦克风 dBFS + 250ms 保持
                    // 本地圈仍按麦克风能量：原始 PCM 本来就在手上，不必额外解码，
                    // 而且和下面的 VA 发送门控共用同一个门限 —— 两边行为严格一致。
                    // 公式与设置页"麦克风测试"、远端能量判据都是同一个 dbfsOf。
                    val micDb = dbfsOf(buffer, read)
                    val nowMs = System.currentTimeMillis()
                    if (micDb > ringThresholdDb) localAudibleAtMs = nowMs
                    val isVoiceActive = nowMs - localAudibleAtMs <= RING_HANGOVER_MS
                    _isLocalVoiceActive.value = isVoiceActive
                    
                    // VA 模式：判定"没在说话"就整帧跳过 —— 不编码也不上行。
                    // 麦克风保持采集（要靠它测电平、等下一次开口），只停编码与发送。
                    if (shouldTransmitFrame(gateTransmissionByVoiceActivity, isVoiceActive)) {
                        val pcmBytes = shortsToBytes(buffer)
                        try {
                            val encoded = codec.encode(pcmBytes)
                            tsClient.sendAudio(encoded, CODEC_OPUS_VOICE)
                        } catch (_: Exception) {}
                    }
                } else {
                    _isLocalVoiceActive.value = false
                }
            }
            _isCapturing.value = false
            _isLocalVoiceActive.value = false
            val finishedRecord = audioRecord
            audioRecord = null
            noiseSuppressor?.release()
            noiseSuppressor = null
            try {
                finishedRecord?.stop()
            } catch (_: Throwable) {
            }
            try {
                finishedRecord?.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun stopCapture() {
        _isCapturing.value = false
        captureJob?.cancel()
        captureJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        noiseSuppressor?.release()
        noiseSuppressor = null
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            // 缓冲加深到 8 帧(≈160ms)：抖动缓冲；不再强制 LOW_LATENCY（它会压小缓冲导致 underrun）
            .setBufferSizeInBytes(maxOf(minBuf, FRAME_SIZE_BYTES * PLAYBACK_BUFFER_FRAMES))
            .build()
        writtenFrames = 0L
        userJitter.values.forEach { it.reset() }
        trackCreates++
        val t = audioTrack
        Log.i(TAG, "AudioTrack 新建 #$trackCreates: state=${t?.state} sess=${t?.audioSessionId} " +
                "minBuf=$minBuf bufBytes=${maxOf(minBuf, FRAME_SIZE_BYTES * PLAYBACK_BUFFER_FRAMES)} " +
                "bufFrames=${t?.bufferSizeInFrames} hash=${t?.hashCode()}")
        try {
            t?.play()
            Log.i(TAG, "AudioTrack.play() → playState=${t?.playState} head=${t?.playbackHeadPosition}")
        } catch (e: Throwable) {
            Log.e(TAG, "AudioTrack.play() 抛异常", e)
        }
    }

    /**
     * 播放循环（输出时钟驱动）：
     *  - 每轮把队列里的帧尽量写出去（硬上限 = 硬件缓冲帧数，防积压）
     *  - 在飞帧数不足 PLAYBACK_TARGET_FRAMES 时补写静音 → 永不 underrun
     *  - 延迟 ≈ 目标帧数（3 帧 ≈ 60ms），而不是缓冲上限
     * 所有解码器访问都在这一个线程上，故无需对解码器加锁。
     */
    private fun startPlaybackLoop() {
        // 必须先停掉旧循环：重复 initialize() 时两个循环会同时写同一个音频轨
        playbackJob?.cancel()
        playbackJob = playbackScope.launch {
            val mixBuffer = ShortArray(FRAME_SIZE_SAMPLES)
            val frameNs = FRAME_SIZE_MS * 1_000_000L
            val loopStartNs = System.nanoTime()
            var nextSlotNs = loopStartNs
            Log.i(TAG, "播放循环启动 (track=${audioTrack?.hashCode()} state=${audioTrack?.state})")

            while (isActive) {
                loopTicks++
                // 协程可能在别的线程恢复，逐次确认实时优先级
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

                // 说话圈：每时隙判定一次（能量 + 250ms 保持），没人说话时也能把圈灭掉
                publishSpeakingRings()

                val nowNs = System.nanoTime()
                if (nowNs < nextSlotNs) {
                    // delay 只有 (Long) 和 (Duration) 两个重载：向上取整到 ms，靠绝对时隙自纠偏
                    delay((nextSlotNs - nowNs + 999_999L) / 1_000_000L)
                    continue
                }
                if (audioTrack == null) {
                    delay(FRAME_SIZE_MS.toLong())
                    continue
                }
                // 输出时钟 = 单调时间 + 已写帧数，**不依赖 playbackHeadPosition**：
                // 实测小米 17 Pro 上这个轨道头恒为 0（系统侧轨道被移走/重建），
                // 用它节流会让循环写满目标帧数后永久停摆 → produceTick 一次不被调用 → 没声音。
                val slotsDone = (nowNs - loopStartNs) / frameNs
                val inFlight = writtenFrames - slotsDone * FRAME_SIZE_SAMPLES
                if (inFlight < (PLAYBACK_BUFFER_FRAMES - 1).toLong() * FRAME_SIZE_SAMPLES) {
                    mixBuffer.fill(0)
                    // 出帧判定用的时间戳：一处取，别让抖动缓冲自己去摸系统时钟（那样没法测）
                    val nowMs = System.currentTimeMillis()
                    var hasAudio = false
                    for ((_, jb) in userJitter) {
                        // 抖动缓冲按包号出帧：可能返回真实帧、PLC 帧、或 null（还没起播）
                        val pcm = try {
                            jb.produceTick(nowMs)
                        } catch (e: Throwable) {
                            Log.e(TAG, "produceTick 失败(user=${jb.userId}): $e")
                            null
                        } ?: continue
                        hasAudio = true
                        for (i in mixBuffer.indices) {
                            val sum = mixBuffer[i].toInt() + pcm[i].toInt()
                            mixBuffer[i] = sum.coerceIn(
                                Short.MIN_VALUE.toInt(),
                                Short.MAX_VALUE.toInt(),
                            ).toShort()
                        }
                    }
                    // 永远写：没数据就是静音 —— 保证 AudioTrack 永不 underrun
                    writeFrame(mixBuffer)
                    if (hasAudio) playedFrames++
                }
                nextSlotNs += frameNs
                if (nextSlotNs < nowNs) nextSlotNs = nowNs + frameNs // 落后了就往前对齐，不追帧
            }
        }
    }

    /**
     * 每秒打印一次音频统计（logcat Tag=AudioBridge）：
     *   recv  = 从 Rust 层收到多少帧/秒（正常单人说话 ≈ 50）
     *   played= 实际播出去多少帧/秒
     *   dup   = 被判为重复丢弃多少帧/秒（>0 说明 Rust 层在重复投递）
     */
    private fun startAudioStats() {
        statsJob?.cancel()
        statsJob = playbackScope.launch {
            var lastRecv = 0L
            var lastPlayed = 0L
            var lastDup = 0L
            var lastLost = 0L
            var lastPlc = 0L
            var lastResync = 0L
            while (isActive) {
                delay(1000)
                val recvDelta = recvFrames - lastRecv
                val playedDelta = playedFrames - lastPlayed
                lastRecv = recvFrames; lastPlayed = playedFrames
                val dupTotal = userJitter.values.sumOf { it.dupDropped }
                val lostTotal = userJitter.values.sumOf { it.lostCount }
                val plcTotal = userJitter.values.sumOf { it.plcCount }
                val resyncTotal = userJitter.values.sumOf { it.resyncCount }
                val dupD = dupTotal - lastDup; val lostD = lostTotal - lastLost
                val plcD = plcTotal - lastPlc; val resyncD = resyncTotal - lastResync
                lastDup = dupTotal; lastLost = lostTotal; lastPlc = plcTotal; lastResync = resyncTotal
                val pend = userJitter.values.sumOf { it.pendingSize() }
                val head = try { audioTrack?.playbackHeadPosition?.toLong() ?: -1L } catch (_: Exception) { -1L }
                // 自适应抖动缓冲的当前档位（所有用户取最大目标深度）与抖动估计
                val jbTarget = userJitter.values.maxOfOrNull { it.targetSlots() } ?: 0
                val jitterMs = userJitter.values.maxOfOrNull { it.jitterMs() } ?: 0.0
                Log.i(TAG,
                    "audio stats: recv=${recvDelta}/s played=${playedDelta}/s dup=${dupD}/s lost=${lostD}/s plc=${plcD}/s " +
                        "resync=${resyncD}/s pending=$pend jb=${jbTarget}x20ms jit=${jitterMs.toInt()}ms " +
                        "buf=${bufferedFrames()}f written=$writtenFrames head=$head " +
                        "loop=$loopTicks werr=$writeErrors herr=$headErrors track=$trackCreates " +
                        "state=${try { audioTrack?.state } catch (_: Exception) { -9 }} " +
                        "sess=${try { audioTrack?.audioSessionId } catch (_: Exception) { -9 }}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // 说话圈：按"是否收到语音包"判定谁在说话（流判定）
    //   · 远端：收到 audio_received 就标记（发送端已按 VAD 门控，见 noteRemotePacket）
    //   · 本地：仍按麦克风能量（micDb > ringThresholdDb）—— 原始 PCM 本来就有，
    //     不必额外解码，且与 VA 发送门控共用同一个门限，两边行为严格一致
    // ─────────────────────────────────────────────────────────

    /**
     * 收到一帧语音包 → 这个人在说话。
     *
     * 判据回到"**流**"，不再解码算能量。依据：发送端已经按 VAD 门控了
     * （AudioBridge 里 shouldTransmitFrame 那一处）—— 不说话根本不会往频道里发流，
     * 所以"收到包"就等价于"对方在说话"。当初流判定会"圈常亮、不跟人动"，根因正是
     * 发送端无条件 50 包/秒地泼流，那个根因已经修掉了，判据本身没有问题。
     *
     * 换回流判定还顺手解决了两件事：
     *  · **快** —— 能量判据要等 jitter buffer 攒够预填充再解码，圈会慢半拍；流判据收到即亮
     *  · **省** —— 不必每帧解码后算 RMS，也就没有门限调参那套复杂度
     *
     * 在主线程（audio_received 事件）调用，userAudibleAt 是 ConcurrentHashMap，
     * 播放线程并发读是安全的。
     */
    private fun noteRemotePacket(userId: Int, now: Long) {
        userAudibleAt[userId] = now
    }

    /** 设置说话圈门限（dBFS，越小越灵敏） */
    fun setRingThresholdDb(db: Double) {
        val v = db.coerceIn(RING_THRESHOLD_DB_MIN, RING_THRESHOLD_DB_MAX)
        if (v == ringThresholdDb) return
        ringThresholdDb = v
        Log.i(TAG, "说话圈门限 = ${v.toInt()} dBFS")
    }

    /** 每 20ms 时隙推一次"谁在说话"：只在集合变化时写 StateFlow，避免 Compose 被拖着重组 */
    private fun publishSpeakingRings() {
        val now = System.currentTimeMillis()
        if (now - ringPublishLastMs < RING_PUBLISH_MIN_MS) return
        ringPublishLastMs = now
        val cutoff = now - RING_HANGOVER_MS
        val speaking = HashSet<Int>(4)
        var loudest: Int? = null
        var earliest = Long.MAX_VALUE
        val it = userAudibleAt.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value >= cutoff) {
                speaking.add(e.key)
                // "当前说话人"取**最早开始说的那个**（也是最近有包到达的）。
                // 为什么不比能量：那要每帧解码算 RMS，而多人同时说话时按响度切换会来回跳；
                // 先说的优先、他停了才轮到下一个，观感反而更稳。
                if (e.value < earliest) {
                    earliest = e.value
                    loudest = e.key
                }
            } else if (now - e.value > 10_000L) {
                it.remove()                  // 很久没声音的用户：清掉，别让表无限长
            }
        }
        if (_speakingUserIds.value != speaking) {
            _speakingUserIds.value = speaking
            Log.i(TAG, "说话圈: 在说=$speaking 最响=$loudest 门限=${ringThresholdDb.toInt()}dBFS")
        }
        if (_loudestSpeakerId.value != loudest) _loudestSpeakerId.value = loudest
    }

    /** 应用增益并写入 AudioTrack（阻塞写，天然限速到实时） */
    private fun writeFrame(mixBuffer: ShortArray) {
        val track = audioTrack ?: return
        val gain = gainFactor
        if (gain != 1.0f) {
            for (i in mixBuffer.indices) {
                mixBuffer[i] = (mixBuffer[i] * gain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
        val bytes = shortsToBytes(mixBuffer)
        try {
            val n = track.write(bytes, 0, bytes.size)
            // 前几帧 + 任何短写都留证据：短写=缓冲满，正常应该等于 bytes.size
            if (writtenFrames < 5 || n != bytes.size) {
                Log.i(TAG, "write 返回 $n/${bytes.size} bytes (第 ${writtenFrames + 1} 次)")
            }
            writtenFrames++
        } catch (e: Throwable) {
            // 写失败（轨道被释放/状态异常）以前会静默带走整个循环，必须留证据
            if (writeErrors++ % 20L == 0L) Log.e(TAG, "writeFrame 失败($writeErrors): $e", e)
        }
    }

    /** 当前在飞（已写入但还没播出去）的帧数：写入总帧数 - 播放头 */
    private fun bufferedFrames(): Long {
        val track = audioTrack ?: return 0L
        val head = try {
            track.playbackHeadPosition.toLong()
        } catch (e: Exception) {
            if (headErrors++ % 20L == 0L) Log.w(TAG, "playbackHeadPosition 读取失败: $e")
            return PLAYBACK_TARGET_FRAMES.toLong() // 读不到头就当已满：宁可停写也不狂写
        }
        return (writtenFrames - head).coerceIn(0L, 1_000_000L)
    }

    /**
     * 解码一帧 Opus（JNI）。
     * 复用 decodeBuffer（只有真实帧会走这里，PLC 补的帧不经过），失败返回 null → 该帧按丢弃处理。
     * 只应在播放线程调用（userDecoders / decodeBuffer 都不是线程安全的）。
     *
     * 注意：说话圈现在由 noteRemotePacket() 在"收到包"时就标记了，这里不再回写能量。
     */
    private fun decodePacket(userId: Int, data: ByteArray): ShortArray? = try {
        val decoder = userDecoders.getOrPut(userId) { OpusCodec(audioConfig) }
        val pcmBytes = decoder.decode(data)
        decodeBuffer.fill(0)
        bytesToShorts(pcmBytes, decodeBuffer)
        decodeBuffer
    } catch (_: Exception) {
        null
    }

    /**
     * Queue an opus packet for a specific user. Called from any thread;
     * decoding happens on the playback thread.
     */
    fun setMutedUserIds(userIds: Set<Int>) {
        mutedUserIds = userIds
    }

    /**
     * 收到一帧语音。
     * @param packetId 发送端给的语音包序号（底层 tsproto 对音频包跳过了包号校验，
     *                 所以重复包/乱序包会原样上来，必须在这里去重+重排）；
     *                 老版本 .so 没有该字段时传 -1，退化为按到达顺序处理。
     */
    fun playAudio(userId: Int, packetId: Int, opusData: ByteArray) {
        if (userId in mutedUserIds) return
        if (_isOutputMuted.value) return // Global output mute — discard incoming audio
        recvFrames++
        // 收到包就判定"这个人在说话"（发送端已按 VAD 门控，不说话不会发流）
        noteRemotePacket(userId, System.currentTimeMillis())
        // 拷贝一份：native 层若复用同一个 ByteArray，存引用会被后续帧覆盖
        userJitter.getOrPut(userId) {
            JitterBuffer(
                userId = userId,
                decode = { data -> decodePacket(userId, data) },
                log = { Log.i(TAG, it) },
            )
        }.submit(packetId, opusData.copyOf(), System.currentTimeMillis())
        publishSpeakingRings()
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        tsClient.setInputMuted(muted)
    }

    fun toggleMute() {
        val newState = !_isMuted.value
        _isMuted.value = newState
        tsClient.setInputMuted(newState)
    }

    fun setOutputMuted(muted: Boolean) {
        _isOutputMuted.value = muted
        // When output is muted, clear all queued audio so nothing plays
        if (muted) {
            userJitter.values.forEach { it.reset() }
            // 静音了就不该还有圈
            userAudibleAt.clear()
            _speakingUserIds.value = emptySet()
            _loudestSpeakerId.value = null
        }
    }

    fun toggleOutputMute() {
        setOutputMuted(!_isOutputMuted.value)
    }

    fun release() {
        Log.i(TAG, "release() 被调用: track=${audioTrack?.hashCode()} state=${audioTrack?.state} " +
                "written=$writtenFrames head=${try { audioTrack?.playbackHeadPosition } catch (_: Exception) { -1 }} " +
                "loop=$loopTicks werr=$writeErrors")
        stopCapture()
        // 取消待执行的焦点重抢，并让已排队的那次失效（否则释放后还会去抢）
        focusRetryToken++
        mainHandler.removeCallbacksAndMessages(null)
        abandonAudioFocus()
        statsJob?.cancel()
        statsJob = null
        playbackJob?.cancel()
        playbackJob = null
        playbackScope.cancel()
        try { playbackExecutor.shutdownNow() } catch (_: Exception) {}
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        encoder?.close()
        encoder = null
        // Close per-user decoders
        for (decoder in userDecoders.values) {
            try { decoder.close() } catch (_: Exception) {}
        }
        userDecoders.clear()
        userJitter.clear()
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    private fun bytesToShorts(bytes: ByteArray, out: ShortArray) {
        val count = minOf(bytes.size / 2, out.size)
        for (i in 0 until count) {
            out[i] = ((bytes[i * 2].toInt() and 0xFF) or
                    (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
    }
}

/**
 * 这一帧要不要编码发出去。
 *
 * · PTT 模式（voiceActivatedMode = false）**永远发** —— 用户按住按键就是明确表达了"要发"，
 *   这时再拿 VAD 拦他，会出现"我按住说话了却没发出去"的困惑；
 * · VA 模式只在判定"在说话"时发 —— 静音段不编码不上行：省上行带宽和电量，
 *   也让服务器不再一直认为你在说话（PC 端那个发送灯会跟着声音闪，而不是常亮）。
 */
internal fun shouldTransmitFrame(voiceActivatedMode: Boolean, isVoiceActive: Boolean): Boolean =
    !voiceActivatedMode || isVoiceActive
