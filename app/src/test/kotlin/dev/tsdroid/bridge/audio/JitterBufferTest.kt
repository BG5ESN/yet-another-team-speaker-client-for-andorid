package dev.tsdroid.bridge.audio

import dev.tsdroid.bridge.audio.JitterBuffer.Companion.JB_IDLE_RESET_MS
import dev.tsdroid.bridge.audio.JitterBuffer.Companion.JB_MAX_TARGET_SLOTS
import dev.tsdroid.bridge.audio.JitterBuffer.Companion.JB_SHRINK_IDLE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抖动缓冲的回归网。
 *
 * 用假解码器（PCM 每个样本 = 包号）和手工推进的 now，把时序逻辑与 JNI/设备完全隔离：
 * 出帧顺序、去重、判丢、PLC 衰减、静默重置、自适应深度全部可在毫秒级精确复现。
 */
class JitterBufferTest {

    /** 假解码：把包里的 16 位包号原样铺成 PCM，便于断言"出的是哪一包" */
    private val DEC: (ByteArray) -> ShortArray? = { data ->
        val id = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        ShortArray(FRAME_SAMPLES) { id.toShort() }
    }

    private fun pkt(id: Int) = byteArrayOf((id and 0xFF).toByte(), ((id shr 8) and 0xFF).toByte())

    private fun jb() = JitterBuffer(userId = 7, decode = DEC)

    private fun sample(out: ShortArray?): Int {
        assertNotNull("期望有帧输出", out)
        return out!![0].toInt()
    }

    // ── 1) 正常连号：每 tick 一帧真实音频，不产生任何 PLC ──
    @Test
    fun consecutivePacketsEmitRealFramesWithoutPlc() {
        val jb = jb()
        var t = T0
        for (id in 0 until 10) {
            jb.submit(id, pkt(id), t)
            assertEquals("第 $id 包应按序出帧", id, sample(jb.produceTick(t)))
            t += 20
        }
        assertEquals(0L, jb.plcCount)
        assertEquals(0L, jb.lostCount)
        assertEquals(0L, jb.dupDropped)
    }

    // ── 2) 乱序到达：按包号重排后再出声，不是按到达顺序 ──
    @Test
    fun outOfOrderPacketsAreReordered() {
        val jb = jb()
        val t = T0
        jb.submit(0, pkt(0), t)
        jb.submit(2, pkt(2), t + 20)   // 2 先到
        jb.submit(1, pkt(1), t + 40)   // 1 后到

        assertEquals(0, sample(jb.produceTick(t)))
        assertEquals("1 应排在 2 前面出", 1, sample(jb.produceTick(t + 20)))
        assertEquals(2, sample(jb.produceTick(t + 40)))
        assertEquals(0L, jb.dupDropped)
    }

    // ── 3) 重复包：第二份丢弃，不播两遍（否则听感=声音被拉长）──
    @Test
    fun duplicatePacketIsDropped() {
        val jb = jb()
        val t = T0
        jb.submit(0, pkt(0), t)
        jb.submit(0, pkt(0), t + 20)   // 同一包再来一次

        assertEquals(0, sample(jb.produceTick(t)))
        assertEquals(1L, jb.dupDropped)
    }

    // ── 4) 缺包：先 PLC 顶住，增益逐帧递减到静音；缺 1 个包不跳号（避免切断连续语音）──
    @Test
    fun missingPacketProducesDecayingPlcThenSilence() {
        val jb = jb()
        var t = T0
        jb.submit(0, pkt(0), t)
        jb.submit(1, pkt(1), t + 20)
        jb.submit(3, pkt(3), t + 40)   // 缺 2
        jb.submit(4, pkt(4), t + 60)

        assertEquals(0, sample(jb.produceTick(t)))
        assertEquals(1, sample(jb.produceTick(t + 20)))

        // 缺 2：PLC 帧（重复上一帧 + 增益递减 0.85 / 0.6 / 0.35），之后静音
        t += 40
        val plc = ArrayList<ShortArray?>()
        repeat(5) { plc.add(jb.produceTick(t)); t += 20 }

        assertEquals("PLC 首帧应是上一帧的 0.85 倍", (1 * 0.85f).toInt(), plc[0]!![0].toInt())
        assertEquals("第二帧 0.6", (1 * 0.6f).toInt(), plc[1]!![0].toInt())
        assertEquals("第三帧 0.35", (1 * 0.35f).toInt(), plc[2]!![0].toInt())
        assertNull("增益耗尽后应静音，不能无限糊", plc[3])
        assertTrue("应记录到丢包", jb.lostCount > 0)
        assertTrue("不应跳号：3 还在缓冲里，要原地等", jb.resyncCount == 0L)
    }

