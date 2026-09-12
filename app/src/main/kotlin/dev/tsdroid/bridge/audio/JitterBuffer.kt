package dev.tsdroid.bridge.audio

/**
 * 每用户抖动缓冲：按包号去重 + 重排 + 缺包隐藏（PLC）+ 按实测抖动自适应深度。
 *
 * 背景：底层 tsproto 对音频包**跳过包号校验**（源码注释 "Ignore range for acks and audio packets"），
 * 服务器重发/网络乱序的包会原样送到播放侧。不处理的话：重复帧被播两遍（听感=被拉长），
 * 乱序帧被前后颠倒播（听感=咔哒/卡）。这里按官方客户端那套做：去重、按序、丢包用 PLC 糊过去。
 *
 * 本类**不碰 Android / JNI / 音频设备**，三处依赖全部由调用方注入：
 *  · now    —— 时间由参数传入（调用方给 `System.currentTimeMillis()`），测试可完全控制时间线
 *  · decode —— Opus 解码走注入的函数（生产环境是 JNI；测试注入假解码器即可跑通全部时序逻辑）
 *  · log    —— 日志走注入（生产环境接 `Log.i`，测试期 no-op）
 *
 * 注意：`produceTick` 返回的 PCM 可能是调用方提供的复用缓冲（由 decode 决定），
 * 只允许在同一个播放线程里立即消费，不要跨线程持有。
 */
