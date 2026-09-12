package dev.tsdroid.bridge.audio

/**
 * 一帧 PCM 的电平（dBFS，16-bit 满量程 = 0 dBFS）。全静音返回 -120。
 *
 * 抽成公共函数是为了让两处用**同一把尺子**：
 *  · AudioBridge 判"谁在说话"（播放侧解码后的真实帧）
 *  · 设置页的麦克风 VA 测试（采集侧）
 * 公式一致，才能保证"设置页里看到超了门限" ⟺ "实际会被判为说话"。
 */
/** 判"在说话"的保持时间：电平超过门限后，继续算作说话这么久。
 *  生产门控（AudioBridge 采集循环）和设置页的麦克风测试共用它 —— 两边规则必须一致。 */
const val VOICE_HANGOVER_MS = 250L

fun dbfsOf(pcm: ShortArray, samples: Int): Double {
    if (samples <= 0) return -120.0
    var sum = 0.0
    for (i in 0 until samples) {
        val v = pcm[i].toDouble()
        sum += v * v
    }
    val rms = kotlin.math.sqrt(sum / samples)
    if (rms <= 1.0) return -120.0
    return 20.0 * kotlin.math.log10(rms / 32768.0)
}
