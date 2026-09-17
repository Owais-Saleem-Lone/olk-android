package com.openlibrarykashmir.olk.feature.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.repository.NotificationsRepository
import com.openlibrarykashmir.olk.core.data.repository.UserNotification
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.ui.parseTimestamp
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotificationsUiState(
    val notifications: List<UserNotification> = emptyList(),
    /** True only until the first load finishes; later refreshes keep the list on screen. */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
) {
    /** What the bell's badge shows, on every screen. */
    val unreadCount: Int get() = notifications.count { it.isUnread }
}

/**
 * Backs both the notifications screen and the bell in every top bar, so it is
 * created once at the navigation host rather than per screen — two copies would
 * mean the badge and the list disagreeing about what has been read.
 */
class NotificationsViewModel(
    private val repository: NotificationsRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var liveJob: Job? = null

    /** Whose notifications are in [uiState]; a different user means start again. */
    private var loadedFor: String? = null

    /**
     * Called whenever the app comes to the foreground: subscribe first, then
     * load, so a notification arriving in between is caught by one or the other
     * (and merged by id if both see it).
     */
    fun start() {
        val userId = auth.currentUserId() ?: run {
            _uiState.value = NotificationsUiState(isLoading = false)
            loadedFor = null
            return
        }
        if (userId != loadedFor) {
            // A different account: never show the previous user's notifications.
            _uiState.value = NotificationsUiState()
            loadedFor = userId
        }
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            repository.changes(userId).collect { notification -> merge(listOf(notification)) }
        }
        refresh()
    }

    /** Called when the app stops; the Realtime channel is not kept open in the background. */
    fun stop() {
        liveJob?.cancel()
        liveJob = null
    }

    fun refresh(userInitiated: Boolean = false) {
        val userId = auth.currentUserId() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(error = null, isRefreshing = userInitiated) }
            runCatching { repository.recent(userId) }
                .onSuccess { list ->
                    _uiState.update {
                        // Merged, not replaced: a Realtime row that arrived while
                        // this query was in flight must not be dropped.
                        it.copy(
                            notifications = mergeNotifications(it.notifications, list),
                            isLoading = false,
                            isRefreshing = false,
                        )
                    }
                }
                .onFailure { throwable ->
                    val message = throwable.toUserMessage()
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = message) }
                    if (_uiState.value.notifications.isNotEmpty()) _messages.send(message)
                }
        }
    }

    /**
     * Marks one notification read, on screen straight away. A failure puts it
     * back: an unread badge that will not clear is easier to make sense of than
     * one that clears and silently comes back on the next refresh.
     */
    fun markRead(notification: UserNotification) {
        if (!notification.isUnread) return
        setRead(listOf(notification.id))
        viewModelScope.launch {
            runCatching { repository.markRead(listOf(notification.id)) }
                .onFailure {
                    setRead(listOf(notification.id), read = false)
                    _messages.send(it.toUserMessage())
                }
        }
    }

    fun markAllRead() {
        val ids = _uiState.value.notifications.filter { it.isUnread }.map { it.id }
        if (ids.isEmpty()) return
        setRead(ids)
        viewModelScope.launch {
            runCatching { repository.markRead(ids) }
                .onFailure {
                    setRead(ids, read = false)
                    _messages.send(it.toUserMessage())
                }
        }
    }

    private fun setRead(ids: List<String>, read: Boolean = true) = _uiState.update { state ->
        state.copy(
            notifications = state.notifications.map {
                if (it.id in ids) it.copy(read = read) else it
            },
        )
    }

    private fun merge(incoming: List<UserNotification>) = _uiState.update {
        it.copy(notifications = mergeNotifications(it.notifications, incoming), isLoading = false)
    }

    override fun onCleared() {
        stop()
    }
}

/**
 * Union by id, newest first. A notification can arrive twice — from the list
 * query and from Realtime — and must show once; a Realtime UPDATE (the same
 * account reading it on the website) replaces the copy already on screen.
 */
internal fun mergeNotifications(
    existing: List<UserNotification>,
    incoming: List<UserNotification>,
): List<UserNotification> {
    if (incoming.isEmpty()) return existing
    val byId = LinkedHashMap<String, UserNotification>()
    (existing + incoming).forEach { byId[it.id] = it }
    return byId.values
        .sortedWith(compareByDescending<UserNotification> { parseTimestamp(it.createdAt) }.thenBy { it.id })
        .take(NotificationsRepository.DEFAULT_LIMIT)
}

private fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Something went wrong. Please try again."
    }
}
