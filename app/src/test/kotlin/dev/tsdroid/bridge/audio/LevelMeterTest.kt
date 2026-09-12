package dev.tsdroid.bridge.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * dBFS 计算的回归网。
 *
 * 这个函数是生产链路（判"谁在说话"）和设置页麦克风测试共用的同一把尺子 ——
 * 两边算法一旦漂移，就会出现"设置页显示超了门限，实际却不判为说话"这种自相矛盾。
 */
class LevelMeterTest {

    @Test
    fun silenceHitsTheFloor() {
        assertEquals(-120.0, dbfsOf(ShortArray(960), 960), 0.001)
    }

    @Test
    fun zeroSamplesHitsTheFloor() {
        assertEquals(-120.0, dbfsOf(ShortArray(0), 0), 0.001)
    }

    @Test
    fun fullScaleIsAboutZeroDb() {
        val pcm = ShortArray(960) { Short.MAX_VALUE }   // 32767 / 32768 ≈ -0.0003 dB
        assertEquals(0.0, dbfsOf(pcm, 960), 0.01)
    }

    @Test
    fun halfScaleIsAboutMinusSixDb() {
        val pcm = ShortArray(960) { 16384 }             // 20*log10(16384/32768) = -6.02 dB
        assertEquals(-6.02, dbfsOf(pcm, 960), 0.05)
    }

    /** 门控的判据就是"电平 > 门限"，这里把默认门限 -40 dBFS 两侧钉一下 */
    @Test
    fun defaultThresholdSeparatesSpeechFromSilence() {
        val quiet = ShortArray(960) { 100 }             // ≈ -50 dBFS：低于默认门限，不该发
        val loud = ShortArray(960) { 3000 }             // ≈ -20.8 dBFS：高于默认门限，该发
        assertEquals(-50.2, dbfsOf(quiet, 960), 0.5)
        assertEquals(-20.8, dbfsOf(loud, 960), 0.5)
    }
}
