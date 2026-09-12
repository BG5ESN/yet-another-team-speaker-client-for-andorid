package dev.tsdroid.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送门控的回归网。
 *
 * 背景：原先 micDb 算出来只喂给了说话圈 UI，而 encode + sendAudio 在判定之外无条件执行 ——
 * 于是 VA（连续说话）模式等于"开着麦一直发"：PC 端看到的发送灯因此常亮，
 * 而官方客户端是跟着声音有没有闪的。
 */
class AudioTxGateTest {

    /** PTT：按住就是要发，不做门控 —— 没出声也照发（用户已明确表达意图，不该被 VAD 拦） */
    @Test
    fun pttTransmitsRegardlessOfVoiceActivity() {
        assertTrue(
            "PTT 且判定在说话 → 发",
            shouldTransmitFrame(voiceActivatedMode = false, isVoiceActive = true),
        )
        assertTrue(
            "PTT 且没判定在说话 → 仍然发（按住即发）",
            shouldTransmitFrame(voiceActivatedMode = false, isVoiceActive = false),
        )
    }

    /** VA：只有判定"在说话"才发，静音段不上行 */
    @Test
    fun voiceActivatedTransmitsOnlyWhenSpeaking() {
        assertTrue(
            "VA 且判定在说话 → 发",
            shouldTransmitFrame(voiceActivatedMode = true, isVoiceActive = true),
        )
        assertFalse(
            "VA 且静音 → 不编码不发送（本次修复点）",
            shouldTransmitFrame(voiceActivatedMode = true, isVoiceActive = false),
        )
    }
}
