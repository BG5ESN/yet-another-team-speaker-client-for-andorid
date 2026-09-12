package dev.tsdroid.bridge.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * JitterBuffer 的并发回归测试。
 *
 * 背景：submit() 来自 serviceScope（Dispatchers.Main —— audio_received 事件），
 * produceTick() 来自 playbackScope（专用高优先级音频线程）。原来 pending 是**裸的 TreeMap**，
 * 两条线程并发 put 会把红黑树结构改坏，真机上崩过：
 *
 *   NullPointerException: Attempt to write to field 'boolean TreeMap$TreeMapEntry.color'
 *   on a null object reference in method 'void TreeMap.fixAfterInsertion(...)'
 *     at java.util.TreeMap.putIfAbsent(TreeMap.java:575)
 *     at dev.tsdroid.bridge.audio.JitterBuffer.submit(JitterBuffer.kt:135)
 *
 * 这个测试就是"两条线程一起用"的最小复现：单线程的 JitterBufferTest 永远测不到它。
 */
class JitterBufferConcurrencyTest {

    @Test
    fun submitAndProduceFromTwoThreadsMustNotCorruptState() {
        val jb = JitterBuffer(userId = 1, decode = { ShortArray(960) })
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val produced = AtomicInteger(0)

        // 生产者：模拟 audio_received 事件线程，持续 submit
        val producer = thread(name = "jb-producer") {
            try {
                var id = 0
                var now = 0L
                while (!stop.get()) {
                    jb.submit(id, ByteArray(16) { 1 }, now)
                    id = (id + 1) and 0xFFFF
                    now += 20
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        // 消费者：模拟播放线程，持续 produceTick（会 remove/firstKey/adapt）
        val consumer = thread(name = "jb-consumer") {
            try {
                var now = 0L
                while (!stop.get()) {
                    jb.produceTick(now)
                    produced.incrementAndGet()
                    now += 20
                    Thread.yield()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        Thread.sleep(1500)
        stop.set(true)
        producer.join(2000)
        consumer.join(2000)

        failure.get()?.let { throw AssertionError("并发访问 JitterBuffer 抛异常：$it", it) }
        assertTrue("播放线程应该实际跑起来过（否则测试没意义）", produced.get() > 0)
    }

    /** 顺带钉住：reset() 也是从别的线程调的，别和 submit/produceTick 撞车 */
    @Test
    fun resetConcurrentWithSubmitMustNotCorruptState() {
        val jb = JitterBuffer(userId = 2, decode = { ShortArray(960) })
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)

        val t1 = thread {
            try {
                var id = 0
                while (!stop.get()) {
                    jb.submit(id, ByteArray(16) { 2 }, id * 20L)
                    id = (id + 1) and 0xFFFF
                }
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }
        val t2 = thread {
            try {
                while (!stop.get()) {
                    jb.reset()
                    jb.pendingSize()
                    Thread.yield()
                }
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }

        Thread.sleep(1000)
        stop.set(true)
        t1.join(2000)
        t2.join(2000)
        failure.get()?.let { throw AssertionError("reset 并发访问抛异常：$it", it) }
    }
}
