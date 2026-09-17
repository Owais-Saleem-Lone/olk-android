package com.openlibrarykashmir.olk.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.repository.ChatInfo
import com.openlibrarykashmir.olk.core.data.repository.ChatMessage
import com.openlibrarykashmir.olk.core.data.repository.MessagesRepository
import com.openlibrarykashmir.olk.core.data.repository.SendOutcome
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.ui.parseTimestamp
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data object NotFound : ChatUiState
    data class Error(val message: String) : ChatUiState
    data class Ready(
        val info: ChatInfo,
        val viewerId: String,
        val messages: List<ChatMessage>,
        val draft: String = "",
        val isSending: Boolean = false,
    ) : ChatUiState {
        val canMessage: Boolean get() = info.status in MessagesRepository.MESSAGING_STATUSES
        val canSend: Boolean
            get() = canMessage && !isSending && draft.isNotBlank() && draft.trim().length <= MessagesRepository.MAX_LENGTH
    }
}

class ChatViewModel(
    private val requestId: String,
    private val repository: MessagesRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var liveJob: Job? = null

    /**
     * Called when the screen starts: subscribe first, then load history, so a
     * message that arrives in between is caught by one or the other (and merged
     * by id if both see it). Returning from the background reloads for the same
     * reason — nothing was listening while the app was stopped.
     */
    fun start() {
        val viewerId = auth.currentUserId() ?: run {
            _uiState.value = ChatUiState.Error("Your session has ended. Sign in again.")
            return
        }
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            repository.newMessages(requestId).collect { message -> merge(listOf(message)) }
        }
        viewModelScope.launch {
            runCatching {
                val info = async { repository.chatInfo(requestId, viewerId) }
                val history = async { repository.history(requestId) }
                info.await() to history.await()
            }.onSuccess { (info, history) ->
                if (info == null) {
                    _uiState.value = ChatUiState.NotFound
                    return@onSuccess
                }
                _uiState.update { current ->
                    if (current is ChatUiState.Ready) {
                        current.copy(info = info, messages = mergeById(current.messages, history))
                    } else {
                        ChatUiState.Ready(info = info, viewerId = viewerId, messages = history)
                    }
                }
            }.onFailure { throwable ->
                if (_uiState.value !is ChatUiState.Ready) {
                    _uiState.value = ChatUiState.Error(throwable.toUserMessage())
                } else {
                    _messages.send(throwable.toUserMessage())
                }
            }
        }
    }

    /** Called when the screen stops; the Realtime channel is not kept open in the background. */
    fun stop() {
        liveJob?.cancel()
        liveJob = null
    }

    fun onDraftChange(value: String) = updateReady { it.copy(draft = value.take(MessagesRepository.MAX_LENGTH)) }

    fun send() {
        val state = _uiState.value as? ChatUiState.Ready ?: return
        if (!state.canSend) return
        val content = state.draft.trim()

        viewModelScope.launch {
            // Cleared straight away so the field is ready for the next message;
            // put back if the send fails, so nothing typed is lost.
            updateReady { it.copy(draft = "", isSending = true) }
            runCatching { repository.send(requestId, state.viewerId, content) }
                .onSuccess { outcome ->
                    when (outcome) {
                        is SendOutcome.Sent -> merge(listOf(outcome.message))
                        SendOutcome.RateLimited -> {
                            restoreDraft(content)
                            _messages.send("You've sent a lot of messages this hour. Please wait a bit.")
                        }
                        SendOutcome.NotAllowed -> {
                            restoreDraft(content)
                            _messages.send("This chat is closed, so the message wasn't sent.")
                        }
                    }
                }
                .onFailure {
                    restoreDraft(content)
                    _messages.send(it.toUserMessage())
                }
            updateReady { it.copy(isSending = false) }
        }
    }

    private fun restoreDraft(content: String) =
        updateReady { if (it.draft.isEmpty()) it.copy(draft = content) else it }

    private fun merge(incoming: List<ChatMessage>) =
        updateReady { it.copy(messages = mergeById(it.messages, incoming)) }

    private inline fun updateReady(transform: (ChatUiState.Ready) -> ChatUiState.Ready) =
        _uiState.update { if (it is ChatUiState.Ready) transform(it) else it }

    override fun onCleared() {
        stop()
    }
}

/**
 * Union by id, in time order. A message can arrive twice — from the insert's own
 * response and from Realtime — and must show once.
 */
internal fun mergeById(existing: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
    if (incoming.isEmpty()) return existing
    val byId = LinkedHashMap<String, ChatMessage>()
    (existing + incoming).forEach { byId[it.id] = it }
    return byId.values.sortedWith(compareBy({ parseTimestamp(it.createdAt) }, { it.id }))
}

private fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Something went wrong. Please try again."
    }
}
