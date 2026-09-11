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
        private const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000 // 960
        private const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2 // 16-bit PCM = 2 bytes/sample
        private const val JB_WAIT_TICKS = 2        // 缺包最多等 2 个时隙（40ms）再判丢
        private const val JB_MAX_DEPTH = 6         // 只收“近未来”的包（<=12 个包号），限制缓冲深度
        private const val JB_IDLE_RESET_MS = 250L  // 静默这么久就重置（下一段说话重新起播）
        // AudioTrack 硬件缓冲（上限，不是常驻延迟）：给它足够空间吸收突发
        private const val PLAYBACK_BUFFER_FRAMES = 8
        // 真正决定延迟的是"在飞帧数"目标：3 帧 ≈ 60ms。太小会 underrun，太大会迟滞（像被拉长）
        private const val PLAYBACK_TARGET_FRAMES = 3
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
    // 解码复用缓冲：只在播放线程（JitterBuffer.decodeToPcm）使用
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

    // ── 音频焦点：防止被其他 App（音乐/导航）duck 或抢占 ──
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

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
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> _isOutputMuted.value = true
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
                    var energy = 0L
                    for (i in 0 until read) {
                        energy += buffer[i].toLong() * buffer[i].toLong()
                    }
                    val rms = Math.sqrt(energy.toDouble() / read)
                    val isVoiceActive = rms > 150.0 // Adjusted threshold for voice activity
                    _isLocalVoiceActive.value = isVoiceActive
                    
                    val pcmBytes = shortsToBytes(buffer)
                    try {
                        val encoded = codec.encode(pcmBytes)
                        tsClient.sendAudio(encoded, CODEC_OPUS_VOICE)
                    } catch (_: Exception) {}
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
                    var hasAudio = false
                    for ((_, jb) in userJitter) {
                        // 抖动缓冲按包号出帧：可能返回真实帧、PLC 帧、或 null（还没起播）
                        val pcm = try {
                            jb.produceTick()
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
                Log.i(TAG,
                    "audio stats: recv=${recvDelta}/s played=${playedDelta}/s dup=${dupD}/s lost=${lostD}/s plc=${plcD}/s " +
                        "resync=${resyncD}/s pending=$pend buf=${bufferedFrames()}f written=$writtenFrames head=$head " +
                        "loop=$loopTicks werr=$writeErrors herr=$headErrors track=$trackCreates " +
                        "state=${try { audioTrack?.state } catch (_: Exception) { -9 }} " +
                        "sess=${try { audioTrack?.audioSessionId } catch (_: Exception) { -9 }}")
            }
        }
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
     * 每用户抖动缓冲：按包号去重 + 重排 + 缺包隐藏（PLC）。
     *
     * 背景：底层 tsproto 对音频包**跳过包号校验**（源码注释 "Ignore range for acks and audio packets"），
     * 服务器重发/网络乱序的包会原样送到这里。不处理的话：重复帧被播两遍（听感=被拉长），
     * 乱序帧被前后颠倒播（听感=咔哒/卡）。这里按官方客户端那套做：去重、按序、丢包用 PLC 糊过去。
     */
    private inner class JitterBuffer(val userId: Int) {
        private val pending = java.util.TreeMap<Int, ByteArray>()
        private var nextExpected = -1
        private var waitTicks = 0
        private var plcTicks = 0
        private var lastPcm: ShortArray? = null

        @Volatile var lastRecvMs = 0L
        var dupDropped = 0L
        var lostCount = 0L
        var resyncCount = 0L
        var plcCount = 0L

        fun pendingSize(): Int = pending.size

        fun submit(packetId: Int, data: ByteArray) {
            lastRecvMs = System.currentTimeMillis()
            if (nextExpected < 0) {
                nextExpected = if (packetId >= 0) packetId and 0xFFFF else 0
            }
            val id = if (packetId >= 0) packetId and 0xFFFF else nextExpected
            val delta = (id - nextExpected) and 0xFFFF
            when {
                delta < JB_MAX_DEPTH * 2 -> {              // 正是期望的，或近未来（乱序早到）
                    if (pending.putIfAbsent(id, data) != null) dupDropped++
                }
                delta >= 0x8000 -> dupDropped++             // 过期/重复包
                else -> {                                   // 远期跳变：重同步（换人/包号回绕）
                    resyncCount++
                    pending.clear()
                    pending[id] = data
                    nextExpected = id
                }
            }
        }

        /** 产出当前 20ms 时隙的 PCM：真实帧 / PLC 帧 / null */
        fun produceTick(): ShortArray? {
            if (nextExpected < 0) return null
            if (pending.isEmpty() && System.currentTimeMillis() - lastRecvMs > JB_IDLE_RESET_MS) {
                reset()
                return null
            }
            val data = pending.remove(nextExpected)
            if (data != null) {
                waitTicks = 0
                plcTicks = 0
                val pcm = decodeToPcm(data)
                if (pcm != null) {
                    lastPcm = pcm.copyOf()
                    nextExpected = (nextExpected + 1) and 0xFFFF
                }
                return pcm
            }
            // 缺包：先等 JB_WAIT_TICKS 个时隙；仍没有就判丢并跳到已知的下一个包
            if (waitTicks < JB_WAIT_TICKS) {
                waitTicks++
                plcCount++
                return plcFrame()
            }
            lostCount++
            waitTicks = 0
            nextExpected = if (pending.isNotEmpty()) pending.firstKey() else (nextExpected + 1) and 0xFFFF
            plcCount++
            return plcFrame()
        }

        /** 丢包隐藏：重复上一帧并递减增益（消掉"咔哒"，听感连续） */
        private fun plcFrame(): ShortArray? {
            val last = lastPcm ?: return null
            val g = when (plcTicks) {
                0 -> 0.85f
                1 -> 0.6f
                2 -> 0.35f
                else -> 0f
            }
            plcTicks++
            if (g == 0f) return null
            val out = ShortArray(last.size)
            for (i in last.indices) {
                out[i] = (last[i] * g).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            return out
        }

        private fun decodeToPcm(data: ByteArray): ShortArray? = try {
            val decoder = userDecoders.getOrPut(userId) { OpusCodec(audioConfig) }
            val pcmBytes = decoder.decode(data)
            decodeBuffer.fill(0)
            bytesToShorts(pcmBytes, decodeBuffer)
            decodeBuffer
        } catch (_: Exception) {
            null
        }

        fun reset() {
            pending.clear()
            nextExpected = -1
            waitTicks = 0
            plcTicks = 0
            lastPcm = null
        }
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
        // 拷贝一份：native 层若复用同一个 ByteArray，存引用会被后续帧覆盖
        userJitter.getOrPut(userId) { JitterBuffer(userId) }.submit(packetId, opusData.copyOf())
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
