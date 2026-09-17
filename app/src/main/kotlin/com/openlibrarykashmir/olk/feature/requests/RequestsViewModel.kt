package com.openlibrarykashmir.olk.feature.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.model.RequestStatus
import com.openlibrarykashmir.olk.core.data.repository.BookRequestItem
import com.openlibrarykashmir.olk.core.data.repository.RequestActionOutcome
import com.openlibrarykashmir.olk.core.data.repository.RequestsRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class RequestDirection { INCOMING, OUTGOING }

/** Everything a user can do to move a request along, in the order it happens. */
enum class RequestAction(val label: String, val needsConfirmation: Boolean) {
    ACCEPT("Accept", needsConfirmation = false),
    DECLINE("Decline", needsConfirmation = true),
    CONFIRM_HANDOVER("Confirm handover", needsConfirmation = true),
    CONFIRM_RETURN("Mark returned", needsConfirmation = true),
    FINISH_READING("Finished reading", needsConfirmation = true),
}

/**
 * The actions offered on a request, mirroring the web's RequestActions. The
 * database enforces the same rules (guard trigger and RPC preconditions); this
 * only decides which buttons to draw.
 */
fun actionsFor(item: BookRequestItem, direction: RequestDirection): List<RequestAction> =
    when (item.status) {
        RequestStatus.PENDING ->
            if (direction == RequestDirection.INCOMING) listOf(RequestAction.ACCEPT, RequestAction.DECLINE) else emptyList()
        RequestStatus.ACCEPTED -> listOf(RequestAction.CONFIRM_HANDOVER)
        RequestStatus.HANDED_OVER -> when (item.book.listingType) {
            ListingType.LEND -> listOf(RequestAction.CONFIRM_RETURN)
            // Only the new reader completes a donation: it transfers ownership to them.
            ListingType.DONATE ->
                if (direction == RequestDirection.OUTGOING) listOf(RequestAction.FINISH_READING) else emptyList()
        }
        RequestStatus.DECLINED, RequestStatus.RETURNED -> emptyList()
    }

/**
 * Whole days until a lend is due back, negative once overdue; null when it does not
 * apply. Must agree with the web's `dueDaysLeft`, which uses JS `setMonth(+months)`:
 * a day that does not exist in the target month rolls over (Jan 31 + 1 month =
 * Mar 3), where java.time's `plusMonths` would clamp to Feb 28.
 */
fun dueDaysLeft(
    handedOverAt: String?,
    lendingDurationMonths: Int?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): Long? {
    if (handedOverAt == null || lendingDurationMonths == null) return null
    val handedOver = runCatching { OffsetDateTime.parse(handedOverAt) }.getOrNull() ?: return null
    val local = handedOver.atZoneSameInstant(zone)
    val due = local.withDayOfMonth(1)
        .plusMonths(lendingDurationMonths.toLong())
        .plusDays(local.dayOfMonth - 1L)
        .toInstant()
    return Math.floorDiv(ChronoUnit.MILLIS.between(now, due), MILLIS_PER_DAY)
}

private const val MILLIS_PER_DAY = 86_400_000L

data class PendingConfirmation(val item: BookRequestItem, val action: RequestAction)

data class RequestsUiState(
    val selected: RequestDirection = RequestDirection.INCOMING,
    val incoming: List<BookRequestItem> = emptyList(),
    val outgoing: List<BookRequestItem> = emptyList(),
    /** True only until the first load finishes; later refreshes keep the lists on screen. */
    val isLoading: Boolean = true,
    /** A pull-to-refresh in progress; the lists stay on screen meanwhile. */
    val isRefreshing: Boolean = false,
    val error: String? = null,
    /** The request an action is in flight for, so only its buttons disable. */
    val busyRequestId: String? = null,
    val confirmation: PendingConfirmation? = null,
) {
    val visible: List<BookRequestItem>
        get() = if (selected == RequestDirection.INCOMING) incoming else outgoing

    /** Requests waiting for this user's answer — the badge on the Incoming tab. */
    val incomingPendingCount: Int
        get() = incoming.count { it.status == RequestStatus.PENDING }
}

class RequestsViewModel(
    private val repository: RequestsRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RequestsUiState())
    val uiState: StateFlow<RequestsUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun select(direction: RequestDirection) = _uiState.update { it.copy(selected = direction) }

    /**
     * Called whenever the screen starts, and on pull-to-refresh ([userInitiated]):
     * the other person answers, hands over or returns on their own device, so
     * this screen goes stale more than most.
     */
    fun refresh(userInitiated: Boolean = false) {
        val userId = auth.currentUserId() ?: run {
            _uiState.update { it.copy(isLoading = false, error = "Your session has ended. Sign in again.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(error = null, isRefreshing = userInitiated) }
            runCatching {
                val incoming = async { repository.incoming(userId) }
                val outgoing = async { repository.outgoing(userId) }
                incoming.await() to outgoing.await()
            }.onSuccess { (incoming, outgoing) ->
                _uiState.update {
                    it.copy(incoming = incoming, outgoing = outgoing, isLoading = false, isRefreshing = false)
                }
            }.onFailure { throwable ->
                val message = throwable.toUserMessage()
                _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = message) }
                // With lists already on screen the error state is not drawn; say it once.
                if (_uiState.value.visible.isNotEmpty()) _messages.send(message)
            }
        }
    }

    fun onAction(item: BookRequestItem, action: RequestAction) {
        if (action.needsConfirmation) {
            _uiState.update { it.copy(confirmation = PendingConfirmation(item, action)) }
        } else {
            perform(item, action)
        }
    }

    fun confirm() {
        val pending = _uiState.value.confirmation ?: return
        _uiState.update { it.copy(confirmation = null) }
        perform(pending.item, pending.action)
    }

    fun dismissConfirmation() = _uiState.update { it.copy(confirmation = null) }

    private fun perform(item: BookRequestItem, action: RequestAction) {
        if (_uiState.value.busyRequestId != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(busyRequestId = item.id) }
            runCatching {
                when (action) {
                    RequestAction.ACCEPT -> repository.accept(item.id)
                    RequestAction.DECLINE -> repository.decline(item.id)
                    RequestAction.CONFIRM_HANDOVER -> repository.confirmHandover(item.id)
                    RequestAction.CONFIRM_RETURN -> repository.confirmReturn(item.id)
                    RequestAction.FINISH_READING -> repository.completeDonatedReading(item.id)
                }
            }.onSuccess { outcome ->
                _messages.send(
                    when (outcome) {
                        RequestActionOutcome.Done -> action.successMessage()
                        RequestActionOutcome.OutOfDate -> "This request has already changed. Showing the latest."
                    },
                )
                refresh()
            }.onFailure { _messages.send(it.toUserMessage()) }
            _uiState.update { it.copy(busyRequestId = null) }
        }
    }
}

private fun RequestAction.successMessage() = when (this) {
    RequestAction.ACCEPT -> "Request accepted. Arrange the handover with the reader."
    RequestAction.DECLINE -> "Request declined."
    RequestAction.CONFIRM_HANDOVER -> "Handover confirmed."
    RequestAction.CONFIRM_RETURN -> "Marked as returned. The book is available again."
    RequestAction.FINISH_READING -> "The book is now yours to pass on. Find it in My Books."
}

private fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Something went wrong. Please try again."
    }
}
