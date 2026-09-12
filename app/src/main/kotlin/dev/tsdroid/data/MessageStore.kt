package dev.tsdroid.data

import android.content.Context
import android.util.Log
import dev.tsdroid.model.ChatMessage
import dev.tsdroid.model.FileAttachment
import java.io.File

class MessageStore(private val context: Context) {

    companion object {
        private const val TAG = "MessageStore"
        private const val MAX_MESSAGES = 500
    }

    private val messagesDir = File(context.filesDir, "messages")

    fun load(serverAddress: String): Pair<List<ChatMessage>, Map<Int, List<ChatMessage>>> {
        val file = fileFor(serverAddress)
        if (!file.exists()) return Pair(emptyList(), emptyMap())
        return try {
            val json = file.readText()
            parseServerMessages(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load messages for $serverAddress", e)
            Pair(emptyList(), emptyMap())
        }
    }

    fun save(
        serverAddress: String,
        channelMessages: List<ChatMessage>,
        privateMessages: Map<Int, List<ChatMessage>>,
    ) {
        try {
            messagesDir.mkdirs()
            val json = serializeServerMessages(channelMessages, privateMessages)
            fileFor(serverAddress).writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save messages for $serverAddress", e)
        }
    }

    private fun fileFor(serverAddress: String): File {
        return File(messagesDir, sanitizeFilename(serverAddress) + ".json")
    }

    private fun sanitizeFilename(address: String): String {
        return address.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    // --- Serialization ---

    private fun serializeServerMessages(
        channel: List<ChatMessage>,
        private_: Map<Int, List<ChatMessage>>,
    ): String {
        val sb = StringBuilder()
        sb.append("{\"channel\":[")
        val trimmedChannel = channel.takeLast(MAX_MESSAGES)
        trimmedChannel.forEachIndexed { i, msg ->
            if (i > 0) sb.append(',')
            sb.append(serializeMessage(msg))
        }
        sb.append("],\"private\":{")
        var first = true
        for ((userId, msgs) in private_) {
            if (!first) sb.append(',')
            first = false
            val trimmed = msgs.takeLast(MAX_MESSAGES)
            sb.append("\"$userId\":[")
            trimmed.forEachIndexed { i, msg ->
                if (i > 0) sb.append(',')
                sb.append(serializeMessage(msg))
            }
            sb.append(']')
        }
        sb.append("}}")
        return sb.toString()
    }

    private fun serializeMessage(msg: ChatMessage): String {
        val base = """{"s":"${escape(msg.sender)}","t":"${escape(msg.text)}","ts":${msg.timestamp},"me":${msg.isMe},"sid":${msg.senderId}"""
        val fa = msg.fileAttachment
        return if (fa != null) {
            """$base,"fa":{"fn":"${escape(fa.fileName)}","fs":${fa.fileSize},"fi":"${escape(fa.fileId)}","im":${fa.isImage},"ch":${fa.channelId}}}"""
        } else {
            "$base}"
        }
    }

    // --- Parsing ---

    private fun parseServerMessages(json: String): Pair<List<ChatMessage>, Map<Int, List<ChatMessage>>> {
        if (json.isBlank()) return Pair(emptyList(), emptyMap())

        val channelMessages = mutableListOf<ChatMessage>()
        val privateMessages = mutableMapOf<Int, List<ChatMessage>>()

        // Find "channel":[...] array
        val channelStart = json.indexOf("\"channel\":[")
        if (channelStart >= 0) {
            val arrayStart = json.indexOf('[', channelStart)
            val arrayEnd = findMatchingBracket(json, arrayStart)
            if (arrayEnd > arrayStart) {
                val arrayContent = json.substring(arrayStart + 1, arrayEnd)
                channelMessages.addAll(parseMessageArray(arrayContent, isPrivate = false))
            }
        }

        // Find "private":{...} object
        val privateStart = json.indexOf("\"private\":{")
        if (privateStart >= 0) {
            val objStart = json.indexOf('{', privateStart + 10)
            val objEnd = findMatchingBrace(json, objStart)
            if (objEnd > objStart) {
                val objContent = json.substring(objStart + 1, objEnd)
                parsePrivateObject(objContent, privateMessages)
            }
        }

        return Pair(channelMessages, privateMessages)
    }

    private fun parsePrivateObject(content: String, result: MutableMap<Int, List<ChatMessage>>) {
        // Pattern: "42":[...], "55":[...]
        var pos = 0
        while (pos < content.length) {
            // Find next key
            val keyStart = content.indexOf('"', pos)
            if (keyStart < 0) break
            val keyEnd = content.indexOf('"', keyStart + 1)
            if (keyEnd < 0) break
            val userId = content.substring(keyStart + 1, keyEnd).toIntOrNull()

            // Find array
            val arrStart = content.indexOf('[', keyEnd)
            if (arrStart < 0) break
            val arrEnd = findMatchingBracket(content, arrStart)
            if (arrEnd < 0) break

            if (userId != null) {
                val arrayContent = content.substring(arrStart + 1, arrEnd)
                result[userId] = parseMessageArray(arrayContent, isPrivate = true)
            }
            pos = arrEnd + 1
        }
    }

    private fun parseMessageArray(content: String, isPrivate: Boolean): List<ChatMessage> {
        if (content.isBlank()) return emptyList()
        val messages = mutableListOf<ChatMessage>()
        var pos = 0
        while (pos < content.length) {
            val objStart = content.indexOf('{', pos)
            if (objStart < 0) break
            val objEnd = findMatchingBrace(content, objStart)
            if (objEnd < 0) break
            val msgJson = content.substring(objStart + 1, objEnd)
            parseMessage(msgJson, isPrivate)?.let { messages.add(it) }
            pos = objEnd + 1
        }
        return messages
    }

    private fun parseMessage(fields: String, isPrivate: Boolean): ChatMessage? {
        return try {
            val sender = extractStringField(fields, "s") ?: return null
            val text = extractStringField(fields, "t") ?: return null
            val ts = extractLongField(fields, "ts") ?: System.currentTimeMillis()
            val me = extractBoolField(fields, "me")
            val sid = extractIntField(fields, "sid") ?: 0
            val fa = parseFileAttachmentField(fields)
            ChatMessage(
                sender = sender,
                text = text,
                timestamp = ts,
                isMe = me,
                isPrivate = isPrivate,
                senderId = sid,
                fileAttachment = fa,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message: ${e.message}")
            null
        }
    }

    private fun parseFileAttachmentField(fields: String): FileAttachment? {
        val faPattern = "\"fa\":{"
        val faStart = fields.indexOf(faPattern)
        if (faStart < 0) return null
        val objStart = fields.indexOf('{', faStart)
        val objEnd = findMatchingBrace(fields, objStart)
        if (objEnd < 0) return null
        val faFields = fields.substring(objStart + 1, objEnd)
        val fn = extractStringField(faFields, "fn") ?: return null
        val fs = extractLongField(faFields, "fs") ?: 0
        val fi = extractStringField(faFields, "fi") ?: return null
        val im = extractBoolField(faFields, "im")
        val ch = extractLongField(faFields, "ch") ?: 0L
        return FileAttachment(fn, fs, fi, im, channelId = ch)
    }

    // --- Field extraction helpers ---

    private fun extractStringField(json: String, key: String): String? {
        val pattern = "\"$key\":\""
        val start = json.indexOf(pattern)
        if (start < 0) return null
        val valStart = start + pattern.length
        val valEnd = findUnescapedQuote(json, valStart)
        if (valEnd < 0) return null
        return unescape(json.substring(valStart, valEnd))
    }

    private fun extractLongField(json: String, key: String): Long? {
        val pattern = "\"$key\":"
        val start = json.indexOf(pattern)
        if (start < 0) return null
        val valStart = start + pattern.length
        val valEnd = json.indexOfFirst(valStart) { it == ',' || it == '}' }
        if (valEnd < 0) return null
        return json.substring(valStart, valEnd).trim().toLongOrNull()
    }

    private fun extractIntField(json: String, key: String): Int? {
        return extractLongField(json, key)?.toInt()
    }

    private fun extractBoolField(json: String, key: String): Boolean {
        val pattern = "\"$key\":"
        val start = json.indexOf(pattern)
        if (start < 0) return false
        val valStart = start + pattern.length
        return json.substring(valStart).trimStart().startsWith("true")
    }

    private fun findUnescapedQuote(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '"') {
                // Count preceding backslashes
                var backslashes = 0
                var j = i - 1
                while (j >= from && s[j] == '\\') { backslashes++; j-- }
                if (backslashes % 2 == 0) return i
            }
            i++
        }
        return -1
    }

    private fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) {
            if (predicate(this[i])) return i
        }
        return -1
    }

    // --- Bracket matching ---

    private fun findMatchingBracket(s: String, openPos: Int): Int {
        return findMatching(s, openPos, '[', ']')
    }

    private fun findMatchingBrace(s: String, openPos: Int): Int {
        return findMatching(s, openPos, '{', '}')
    }

    private fun findMatching(s: String, openPos: Int, open: Char, close: Char): Int {
        var depth = 0
        var inString = false
        var i = openPos
        while (i < s.length) {
            val c = s[i]
            if (c == '"' && (i == 0 || s[i - 1] != '\\')) {
                inString = !inString
            } else if (!inString) {
                if (c == open) depth++
                else if (c == close) {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    // --- Escaping ---

    private fun escape(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(s[i]); i++ }
                }
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }
}