    // ── 5) 静默后重新起播：靠静默重置，而不是走"远期跳变重同步" ──
    @Test
    fun idleGapResetsInsteadOfResyncing() {
        val jb = jb()
        val t = T0
        jb.submit(0, pkt(0), t)
        assertEquals(0, sample(jb.produceTick(t)))

        // 静默超过 JB_IDLE_RESET_MS：pending 空 → 自动重置
        assertNull(jb.produceTick(t + JB_IDLE_RESET_MS + 20))

        // 新一段说话，包号从很远处起（等价于换人/长时间停顿）
        val t2 = t + JB_IDLE_RESET_MS + 100
        jb.submit(100, pkt(100), t2)
        assertEquals("重置后应把 100 当新起点直接接受", 100, sample(jb.produceTick(t2)))
        assertEquals("不该走重同步分支", 0L, jb.resyncCount)
    }

    // ── 6) 预填充：一段说话先屯够 targetSlots 帧才出声 ──
    @Test
    fun prefillHoldsBackUntilTargetDepthReached() {
        val jb = jb()
        var t = T0
        // 先用 40ms 的到达间隔把抖动估计顶起来，让 adapt 把目标深度提到 ≥2
        repeat(80) { i ->
            jb.submit(i, pkt(i), t)
            jb.produceTick(t)
            t += 40
        }
        val depth = jb.targetSlots()
        assertTrue("抖动线路上目标深度应升到 ≥2（实际 $depth）", depth >= 2)

        // 新一段说话：只到 1 帧时不该出声
        jb.reset()
        val t2 = t + 1000
        jb.submit(500, pkt(500), t2)
        assertNull("只有 1 帧、不足目标深度时应保持静音", jb.produceTick(t2))
        jb.submit(501, pkt(501), t2 + 20)
        assertEquals("屯够目标深度后出声", 500, sample(jb.produceTick(t2 + 20)))
    }

    // ── 7) 自适应升档：只有**真的判丢**才加深（补帧不算，见用例 10 的反例）──
    @Test
    fun depthRisesWhenPacketsAreActuallyDropped() {
        val jb = jb()
        val t0 = T0
        jb.submit(0, pkt(0), t0)
        assertEquals(0, sample(jb.produceTick(t0)))    // 建立 nextExpected

        // 每 10 拍里有 2 拍没有包到达 → 缺口跨过等待窗口 → 判丢
        var nextId = 1
        var t = t0 + 20
        repeat(600) { tick ->
            if (tick % 10 >= 2) {
                jb.submit(nextId, pkt(nextId), t)
                nextId++
            }
            jb.produceTick(t)
            t += 20
        }
        assertTrue("真丢包时应升档（lost=${jb.lostCount}, targetSlots=${jb.targetSlots()}）",
            jb.targetSlots() >= 2)
    }

