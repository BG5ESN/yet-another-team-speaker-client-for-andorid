package dev.tsdroid.bridge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测 [resolveSavedDevice]：把"上次选的那副耳机"在当前设备列表里认回来。
 * 真实场景是蓝牙耳机断开再连 —— 设备 id 通常变了，但 type 和商品名还在。
 */
class AudioRouteTest {

    private fun dev(id: Int, name: String, type: Int) = AudioRouteDevice(id, name, type)

    private val speakers = dev(1, "", 2)                       // TYPE_BUILTIN_SPEAKER
    private val earpiece = dev(2, "", 1)                       // TYPE_BUILTIN_EARPIECE
    private val buds = dev(37, "Galaxy Buds", 8)               // TYPE_BLUETOOTH_A2DP
    private val wired = dev(41, "Wired Headset", 3)            // TYPE_WIRED_HEADSET

    @Test
    fun `跟随系统时永远返回 null`() {
        assertNull(resolveSavedDevice(FOLLOW_SYSTEM, 0, "", listOf(speakers, buds)))
        assertNull(resolveSavedDevice(FOLLOW_SYSTEM, 8, "Galaxy Buds", listOf(buds)))
    }

    @Test
    fun `id 还在时按 id 精确匹配`() {
        // 耳机换了个商品名（用户改名）但 id 没变，仍应认出来
        val renamed = dev(37, "我的耳机", 8)
        assertEquals(37, resolveSavedDevice(37, 8, "Galaxy Buds", listOf(speakers, renamed))?.id)
    }

    @Test
    fun `id 变了按 type 加名字认回来`() {
        // 真实场景：蓝牙重连后 id 从 37 变成 52，商品名没变
        val reconnected = dev(52, "Galaxy Buds", 8)
        assertEquals(52, resolveSavedDevice(37, 8, "Galaxy Buds", listOf(speakers, reconnected))?.id)
    }

    @Test
    fun `设备不在列表里返回 null 交给调用方回退`() {
        // 耳机拔了 —— 列表里只剩扬声器和听筒
        assertNull(resolveSavedDevice(37, 8, "Galaxy Buds", listOf(speakers, earpiece)))
    }

    @Test
    fun `名字相同但类型不同不算同一个设备`() {
        // 同名不同 type（比如 SCO 与 A2DP 通道同名）不能混认，否则会切到错的那条通道
        val sco = dev(60, "Galaxy Buds", 7)
        assertNull(resolveSavedDevice(37, 8, "Galaxy Buds", listOf(sco)))
    }

    @Test
    fun `内置设备名字为空也能按 id 认回来`() {
        // 扬声器 / 听筒的 productName 是空串，id 匹配必须照常工作
        assertEquals(2, resolveSavedDevice(2, 1, "", listOf(speakers, earpiece))?.id)
    }

    @Test
    fun `有线耳机插拔后仍能认回`() {
        assertTrue(resolveSavedDevice(41, 3, "Wired Headset", listOf(wired, speakers))?.id == 41)
    }
}
