package dev.tsdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CrashCatcher.readableTrace 的回归测试。
 *
 * 背景：原生崩溃时 ApplicationExitInfo.traceInputStream 给的是 **protobuf 二进制**（tombstone），
 * 旧代码直接 String(bytes, UTF_8) 塞进报告，于是那份 240KB 号称"给人读"的报告里，trace 段
 * 全是控制字符 —— 实测可打印率只有 67 percent。
 */
class CrashTraceTest {

    /** 文本型 trace（ANR 那种）必须原样返回，不能被"抽取"二次加工 */
    @Test
    fun plainTextTracePassesThrough() {
        val anr = buildString {
            append("----- pid 1234 at 2026-09-11 20:19:20 -----\n")
            append("Cmd line: com.yuaxi.ts6droid.cn\n")
            append("\"main\" prio=5 tid=1 Native\n")
        }
        assertEquals(anr, CrashCatcher.readableTrace(anr.toByteArray(Charsets.UTF_8)))
    }

    /**
     * 样本取自**实测报告**：从 2026-09-12 那份 crash-*.protos 的 trace 段截的 400 字节
     * （可打印率 0.45，控制字节与可读串交错）。
     *
     * 注意里面夹杂的 EF BF BD（U+FFFD 替换字符）—— 那是旧代码用 String(bytes, UTF_8) 强行
     * 解码二进制时留下的破坏痕迹，说明**旧报告里的原始字节已经救不回来了**；
     * 改成直接处理 ByteArray 之后，新报告才不会再被破坏。
     */
    @Test
    fun protobufTraceKeepsReadableParts() {
        val raw = hex(
            "3a31393a32302e3531323935373630352b3038303028efbfbd0d30efbfbdefbfbd0138efbfbd514229753a723a756e747275737465645f6170703a73303a633132352c633235372c633531322c63373638004a15636f6d2e79756178692e74733664726f69642e636e5220080612075349474142525418efbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbdefbfbd01220853495f5155455545efbfbd01efbfbd2f08efbfbdefbfbd0112efbfbd2f08efbfbdefbfbd01120f44656661756c7444697370617463681a0f0a02783010efbfbdc7a9efbfbde48e80efbfbdefbfbd011a070a02783110efbfbd011a060a02783210021a0b0a02783310c8bcefbfbdefbfbdefbfbd0e1a040a0278341a040a0278351a040a0278361a040a0278371a060a02783810621a040a0278391a050a037831301a100a0378313110e0b885efbfbdea8e80efbfbdefbfbd011a070a0378313210011a0c0a0378313310efbfbdefbfbdefbfbdefbfbdefbfbd0e1a0c0a0378313410efbfbdefbfbdefbfbdefbfbdefbfbd0e1a0c0a0378313510"
        )
        val out = CrashCatcher.readableTrace(raw)

        assertTrue("应声明原始是二进制 protobuf", out.contains("protobuf"))
        assertTrue("要能捞到信号名", out.contains("SIGABRT"))
        assertTrue("要能捞到 si_code", out.contains("SI_QUEUE"))
        assertTrue("要能捞到进程上下文", out.contains("untrusted_app"))
        assertTrue("要能捞到包名", out.contains("com.yuaxi.ts6droid.cn"))
    }

    /** 短于 4 字符的片段是噪声，丢掉（否则会刷出一屏单字符行） */
    @Test
    fun shortRunsAreDropped() {
        val raw = byteArrayOf(1, 2, 3) +
            "ab".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(9, 9) +
            "abcdefgh".toByteArray(Charsets.US_ASCII)
        val out = CrashCatcher.readableTrace(raw)
        assertFalse("两个字符的片段不该单独成行", out.contains("ab\n"))
        assertTrue(out.contains("abcdefgh"))
    }

    private fun hex(s: String): ByteArray {
        val t = s.filter { !it.isWhitespace() }
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
