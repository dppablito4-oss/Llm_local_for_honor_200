package com.prismai.llmhost.chat

import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.TranscriptMessage
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** In-memory search projection rebuilt from the Room conversation snapshot. */
class ChatSearchIndex {
    private val index = ConcurrentHashMap<String, String>()

    fun refresh(
        sessions: List<ChatSession>,
        messagesByChat: Map<String, List<TranscriptMessage>>,
    ) {
        index.clear()
        sessions.forEach { session ->
            update(session.id, messagesByChat[session.id].orEmpty(), session.title)
        }
    }

    fun update(
        chatId: String,
        messages: List<TranscriptMessage>,
        sessionTitle: String?,
    ) {
        val haystack = buildString {
            append(sessionTitle.orEmpty()).append(' ')
            messages.forEach { message ->
                append(message.text.take(300)).append(' ')
            }
        }
        index[chatId] = haystack.lowercase(Locale.US)
    }

    fun get(chatId: String): String? = index[chatId]
}
