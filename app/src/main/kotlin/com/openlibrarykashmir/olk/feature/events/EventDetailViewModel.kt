package com.openlibrarykashmir.olk.feature.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.Event
import com.openlibrarykashmir.olk.core.data.model.EventAttendee
import com.openlibrarykashmir.olk.core.data.model.MembershipStatus
import com.openlibrarykashmir.olk.core.data.repository.ClubsRepository
import com.openlibrarykashmir.olk.core.data.repository.EventsRepository
import com.openlibrarykashmir.olk.core.data.repository.RsvpOutcome
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class EventDetailUiState(
    val event: Event? = null,
    val creatorName: String? = null,
    val currentUserId: String? = null,
    val isCreator: Boolean = false,
    /** Approved members only, the same as the database's `is_club_member()`. */
    val isClubMember: Boolean = false,
    val isGoing: Boolean = false,
    val attendees: List<EventAttendee> = emptyList(),
    val meetingUrl: String? = null,
    val hasEnded: Boolean = false,
    val isRsvping: Boolean = false,
    val isCancelling: Boolean = false,
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    /** A members-only event takes RSVPs from approved members and the organiser. */
    val canRsvp: Boolean get() = event != null && (!event.isMembersOnly || isClubMember || isCreator)

    val isFull: Boolean
        get() = event?.capacity?.let { event.attendeeCount >= it } == true && !isGoing

    /** Mirrors the RSVP read policy, so the app never promises a list RLS will return empty. */
    val canSeeAttendees: Boolean get() = isClubMember || isGoing || isCreator

    /** Only ever true for the organiser: nobody else can read a cancelled event. */
    val isCancelled: Boolean get() = event?.active == false
}

class EventDetailViewModel(
    private val eventId: String,
    private val events: EventsRepository,
    private val clubs: ClubsRepository,
    private val auth: AuthRepository,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EventDetailUiState())
    val uiState: StateFlow<EventDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun refresh() = load()

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    /** "I'm going" when not going, "Cancel RSVP" when going. */
    fun toggleRsvp() {
        val state = _uiState.value
        val userId = state.currentUserId ?: return
        if (state.isRsvping || state.event == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRsvping = true) }
            runCatching {
                if (state.isGoing) {
                    events.cancelRsvp(eventId, userId)
                    null
                } else {
                    events.rsvp(eventId, userId)
                }
            }.onSuccess { outcome ->
                _uiState.update {
                    it.copy(
                        isRsvping = false,
                        message = when (outcome) {
                            null -> "You're no longer going."
                            RsvpOutcome.Going -> "You're going."
                            RsvpOutcome.AlreadyGoing -> "You're already going."
                            RsvpOutcome.Full -> "This event just filled up."
                        },
                    )
                }
                // Count, attendee list and joining link all change with an RSVP,
                // and the count is the database's, so read the whole page again.
                load()
            }.onFailure {
                _uiState.update { s -> s.copy(isRsvping = false) }
                reportFailure(it, if (state.isGoing) "Could not cancel your RSVP. Try again." else "Could not RSVP. Try again.")
                load()
            }
        }
    }

    fun cancelEvent() {
        val state = _uiState.value
        if (!state.isCreator || state.isCancelling || state.isCancelled) return

        viewModelScope.launch {
            _uiState.update { it.copy(isCancelling = true) }
            runCatching { events.cancelEvent(eventId) }
                .onSuccess {
                    _uiState.update { it.copy(isCancelling = false, message = "Event cancelled.") }
                    // Read back rather than assumed: the website once told organisers
                    // an event was cancelled while the database had refused it.
                    load()
                }
                .onFailure {
                    _uiState.update { s -> s.copy(isCancelling = false) }
                    reportFailure(it, "Could not cancel the event. Try again.")
                }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            val userId = auth.currentUserId()

            runCatching {
                val event = events.event(eventId) ?: return@runCatching null
                val isCreator = userId != null && userId == event.creatorId
                val isMember = userId != null &&
                    clubs.membership(event.clubId, userId) == MembershipStatus.APPROVED
                val isGoing = userId != null && events.isGoing(eventId, userId)
                val canSeeAttendees = isMember || isGoing || isCreator

                EventDetailUiState(
                    event = event,
                    creatorName = events.displayName(event.creatorId),
                    currentUserId = userId,
                    isCreator = isCreator,
                    isClubMember = isMember,
                    isGoing = isGoing,
                    attendees = if (canSeeAttendees) events.attendees(eventId) else emptyList(),
                    // Asked only where the database would answer; it returns null
                    // to everyone else anyway.
                    meetingUrl = if (event.isOnline && (isGoing || isCreator)) events.meetingUrl(eventId) else null,
                    hasEnded = eventHasEnded(event.startsAt, event.endsAt, now()),
                    isLoading = false,
                )
            }.onSuccess { loaded ->
                _uiState.update {
                    loaded?.copy(message = it.message, isRsvping = it.isRsvping, isCancelling = it.isCancelling)
                        ?: it.copy(isLoading = false, notFound = true, event = null)
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                _uiState.update { it.copy(isLoading = false, error = throwable.toEventsMessage()) }
            }
        }
    }

    private fun reportFailure(throwable: Throwable, fallback: String) {
        if (throwable is CancellationException) throw throwable
        _uiState.update { it.copy(message = fallback) }
    }
}
