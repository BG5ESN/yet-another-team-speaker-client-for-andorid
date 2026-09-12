package dev.tsdroid.model

import dev.tslib.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件归约（text_message → 聊天消息）的回归网。
 *
 * 这段逻辑原先埋在 ServerViewModel.handleEvent 的 128 行里，跟 StateFlow/未读/落盘混在一起，
 * 除了真机手测没有任何验证手段。抽成 MessageParser 后可以在 JVM 里把"该丢弃什么、该显示什么"
 * 逐条钉死。
 *
 * 注意：ts3file:// 附件分支用 android.net.Uri 取 query 参数，纯 JVM 单测跑不到（android.jar 是桩），
 * 那条路只能靠真机验证。
 */
class MessageParserTest {

    private val logs = mutableListOf<String>()

    private fun evt(vararg pairs: Pair<String, Any?>): Event {
        val m = HashMap<String, Any?>()
        pairs.forEach { (k, v) -> m[k] = v }
        return Event("text_message", m)
    }

    private fun parse(event: Event, myId: Int? = null): ParsedMessage? =
        MessageParser.parse(event, myId) { logs += it }

    // ── 1) 非 text_message 直接忽略 ──
    @Test
    fun nonTextMessageIgnored() {
        assertNull(parse(Event("user_joined", emptyMap())))
    }

    // ── 2) 关键字段缺失 → 忽略，且不抛异常 ──
    @Test
    fun missingFieldsIgnored() {
        assertNull(parse(evt("target" to "channel", "sender_name" to "张三")))   // 缺 message
        assertNull(parse(evt("sender_name" to "张三", "message" to "hi")))       // 缺 target
        assertNull(parse(evt("target" to "channel", "message" to "hi")))         // 缺 sender_name
        assertTrue("应留下诊断日志", logs.any { it.contains("missing required fields") })
    }

    // ── 3) 服务器滥用/泛洪保护提示不算用户消息 ──
    @Test
    fun abuseProtectionNoiseIgnored() {
        assertNull(parse(evt("target" to "channel", "sender_name" to "Server",
            "message" to "滥用保护：你发送消息的速度太快了")))
        assertNull(parse(evt("target" to "channel", "sender_name" to "Server",
            "message" to "flood protection active")))
        assertNull(parse(evt("target" to "channel", "sender_name" to "Server",
            "message" to "Action currently not possible")))
    }

    // ── 4) 自己发的消息跳过（本地已经加进列表了）──
    @Test
    fun ownMessageSkipped() {
        assertNull(parse(evt("target" to "channel", "sender_name" to "我",
            "sender_id" to 42, "message" to "hi"), myId = 42))
        // clientId 还不知道时不该误杀（保持原行为）
        assertNotNull(parse(evt("target" to "channel", "sender_name" to "我",
            "sender_id" to 42, "message" to "hi"), myId = null))
    }

    // ── 5) 频道消息：首尾空白被 trim ──
    @Test
    fun channelMessageParsed() {
        val ch = parse(evt("target" to "channel", "sender_name" to "张三",
            "sender_id" to 7, "message" to "  大家好  ")) as ParsedMessage.Channel
        assertEquals("张三", ch.message.sender)
        assertEquals("大家好", ch.message.text)
        assertFalse(ch.message.isPrivate)
        assertFalse(ch.message.isMe)
        assertNull(ch.message.fileAttachment)
    }

    // ── 6) 私聊消息带 userId ──
    @Test
    fun privateMessageParsed() {
        val pm = parse(evt("target" to "private", "sender_name" to "李四",
            "sender_id" to 9, "message" to "在吗")) as ParsedMessage.Private
        assertEquals(9, pm.userId)
        assertTrue(pm.message.isPrivate)
        assertEquals(9, pm.message.senderId)
    }

    // ── 7) 私聊缺 sender_id → 忽略 ──
    @Test
    fun privateWithoutSenderIdIgnored() {
        assertNull(parse(evt("target" to "private", "sender_name" to "李四", "message" to "在吗")))
        assertTrue(logs.any { it.contains("without sender ID") })
    }

    // ── 8) 未知 target → 忽略 ──
    @Test
    fun unknownTargetIgnored() {
        assertNull(parse(evt("target" to "server", "sender_name" to "x", "message" to "y")))
    }

    // ── 9) 正文里的内嵌 NUL 被清掉 ──
    @Test
    fun nulBytesStrippedFromText() {
        val ch = parse(evt("target" to "channel", "sender_name" to "x",
            "message" to "a\u0000b")) as ParsedMessage.Channel
        assertEquals("ab", ch.message.text)
    }

    // ── 10) TS5 JSON 附件：解析成 FileAttachment，正文显示文件名 ──
    @Test
    fun ts5JsonAttachmentParsed() {
        val json = """{"msg_type":"ts.file.myts","file_name":"report.pdf","file_size":12345,"file_id":"abc123"}"""
        val ch = parse(evt("target" to "channel", "sender_name" to "x",
            "message" to json)) as ParsedMessage.Channel
        val a = ch.message.fileAttachment!!
        assertEquals("report.pdf", a.fileName)
        assertEquals(12345L, a.fileSize)
        assertEquals("abc123", a.fileId)
        assertFalse(a.isImage)
        assertEquals("有附件时正文应显示文件名", "report.pdf", ch.message.text)
    }

    // ── 11) 图片扩展名认得出（大小写不敏感）──
    @Test
    fun imageExtensionDetected() {
        val json = """{"msg_type":"ts.file.myts","file_name":"pic.PNG","file_size":1,"file_id":"i1"}"""
        val a = (parse(evt("target" to "channel", "sender_name" to "x",
            "message" to json)) as ParsedMessage.Channel).message.fileAttachment!!
        assertTrue(a.isImage)
    }

    // ── 12) msg_type 不对 → 不当作附件，原样当正文 ──
    @Test
    fun nonAttachmentJsonFallsThrough() {
        val other = """{"msg_type":"something.else","file_name":"x.pdf","file_id":"1"}"""
        val ch = parse(evt("target" to "channel", "sender_name" to "x",
            "message" to other)) as ParsedMessage.Channel
        assertNull(ch.message.fileAttachment)
        assertEquals(other, ch.message.text)
    }

    // ── 13) 缺 file_id → 不算有效附件 ──
    @Test
    fun jsonWithoutFileIdNotAttachment() {
        val json = """{"msg_type":"ts.file.myts","file_name":"x.pdf"}"""
        val ch = parse(evt("target" to "channel", "sender_name" to "x",
            "message" to json)) as ParsedMessage.Channel
        assertNull(ch.message.fileAttachment)
    }

    // ── 14) 超长正文不尝试解析附件（也不该抛异常）──
    @Test
    fun veryLongTextSkipsAttachmentParsing() {
        val long = """{"msg_type":"ts.file.myts",""" + "x".repeat(20_000)
        val ch = parse(evt("target" to "channel", "sender_name" to "x",
            "message" to long)) as ParsedMessage.Channel
        assertNull(ch.message.fileAttachment)
        assertTrue(logs.any { it.contains("too long") })
    }

    // ── 15) 畸形 JSON 不炸（当正文处理）──
    @Test
    fun malformedJsonDoesNotThrow() {
        val broken = """{"msg_type":"ts.file.myts", "file_name":"""
        val ch = parse(evt("target" to "channel", "sender_name" to "x",
            "message" to broken)) as ParsedMessage.Channel
        assertNull(ch.message.fileAttachment)
    }
}
