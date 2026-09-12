package dev.tsdroid.model

import dev.tslib.Event

/** text_message 事件归约出来的东西：要么发到频道，要么是某个人的私聊。 */
sealed class ParsedMessage {
    data class Channel(val message: ChatMessage) : ParsedMessage()
    data class Private(val userId: Int, val message: ChatMessage) : ParsedMessage()
}

/**
 * 把底层投上来的 `text_message` 事件解析成可显示的聊天消息。
 *
 * 纯逻辑：只做"字段校验 → 噪声过滤 → 附件解析 → 分流"，**不碰任何状态**。
 * 副作用（写 StateFlow、未读计数、落盘）留在 ViewModel —— 这样这段最容易出 bug 的
 * 解析逻辑可以脱离设备做 JVM 单测。
 *
 * 日志走注入（生产接 Log.d，测试期可收集），所以本类不依赖 android.util.Log。
 *
 * 已知边界：`parseFileAttachment` 的 ts3file:// 分支用 `android.net.Uri` 取 query 参数，
 * 纯 JVM 单测跑不到那条路（见 MessageParserTest 里的说明），走真机验证。
 */
object MessageParser {

    /** 超过这个长度就不尝试解析附件了（避免把巨型文本灌进 JSON 解析） */
    private const val MAX_ATTACHMENT_PARSE_LEN = 10_000

    /** 服务器滥用/泛洪保护之类的系统提示，不是用户消息 */
    private val SYSTEM_NOISE = listOf(
        "滥用保护",
        "abuse protection",
        "flood protection",
        "spam protection",
        "Cannot perform this action due to",
        "无法采取此动作",
        "Action currently not possible",
    )

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /**
     * @param myClientId 自己的 clientId：自己发的消息本地已经加过，这里要跳过
     * @return null = 这条事件不该产生消息（类型不符 / 字段缺失 / 系统噪声 / 自己发的 / target 未知）
     */
    fun parse(event: Event, myClientId: Int?, log: (String) -> Unit = {}): ParsedMessage? {
        if (event.type != "text_message") return null

        val target = event.data["target"] as? String
        val sender = event.data["sender_name"] as? String
        val senderId = (event.data["sender_id"] as? Number)?.toInt()
        val text = event.data["message"] as? String
        if (target == null || sender == null || text == null) {
            log("Invalid text_message: missing required fields")
            return null
        }

        if (SYSTEM_NOISE.any { text.contains(it) }) {
            log("Skipping system abuse protection message: ${text.take(80)}")
            return null
        }

        // 自己的消息跳过（本地发送时已经加进列表了）
        if (myClientId != null && senderId == myClientId) return null

        val attachment = if (text.length > MAX_ATTACHMENT_PARSE_LEN) {
            log("Message too long, skipping file attachment parsing")
            null
        } else {
            try {
                parseFileAttachment(text)
            } catch (e: Exception) {
                log("Error parsing file attachment: $e")
                null
            }
        }
        // 有附件就显示文件名，否则用清理过的正文（去掉内嵌 NUL，避免显示异常）
        val displayText = attachment?.fileName ?: text.replace("\u0000", "").trim()

        return when (target) {
            "private" -> {
                val id = senderId
                if (id == null) {
                    log("Private message without sender ID")
                    return null
                }
                ParsedMessage.Private(
                    userId = id,
                    message = ChatMessage(
                        sender = sender,
                        text = displayText,
                        isPrivate = true,
                        senderId = id,
                        fileAttachment = attachment,
                    ),
                )
            }
            "channel" -> ParsedMessage.Channel(
                ChatMessage(sender = sender, text = displayText, fileAttachment = attachment),
            )
            else -> {
                log("Unknown message target: $target")
                null
            }
        }
    }

    /**
     * 从消息文本里解析文件/图片附件。两种格式：
     *  · TS5 MyTeamSpeak JSON：{"msg_type":"ts.file.myts", ...}
     *  · TS3 文件传输 URL：ts3file://host?port=..&channel=..&filename=..&size=..
     */
    fun parseFileAttachment(text: String): FileAttachment? {
        // TS5 MyTeamSpeak JSON format
        if (text.startsWith("{\"msg_type\":")) {
            return try {
                val json = org.json.JSONObject(text)
                if (json.optString("msg_type") != "ts.file.myts") return null
                val fileName = json.optString("file_name", "")
                val fileSize = json.optLong("file_size", 0)
                val fileId = json.optString("file_id", "")
                if (fileName.isEmpty() || fileId.isEmpty()) return null
                val ext = fileName.substringAfterLast('.', "").lowercase()
                FileAttachment(fileName, fileSize, fileId, ext in IMAGE_EXT)
            } catch (_: Exception) {
                null
            }
        }
        // TS3 file transfer URL format
        val ts3Start = text.indexOf("ts3file://")
        if (ts3Start >= 0) {
            return try {
                // 截到空白为止就是 URL 本体
                val urlStr = text.substring(ts3Start).takeWhile { !it.isWhitespace() }
                val uri = android.net.Uri.parse(urlStr)
                val fileName = uri.getQueryParameter("filename") ?: return null
                val fileSize = uri.getQueryParameter("size")?.toLongOrNull() ?: 0L
                val channelId = uri.getQueryParameter("channel")?.toLongOrNull() ?: 0L
                val ext = fileName.substringAfterLast('.', "").lowercase()
                FileAttachment(fileName, fileSize, fileId = "", isImage = ext in IMAGE_EXT, channelId = channelId)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }
}
