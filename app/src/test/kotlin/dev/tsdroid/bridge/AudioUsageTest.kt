package dev.tsdroid.bridge

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 钉住"输出通道 → AudioAttributes 用途"这个映射。
 *
 * 它决定我们的音频进哪条策略（STRATEGY_MEDIA 还是 STRATEGY_PHONE），
 * 也就决定了切输出设备时会不会连带把同一条 mixer thread 上的音乐一起换掉 ——
 * 是这一块功能的地基，不该被随手改动而无人察觉。
 */
class AudioUsageTest {

    @Test
    fun `默认走媒体策略`() {
        assertEquals(AudioAttributes.USAGE_GAME, audioUsageFor(communicationChannel = false))
    }

    @Test
    fun `通话通道走通信策略`() {
        assertEquals(
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            audioUsageFor(communicationChannel = true),
        )
    }

    @Test
    fun `两个通道必须落在不同策略上`() {
        // 取值相同的话，"切通道"就等于什么都没做 —— 而用户会以为已经隔离了
        assertNotEquals(audioUsageFor(false), audioUsageFor(true))
    }
}
