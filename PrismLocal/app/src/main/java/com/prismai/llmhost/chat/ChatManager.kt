package com.prismai.llmhost.chat
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.ChatTitles
import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import com.prismai.llmhost.persistence.ConversationRepository
import com.prismai.llmhost.persistence.ConversationSnapshot
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.ui.UiEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Chat CRUD operations.
 *
 * Coordinates between [ServiceUiState] (in-memory state), [TranscriptStore]
 * (persistence), and [ChatSearchIndex] (in-memory search).
 * Cross-cutting concerns that depend on InferenceService (agent tool state,
 * native engine conversation reset) are handled by the caller via returned
 * result values.
 */
class ChatManager(
    private val context: Context,
    private val transcriptStore: TranscriptStore,
    private val searchIndex: ChatSearchIndex,
    private val uiState: ServiceUiState,
    private val eventBus: UiEventBus,
    private val scope: CoroutineScope,
    private val conversationRepository: ConversationRepository? = null,
) {
    private val ioMutex = Mutex()
    private val chatIndexRevision = AtomicLong(0L)
    private val transcriptCache = ConcurrentHashMap<String, List<TranscriptMessage>>()

    @Volatile
    var isRepositoryReady: Boolean = false
        private set

    // ── Public fields — accessed from InferenceService via delegation props ──
    @Volatile
    var nextTranscriptId = 1L
    var activeAssistantTranscriptId: Long? = null
    var lastTranscriptPersistAt = 0L

    companion object {
        private const val TAG = "ChatManager"
        private const val PREFS_NAME = "llm_host_prefs"
        private const val KEY_ACTIVE_CHAT = "active_chat"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    // ── Chat CRUD ────────────────────────────────────────────────────────

    fun createChat(): String {
        if (uiState._isGenerating.value) {
            eventBus.publish("Cancel generation before creating a new chat")
            return uiState._currentChatId.value.orEmpty()
        }
        return createChatInternal(ChatTitles.DEFAULT_TITLE)
    }

    fun createChatInternal(
        title: String,
        publishEvent: Boolean = false,
    ): String {
        persistTranscriptNow()
        val session = newSession(title = title, messageCount = 0)
        synchronized(lock) {
            uiState._chatSessions.value =
                (uiState._chatSessions.value + session).sortedByDescending { it.updatedAt }
            uiState._currentChatId.value = session.id
            uiState._transcript.value = emptyList()
            nextTranscriptId = 1L
            activeAssistantTranscriptId = null
            lastTranscriptPersistAt = 0L
        }
        transcriptCache[session.id] = emptyList()
        uiState.streamState.clear()
        persistChatIndex()
        persistTranscriptNow()
        prefs.edit().putString(KEY_ACTIVE_CHAT, session.id).apply()
        if (publishEvent) {
            eventBus.publish("Started a new chat to keep the transcript responsive")
        }
        return session.id
    }

    fun switchChat(chatId: String): Boolean {
        if (uiState._isGenerating.value) {
            eventBus.publish("Cancel generation before switching chats")
            return false
        }
        if (uiState._chatSessions.value.none { it.id == chatId }) {
            eventBus.publish("Chat no longer exists")
            return false
        }
        persistTranscriptNow()
        val restored = messagesForChat(chatId)
        synchronized(lock) {
            uiState._currentChatId.value = chatId
            uiState._transcript.value = restored
            nextTranscriptId = (restored.maxOfOrNull { it.id } ?: 0L) + 1L
            activeAssistantTranscriptId = null
            lastTranscriptPersistAt = 0L
        }
        uiState.streamState.clear()
        prefs.edit().putString(KEY_ACTIVE_CHAT, chatId).apply()
        return true
    }

    fun renameChat(chatId: String, title: String) {
        val safeTitle = title.replace(Regex("\\s+"), " ").trim().take(64)
        if (safeTitle.isBlank()) {
            eventBus.publish("Chat title cannot be empty")
            return
        }
        uiState._chatSessions.value = uiState._chatSessions.value
            .map { session ->
                if (session.id == chatId) {
                    session.copy(title = safeTitle, updatedAt = System.currentTimeMillis())
                } else {
                    session
                }
            }
            .sortedByDescending { it.updatedAt }
        persistChatIndex()
    }

    fun deleteChat(chatId: String): Boolean {
        if (uiState._isGenerating.value) {
            eventBus.publish("Cancel generation before deleting a chat")
            return false
        }

        val remaining = uiState._chatSessions.value.filterNot { it.id == chatId }
        transcriptCache.remove(chatId)
        runCatching { transcriptStore.transcriptFile(chatId).delete() }
            .onFailure { error -> Log.w(TAG, "failed to delete chat transcript", error) }

        if (remaining.isEmpty()) {
            synchronized(lock) {
                uiState._chatSessions.value = emptyList()
                uiState._currentChatId.value = null
                uiState._transcript.value = emptyList()
                nextTranscriptId = 1L
                activeAssistantTranscriptId = null
            }
            // Create a new chat to replace the deleted one
            createChat()
            return true
        }

        uiState._chatSessions.value = remaining.sortedByDescending { it.updatedAt }
        persistChatIndex()

        if (uiState._currentChatId.value == chatId) {
            // Switch to the first remaining chat without persist (already done above) or agent side effects
            val newId = remaining.first().id
            val restored = messagesForChat(newId)
            synchronized(lock) {
                uiState._currentChatId.value = newId
                uiState._transcript.value = restored
                nextTranscriptId = (restored.maxOfOrNull { it.id } ?: 0L) + 1L
                activeAssistantTranscriptId = null
                lastTranscriptPersistAt = 0L
            }
            uiState.streamState.clear()
            prefs.edit().putString(KEY_ACTIVE_CHAT, newId).apply()
        }
        return true
    }

    fun clearTranscript() {
        if (uiState._isGenerating.value) {
            eventBus.publish("Cancel generation before clearing chat")
            return
        }
        synchronized(lock) {
            uiState._transcript.value = emptyList()
            val chatId = uiState._currentChatId.value
            uiState._chatSessions.value = uiState._chatSessions.value.map { session ->
                if (session.id == chatId) {
                    session.copy(summary = null, summaryUntilMessageId = null)
                } else {
                    session
                }
            }
            nextTranscriptId = 1L
            activeAssistantTranscriptId = null
            lastTranscriptPersistAt = 0L
        }
        uiState.streamState.clear()
        uiState._currentChatId.value?.let { transcriptCache[it] = emptyList() }
        touchCurrentChat(emptyList(), updateTitle = false)
        runCatching {
            uiState._currentChatId.value?.let {
                transcriptStore.transcriptFile(it).delete()
            }
        }.onFailure { error ->
            Log.w(TAG, "failed to delete transcript", error)
        }
        persistChatIndex()
        // Persist an explicit empty snapshot. Deleting the legacy file alone
        // cannot tell the Room mirror that every message was cleared.
        persistTranscriptNow()
    }

    // ── Chat state persistence ───────────────────────────────────────────

    /** Reads only the legacy JSON backup for a one-time Room bootstrap. */
    fun legacySessionsForMigration(): List<ChatSession> {
        val indexedSessions = readChatIndex()
        if (indexedSessions.isNotEmpty()) return indexedSessions

        return listOf(
            run {
                val legacyMessages = transcriptStore.readTranscriptFile(transcriptStore.legacyTranscriptFile())
                val session = newSession(
                    title = transcriptStore.firstUserTitle(legacyMessages) ?: ChatTitles.DEFAULT_TITLE,
                    messageCount = legacyMessages.size,
                )
                transcriptStore.writeTranscriptFile(transcriptStore.transcriptFile(session.id), legacyMessages)
                session
            }
        )
    }

    suspend fun loadFromRepository(): Boolean {
        val repository = conversationRepository ?: return false
        val snapshot = repository.loadSnapshot()
        if (snapshot.sessions.isEmpty()) return false
        restoreSnapshot(snapshot)
        return true
    }

    fun restoreSnapshot(snapshot: ConversationSnapshot) {
        val sessions = snapshot.sessions.sortedByDescending { it.updatedAt }

        val activeChat = prefs
            .getString(KEY_ACTIVE_CHAT, null)
            ?.takeIf { candidate -> sessions.any { it.id == candidate } }
            ?: sessions.maxByOrNull { it.updatedAt }?.id
            ?: newSession(ChatTitles.DEFAULT_TITLE, 0).id

        val activeSession = sessions.firstOrNull { it.id == activeChat }
            ?: newSession(ChatTitles.DEFAULT_TITLE, 0)

        val normalizedSessions = if (sessions.any { it.id == activeSession.id }) {
            sessions
        } else {
            sessions + activeSession
        }.sortedByDescending { it.updatedAt }

        val restored = snapshot.messagesByChat[activeSession.id].orEmpty()

        transcriptCache.clear()
        snapshot.messagesByChat.forEach { (chatId, messages) ->
            transcriptCache[chatId] = messages
        }

        synchronized(lock) {
            uiState._chatSessions.value = normalizedSessions
            uiState._currentChatId.value = activeSession.id
            uiState._transcript.value = restored
            nextTranscriptId = (restored.maxOfOrNull { it.id } ?: 0L) + 1L
        }

        searchIndex.refresh(normalizedSessions, snapshot.messagesByChat)
        isRepositoryReady = true

        prefs.edit().putString(KEY_ACTIVE_CHAT, activeSession.id).apply()
        persistSnapshotJsonBackup(snapshot)
    }

    fun messagesForChat(chatId: String): List<TranscriptMessage> =
        if (uiState._currentChatId.value == chatId) {
            uiState._transcript.value
        } else {
            transcriptCache[chatId].orEmpty()
        }

    fun currentSession(): ChatSession? = uiState._currentChatId.value?.let { chatId ->
        uiState._chatSessions.value.firstOrNull { it.id == chatId }
    }

    suspend fun updateSummary(chatId: String, summary: String, untilId: Long): Boolean {
        val normalized = summary.trim()
        if (normalized.isBlank()) return false
        val now = System.currentTimeMillis()
        val persisted = ioMutex.withLock {
            val updated = conversationRepository?.updateSummary(chatId, normalized, untilId, now) ?: false
            if (updated) {
                synchronized(lock) {
                    uiState._chatSessions.value = uiState._chatSessions.value
                        .map { session ->
                            if (session.id == chatId) {
                                session.copy(
                                    summary = normalized,
                                    summaryUntilMessageId = untilId,
                                    updatedAt = now,
                                )
                            } else {
                                session
                            }
                        }
                        .sortedByDescending { it.updatedAt }
                }
            }
            updated
        }
        if (!persisted) return false
        persistChatIndex()
        return true
    }

    fun readChatIndex(): List<ChatSession> =
        runCatching {
            val file = transcriptStore.chatIndexFile()
            if (!file.isFile) {
                return@runCatching emptyList<ChatSession>()
            }
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ChatSession(
                            id = item.getString("id"),
                            title = item.optString("title", ChatTitles.DEFAULT_TITLE),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                            modelId = item.optString("modelId").takeIf { it.isNotBlank() },
                            messageCount = item.optInt("messageCount", 0),
                            summary = item.optString("summary").takeIf { it.isNotBlank() },
                            summaryUntilMessageId = item.optLong("summaryUntilMessageId", -1L)
                                .takeIf { it >= 0L },
                        )
                    )
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "failed to load chat index", error)
        }.getOrDefault(emptyList())

    fun persistChatIndex() {
        val revision = chatIndexRevision.incrementAndGet()
        val sessionsSnapshot = uiState._chatSessions.value
        scope.launch(Dispatchers.IO) {
            ioMutex.withLock {
                // IO coroutines can reach this mutex out of launch order. Only
                // the newest snapshot may replace the index or Room rows.
                if (revision != chatIndexRevision.get()) return@withLock

                runCatching {
                    conversationRepository?.syncSessions(sessionsSnapshot)
                }.onFailure { error ->
                    Log.e(TAG, "failed to persist chat index to Room", error)
                }

                // JSON is retained as a recoverable backup during the Room
                // cutover, but no longer gates the primary database write.
                runCatching {
                    writeChatIndexJson(sessionsSnapshot)
                }.onFailure { error ->
                    Log.w(TAG, "failed to write JSON chat-index backup", error)
                }
            }
        }
    }

    fun newSession(title: String, messageCount: Int): ChatSession {
        val now = System.currentTimeMillis()
        return ChatSession(
            id = "chat_${now}_${SystemClock.uptimeMillis()}",
            title = title,
            createdAt = now,
            updatedAt = now,
            modelId = uiState._currentModel.value,
            messageCount = messageCount,
        )
    }

    // ── Touch (timestamp / title update) ─────────────────────────────────

    fun touchCurrentChat(messages: List<TranscriptMessage>, updateTitle: Boolean) {
        val chatId = uiState._currentChatId.value ?: return
        val now = System.currentTimeMillis()
        uiState._chatSessions.value = uiState._chatSessions.value
            .map { session ->
                if (session.id == chatId) {
                    val shouldRetitle = updateTitle && session.title == ChatTitles.DEFAULT_TITLE
                    session.copy(
                        title = if (shouldRetitle) transcriptStore.firstUserTitle(messages) ?: session.title else session.title,
                        updatedAt = now,
                        modelId = uiState._currentModel.value ?: session.modelId,
                        messageCount = messages.size,
                    )
                } else {
                    session
                }
            }
            .sortedByDescending { it.updatedAt }
        persistChatIndex()
    }

    // ── Transcript message management ──────────────────────────────────────

    /**
     * Appends a message to the current transcript, auto-creating a chat if
     * none exists. Returns the assigned message id.
     */
    fun appendTranscriptMessage(role: TranscriptRole, text: String): Long {
        if (uiState._currentChatId.value == null) {
            createChat()
        }
        val id: Long
        val sum: String?
        synchronized(lock) {
            id = nextTranscriptId++
            sum = if (role == TranscriptRole.TOOL) {
                AgentToolProtocol.parseToolEvent(text)
                    ?.optString("summary")
                    ?.takeIf { it.isNotBlank() }
            } else null
            val mutable = uiState._transcript.value.toMutableList()
            mutable.add(TranscriptMessage(id, role, text, sum))
            uiState._transcript.value = mutable
            uiState._currentChatId.value?.let { transcriptCache[it] = mutable }
        }
        touchCurrentChat(uiState._transcript.value, updateTitle = role == TranscriptRole.USER)
        return id
    }

    /**
     * Updates the text (and optional tool summary) of an existing transcript
     * message identified by [id].
     */
    fun updateTranscriptMessage(id: Long, text: String) {
        synchronized(lock) {
            val list = uiState._transcript.value
            val index = list.indexOfLast { it.id == id }
            if (index >= 0) {
                val message = list[index]
                if (message.text != text) {
                    val mutableList = list.toMutableList()
                    val sum = if (message.role == TranscriptRole.TOOL) {
                        AgentToolProtocol.parseToolEvent(text)
                            ?.optString("summary")
                            ?.takeIf { it.isNotBlank() }
                    } else message.summary
                    mutableList[index] = message.copy(text = text, summary = sum)
                    uiState._transcript.value = mutableList
                    uiState._currentChatId.value?.let { transcriptCache[it] = mutableList }
                }
            }
        }
        // Intentionally does NOT touch/persist the chat index here. Streaming
        // calls this on a ~75 ms cadence; rewriting chat_index.json and
        // re-sorting every session per token is wasteful. The index is
        // persisted on append/rename/delete instead.
    }

    // ── Persist transcript to disk (thread-safe under ioMutex) ─────────────

    /** Persists transcript messages for [chatId] under [ioMutex] to avoid disk races. */
    suspend fun persistTranscript(chatId: String, messages: List<TranscriptMessage>) {
        transcriptCache[chatId] = messages
        ioMutex.withLock {
            // A delayed write for a deleted chat must not recreate its JSON
            // transcript or its Room parent row.
            val session = uiState._chatSessions.value.firstOrNull { it.id == chatId }
                ?: return@withLock
            searchIndex.update(chatId, messages, session.title)
            runCatching {
                conversationRepository?.syncChat(session, messages)
            }.onFailure { error ->
                Log.e(TAG, "failed to persist transcript to Room for $chatId", error)
            }

            runCatching {
                transcriptStore.writeTranscriptFile(transcriptStore.transcriptFile(chatId), messages)
            }.onFailure { error ->
                Log.w(TAG, "failed to write JSON transcript backup for $chatId", error)
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun persistSnapshotJsonBackup(snapshot: ConversationSnapshot) {
        scope.launch(Dispatchers.IO) {
            ioMutex.withLock {
                runCatching {
                    writeChatIndexJson(snapshot.sessions)
                    snapshot.messagesByChat.forEach { (chatId, messages) ->
                        transcriptStore.writeTranscriptFile(
                            transcriptStore.transcriptFile(chatId),
                            messages,
                        )
                    }
                    Log.d(TAG, "JSON conversation backup refreshed from Room")
                }.onFailure { error ->
                    Log.w(TAG, "failed to refresh JSON conversation backup from Room", error)
                }
            }
        }
    }

    private fun writeChatIndexJson(sessions: List<ChatSession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("createdAt", session.createdAt)
                    .put("updatedAt", session.updatedAt)
                    .put("modelId", session.modelId)
                    .put("messageCount", session.messageCount)
                    .put("summary", session.summary)
                    .put("summaryUntilMessageId", session.summaryUntilMessageId)
            )
        }
        val target = transcriptStore.chatIndexFile()
        val temp = File(context.filesDir, "chat_index.json.tmp")
        temp.writeText(array.toString())
        transcriptStore.promoteTempFile(temp, target)
    }

    private fun persistTranscriptNow() {
        val chatId = uiState._currentChatId.value ?: return
        val messages = uiState._transcript.value
        transcriptCache[chatId] = messages
        scope.launch(Dispatchers.IO) {
            persistTranscript(chatId, messages)
        }
    }
}