    // ── 8) 自适应降档：线路干净满 JB_SHRINK_IDLE_MS 才回落一档 ──
    @Test
    fun depthFallsWhenLineIsClean() {
        val jb = jb()
        val t = T0
        // 先升档（同用例 7：靠真判丢）
        jb.submit(0, pkt(0), t)
        jb.produceTick(t)
        var riseId = 1
        var tRise = t + 20
        repeat(600) { tick ->
            if (tick % 10 >= 2) {
                jb.submit(riseId, pkt(riseId), tRise)
                riseId++
            }
            jb.produceTick(tRise)
            tRise += 20
        }
        val risen = jb.targetSlots()
        assertTrue("前置条件：应先升档（实际 $risen）", risen >= 2)

        // 再跑 8 秒完全干净的连号流（间隔稳定 20ms，无补帧、无丢包）
        jb.reset()                     // 注意：reset 保留 targetSlots（这是设计，不是 bug）
        val plcBefore = jb.plcCount
        var id = 200
        var t2 = tRise + 2000
        var realFrames = 0
        // 跑 15 秒干净连号流（无补帧、无丢包）。长度要留够：升档阶段的补帧计数会残留到
        // 干净流的第一个统计周期，可能再多升一档，所以必须给足"每秒降一档"的回落时间。
        repeat(750) {
            jb.submit(id, pkt(id), t2)
            // 开头有预填充空窗（不足 targetSlots 帧不出声），所以只统计出帧数，不逐帧断言
            if (jb.produceTick(t2) != null) realFrames++
            id++
            t2 += 20
        }
        assertEquals("干净连号流不该产生任何补帧", plcBefore, jb.plcCount)
        assertTrue("干净流应基本每拍都出帧（实际 $realFrames/750）", realFrames >= 740)
        // 回落目标是"抖动下限"而不是 1：jitterFloor = ceil(jit/20) + 1，
        // 只要还有一丝抖动估计（jit 衰减到极小但不为零），ceil 就至少给 1 档，再加 1 档余量 = 2 档(40ms)。
        assertEquals("线路持续干净应回落到抖动下限 2 档(40ms)（起于 $risen，jit=${jb.jitterMs()}）",
            2, jb.targetSlots())
    }

    // ── 9) 边界：最后一个包播完后 pending 为空，不得抛异常 ──
    @Test
    fun emptyPendingAfterLastFrameDoesNotThrow() {
        val jb = jb()
        val t = T0
        jb.submit(0, pkt(0), t)
        assertEquals(0, sample(jb.produceTick(t)))   // 唯一一包播完 → pending 空

        // 之后几拍（离上次收包 < 静默重置阈值）：应继续出 PLC/静音，而不是抛 NoSuchElementException
        repeat(6) { i ->
            jb.produceTick(t + 20L * (i + 1))
        }
        assertTrue("pending 空时也不该把丢包数算爆", jb.lostCount < 10L)
    }

    // ── 10) 真机现象的复现：包只是"到得不准点"，一个都没丢 ──
    // 真机日志里 jb 恒定顶在上限 8x20ms（160ms），而 lost=0/s、dup=0/s。
    // 说明把深度顶上天的不是丢包，而是"晚到半拍"引发的补帧。
    @Test
    fun jitterWithoutPacketLossMustNotPushDepthToCeiling() {
        val jb = jb()
        val rnd = java.util.Random(20260912L)      // 固定种子：完全可复现

        // 关键：消费由音频时钟驱动（每 20ms 必须出一帧），包由网络驱动 —— 包晚一拍就立刻缺帧。
        // 用"每 6 拍里有 1 拍的包晚到一拍"来模拟：包号严格连续（一个都没丢），只是晚到。
        // （注意不能往缓冲里"批量补交"——那会形成积压，把抖动吸收掉，反而复现不出来。）
        val ARRIVE_LATE_EVERY = 6

        var nextId = 0
        repeat(3000) { tick ->
            val t = T0 + tick * 20L
            // 不是"晚到的那一拍"就正常提交当前包；晚到的那一拍什么都不提交（包还在路上）
            if (tick % ARRIVE_LATE_EVERY != 3 && nextId < 3000) {
                jb.submit(nextId, pkt(nextId), t)
                nextId++
            }
            jb.produceTick(t)
        }
        println(
            "[diag] 60 秒抖动流(不丢包): depth=${jb.targetSlots()} plc=${jb.plcCount} " +
                "plc/s=${"%.1f".format(jb.plcCount / 60.0)} lost=${jb.lostCount} " +
                "resync=${jb.resyncCount} jit=${jb.jitterMs().toInt()}ms 已提交=$nextId"
        )

        assertTrue(
            "抖动但不丢包时，深度不该被顶到上限 —— " +
                "实际 ${jb.targetSlots()} 档(${jb.targetSlots() * 20}ms)，" +
                "plc=${jb.plcCount} lost=${jb.lostCount} resync=${jb.resyncCount} " +
                "jit=${jb.jitterMs().toInt()}ms",
            jb.targetSlots() < JB_MAX_TARGET_SLOTS,
        )
    }

    private companion object {
        /** 起点取远离 0 的值：避开 lastAdaptMs 初值 0 的首次 adapt 边界 */
        const val T0 = 100_000L
        const val FRAME_SAMPLES = 960
    }
}
