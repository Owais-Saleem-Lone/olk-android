package com.openlibrarykashmir.olk.feature.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.repository.SupportMessage
import com.openlibrarykashmir.olk.core.data.repository.SupportRepository
import com.openlibrarykashmir.olk.core.data.repository.SupportSendOutcome
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.ui.parseTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SupportUiState {
    data object Loading : SupportUiState
    data class Error(val message: String) : SupportUiState
    data class Ready(
        val conversationId: String,
        val viewerId: String,
        /** Staff writing from their own account post with the admin badge, as on the website. */
        val viewerIsAdminTeam: Boolean,
        val messages: List<SupportMessage>,
        val draft: String = "",
        val isSending: Boolean = false,
    ) : SupportUiState {
        val canSend: Boolean
            get() = !isSending && draft.isNotBlank() && draft.trim().length <= SupportRepository.MAX_LENGTH
    }
}

class SupportViewModel(
    private val repository: SupportRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SupportUiState>(SupportUiState.Loading)
    val uiState: StateFlow<SupportUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var liveJob: Job? = null

    /**
     * Called when the screen starts. The thread is found (or started) first,
     * because the live feed is filtered by its id; then the feed subscribes and
     * history loads, merged by id so nothing arriving in between is lost or
     * shown twice. Returning from the background reloads for the same reason.
     */
    fun start() {
        val viewerId = auth.currentUserId() ?: run {
            _uiState.value = SupportUiState.Error("Your session has ended. Sign in again.")
            return
        }
        viewModelScope.launch {
            runCatching {
                val conversationId = (_uiState.value as? SupportUiState.Ready)?.conversationId
                    ?: repository.conversationId(viewerId)
                val isAdminTeam = repository.isAdminTeam()
                startLive(conversationId)
                Triple(conversationId, isAdminTeam, repository.history(conversationId))
            }.onSuccess { (conversationId, isAdminTeam, history) ->
                _uiState.update { current ->
                    if (current is SupportUiState.Ready) {
                        current.copy(viewerIsAdminTeam = isAdminTeam, messages = mergeSupport(current.messages, history))
                    } else {
                        SupportUiState.Ready(conversationId, viewerId, isAdminTeam, history)
                    }
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                if (_uiState.value !is SupportUiState.Ready) {
                    _uiState.value = SupportUiState.Error(throwable.toSupportMessage())
                } else {
                    _messages.send(throwable.toSupportMessage())
                }
            }
        }
    }

    private fun startLive(conversationId: String) {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            repository.newMessages(conversationId).collect { message -> merge(listOf(message)) }
        }
    }

    /** Called when the screen stops; the Realtime channel is not kept open in the background. */
    fun stop() {
        liveJob?.cancel()
        liveJob = null
    }

    fun onDraftChange(value: String) = updateReady { it.copy(draft = value.take(SupportRepository.MAX_LENGTH)) }

    fun send() {
        val state = _uiState.value as? SupportUiState.Ready ?: return
        if (!state.canSend) return
        val content = state.draft.trim()

        viewModelScope.launch {
            updateReady { it.copy(draft = "", isSending = true) }
            runCatching { repository.send(state.conversationId, state.viewerId, state.viewerIsAdminTeam, content) }
                .onSuccess { outcome ->
                    when (outcome) {
                        is SupportSendOutcome.Sent -> merge(listOf(outcome.message))
                        SupportSendOutcome.RateLimited -> {
                            restoreDraft(content)
                            _messages.send("You've sent too many messages this hour. Please wait a bit before sending more.")
                        }
                        SupportSendOutcome.Invalid -> {
                            restoreDraft(content)
                            _messages.send("Messages can be at most ${SupportRepository.MAX_LENGTH} characters.")
                        }
                    }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    restoreDraft(content)
                    _messages.send(it.toSupportMessage())
                }
            updateReady { it.copy(isSending = false) }
        }
    }

    private fun restoreDraft(content: String) =
        updateReady { if (it.draft.isEmpty()) it.copy(draft = content) else it }

    private fun merge(incoming: List<SupportMessage>) =
        updateReady { it.copy(messages = mergeSupport(it.messages, incoming)) }

    private inline fun updateReady(transform: (SupportUiState.Ready) -> SupportUiState.Ready) =
        _uiState.update { if (it is SupportUiState.Ready) transform(it) else it }

    override fun onCleared() {
        stop()
    }
}

/** Union by id, in time order: a sent message comes back from the insert and from Realtime. */
internal fun mergeSupport(existing: List<SupportMessage>, incoming: List<SupportMessage>): List<SupportMessage> {
    if (incoming.isEmpty()) return existing
    val byId = LinkedHashMap<String, SupportMessage>()
    (existing + incoming).forEach { byId[it.id] = it }
    return byId.values.sortedWith(compareBy({ parseTimestamp(it.createdAt) }, { it.id }))
}

private fun Throwable.toSupportMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Something went wrong. Please try again."
    }
}