internal class JitterBuffer(
    val userId: Int,
    private val decode: (ByteArray) -> ShortArray?,
    private val log: (String) -> Unit = {},
) {
    companion object {
        /** 一个时隙的长度（ms）——抖动的度量基准 */
        const val FRAME_SIZE_MS = 20
        /** 缺包至少等 1 个时隙再考虑跳号 */
        const val JB_MIN_WAIT_TICKS = 1
        /** 目标深度下限（20ms） */
        const val JB_MIN_TARGET_SLOTS = 1
        /** 目标深度上限（160ms） */
        const val JB_MAX_TARGET_SLOTS = 8
        /** 预填充最多等这么久，避免稀疏流开不出声 */
        const val JB_PREFILL_TIMEOUT_MS = 150L
        /** 每秒统计一次 */
        const val JB_ADAPT_INTERVAL_MS = 1000L
        /** 干净满 5 秒才回落一档（慢降，防来回抖） */
        const val JB_SHRINK_IDLE_MS = 5000L
        /** 缓冲上限（≈640ms），超了直接对齐最新 */
        const val JB_MAX_PENDING = 32
        /** 抖动估计上限（别让停顿把深度顶满） */
        const val JB_JITTER_MAX_MS = 100.0
        /** 静默这么久就重置（下一段说话重新起播） */
        const val JB_IDLE_RESET_MS = 250L
    }

    private val pending = java.util.TreeMap<Int, ByteArray>()
    private var nextExpected = -1
    private var waitTicks = 0
    private var plcTicks = 0
    private var lastPcm: ShortArray? = null

    // ── 自适应抖动缓冲的状态 ──────────────────────────────
    /** 本段说话是否已预填充完成（每段只填一次） */
    private var prefillDone = false
    /** 本段第一帧到达时间（预填充超时兜底用） */
    private var burstStartMs = 0L
    /** 上一次到达时间与抖动指数平均（|间隔-20ms|） */
    private var arrivalLastMs = 0L
    private var jitterEwmaMs = 0.0
    /** 目标缓冲深度（时隙数，1~JB_MAX_TARGET_SLOTS） */
    private var targetSlots = JB_MIN_TARGET_SLOTS
    /** 适配节拍：上次统计、上次降档时间、上次 PLC 数 */
    private var lastAdaptMs = 0L
    private var lastShrinkMs = 0L
    private var lastAdaptPlc = 0L

    /** 最近一次收到包的时刻（诊断用） */
    @Volatile var lastRecvMs = 0L
        private set
    var dupDropped = 0L
        private set
    var lostCount = 0L
        private set
    var resyncCount = 0L
        private set
    var plcCount = 0L
        private set

    fun pendingSize(): Int = pending.size

    /** 诊断用：当前目标深度（时隙） */
    fun targetSlots(): Int = targetSlots

    /** 诊断用：抖动估计（ms） */
    fun jitterMs(): Double = jitterEwmaMs

    /** 从 nextExpected 起连续可播的帧数 */
    private fun contiguousDepth(): Int {
        var d = 0
        var id = nextExpected
        while (d < 32 && pending.containsKey(id)) {
            d++
            id = (id + 1) and 0xFFFF
        }
        return d
    }

    /** 收到一帧语音包。@param now 到达时刻（ms），由调用方给 */
    fun submit(packetId: Int, data: ByteArray, now: Long) {
        lastRecvMs = now
        if (arrivalLastMs != 0L && packetId >= 0) {
            val interval = (now - arrivalLastMs).toDouble()
            if (interval <= FRAME_SIZE_MS * 8) {
                // 只在"连续说话"的到达间隔里测抖动：相对 20ms 的偏差做指数平均
                val dev = kotlin.math.abs(interval - FRAME_SIZE_MS)
                jitterEwmaMs = (jitterEwmaMs * 0.9 + dev * 0.1).coerceAtMost(JB_JITTER_MAX_MS)
            } else {
                // 长间隔 = 停顿/换段，不是抖动：让它自然衰减，别把停顿算成 800ms 抖动
                jitterEwmaMs *= 0.7
            }
        }
        arrivalLastMs = now

        if (nextExpected < 0) {
            nextExpected = if (packetId >= 0) packetId and 0xFFFF else 0
            burstStartMs = now
            prefillDone = false
        }
        val id = if (packetId >= 0) packetId and 0xFFFF else nextExpected
        val delta = (id - nextExpected) and 0xFFFF
        // 接收窗口随目标深度放宽：目标越深，"近未来"要收得越远
        val fwd = (targetSlots + 4) * 2
        when {
            delta < fwd -> {                             // 期望的 / 乱序早到的
                if (pending.putIfAbsent(id, data) != null) dupDropped++
            }
            delta >= 0x8000 -> dupDropped++              // 过期/重复包
            else -> {                                    // 远期跳变：重同步（换人/包号回绕）
                resyncCount++
                pending.clear()
                pending[id] = data
                nextExpected = id
                prefillDone = false
                burstStartMs = now
            }
        }
        // 保险：缓冲堆太多说明有长缺口，直接对齐到最新（避免延迟无限增长）
        if (pending.size > JB_MAX_PENDING) {
            resyncCount++
            val newest = pending.lastKey()
            pending.clear()
            pending[newest] = data
            nextExpected = newest
            prefillDone = false
            burstStartMs = now
        }
    }

    /** 每秒一次的深度自适应：抖动测量兜底 + 补帧快升 / 长时间干净才慢降 */
    private fun adapt(now: Long) {
        if (now - lastAdaptMs < JB_ADAPT_INTERVAL_MS) return
        lastAdaptMs = now
        val plcDelta = (plcCount - lastAdaptPlc).toInt()
        lastAdaptPlc = plcCount
        // 实测抖动（到达间隔偏离 20ms 的量）直接映射成"最低深度"：
        // jit≈20ms → 2 档 + 1 档余量 = 3 档(60ms)。这样不靠"丢了才反应"，而是提前托住。
        val jitterFloor = (kotlin.math.ceil(jitterEwmaMs / FRAME_SIZE_MS).toInt() + 1)
            .coerceIn(JB_MIN_TARGET_SLOTS, JB_MAX_TARGET_SLOTS)
        if (plcDelta >= 3) {
            // 快升：这一秒补了 3 帧以上，说明还是等不够
            if (targetSlots < JB_MAX_TARGET_SLOTS) {
                targetSlots++
                log("JB(user=$userId) 补帧多(plc=${plcDelta}/s, jit=${jitterEwmaMs.toInt()}ms) → 目标深度 ${targetSlots} 时隙(${targetSlots * 20}ms)")
            }
            lastShrinkMs = now
        } else if (plcDelta == 0 && targetSlots > jitterFloor && now - lastShrinkMs >= JB_SHRINK_IDLE_MS) {
            // 慢降：干净满 JB_SHRINK_IDLE_MS 且仍高于抖动下限，才回落一档
            targetSlots--
            log("JB(user=$userId) 线路干净 → 目标深度 ${targetSlots} 时隙(${targetSlots * 20}ms)")
            lastShrinkMs = now
        }
        if (targetSlots < jitterFloor) {
            // 抖动变大：不等到丢包就把深度托到下限
            targetSlots = jitterFloor
            log("JB(user=$userId) 抖动 ${jitterEwmaMs.toInt()}ms → 托底到 ${targetSlots} 时隙(${targetSlots * 20}ms)")
        }
    }

    /**
     * 产出当前 20ms 时隙的 PCM：真实帧 / PLC 帧 / null（静音，让混音去写）。
     * @param now 当前时刻（ms），由调用方给
     */
    fun produceTick(now: Long): ShortArray? {
        if (nextExpected < 0) return null
        if (pending.isEmpty() && now - lastRecvMs > JB_IDLE_RESET_MS) {
            reset()
            return null
        }
        adapt(now)

        // 预填充：一段说话先屯够 targetSlots 帧再出声（这段是抗抖动用的延迟）
        if (!prefillDone) {
            if (contiguousDepth() < targetSlots && now - burstStartMs < JB_PREFILL_TIMEOUT_MS) {
                return null
            }
            prefillDone = true
        }

        val data = pending.remove(nextExpected)
        if (data != null) {
            waitTicks = 0
            plcTicks = 0
            val pcm = decode(data)
            if (pcm != null) {
                lastPcm = pcm.copyOf()
                nextExpected = (nextExpected + 1) and 0xFFFF
            }
            return pcm
        }
        // 缺包：等待窗口跟目标深度走（深度越深，愿意等越久）
        val waitMax = (targetSlots - 1).coerceAtLeast(JB_MIN_WAIT_TICKS)
        if (waitTicks < waitMax) {
            waitTicks++
            plcCount++
            return plcFrame()
        }
        // 等够了：判丢。只有缺口确实拉大（下一包离得远）才跳号，否则原地多等一拍
        lostCount++
        waitTicks = 0
        // pending 已空 = 最后一段语音的尾帧刚播完：没有"下一包"可对齐。
        // 继续 PLC 等下一包到达或静默重置（不防这一手会撞 firstKey() → NoSuchElementException）
        if (pending.isEmpty()) {
            plcCount++
            return plcFrame()
        }
        val nextAvail = pending.firstKey()
        val gap = (nextAvail - nextExpected) and 0xFFFF
        if (gap in 1..(targetSlots + 2)) {
            // 只差一点：继续等这一包，别跳（跳号会把连续语音切断）
            plcCount++
            return plcFrame()
        }
        nextExpected = nextAvail
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

    fun reset() {
        pending.clear()
        nextExpected = -1
        waitTicks = 0
        plcTicks = 0
        lastPcm = null
        // 抖动用的是"到达间隔"而非缓冲状态，这里不重置；但一段新说话开始时
        // prefill 会重新屯一次，所以清掉 arrivalLastMs 避免跨段的假间隔
        arrivalLastMs = 0L
    }
}
