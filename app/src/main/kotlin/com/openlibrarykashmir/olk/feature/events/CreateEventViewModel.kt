package com.openlibrarykashmir.olk.feature.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.CoverBucket
import com.openlibrarykashmir.olk.core.data.model.CreateEventOutcome
import com.openlibrarykashmir.olk.core.data.model.EventDraft
import com.openlibrarykashmir.olk.core.data.model.OrganiserRules
import com.openlibrarykashmir.olk.core.data.repository.ClubOrganiserRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.feature.mybooks.CoverChoice
import com.openlibrarykashmir.olk.feature.mybooks.CoverUploader
import com.openlibrarykashmir.olk.feature.mybooks.toCoverAwareMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Instant

/** The website's "Schedule an event" form. Times are the phone's local time. */
data class EventForm(
    val title: String = "",
    val description: String = "",
    val cover: CoverChoice = CoverChoice.Current(null),
    val coverLink: String = "",
    val date: LocalDate? = null,
    val startTime: LocalTime? = null,
    /** Same day as the start; optional. */
    val endTime: LocalTime? = null,
    val isOnline: Boolean = false,
    val venue: String = "",
    val meetingUrl: String = "",
    val membersOnly: Boolean = false,
    val capacity: String = "",
)

data class CreateEventUiState(
    val clubName: String,
    val form: EventForm = EventForm(),
    val isSaving: Boolean = false,
    val showErrors: Boolean = false,
    /** Set once the event exists; the screen opens it. */
    val createdEventId: String? = null,
)

class CreateEventViewModel(
    private val clubId: String,
    clubName: String,
    private val organiser: ClubOrganiserRepository,
    private val auth: AuthRepository,
    private val coverUploader: CoverUploader,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = { Clock.System.now() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateEventUiState(clubName = clubName))
    val uiState: StateFlow<CreateEventUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun onFormChange(transform: (EventForm) -> EventForm) =
        _uiState.update { it.copy(form = transform(it.form).capped()) }

    fun onCoverChoice(choice: CoverChoice) = onFormChange { it.copy(cover = choice, coverLink = "") }

    fun onCoverLink(link: String) = onFormChange {
        it.copy(coverLink = link, cover = if (link.isBlank()) it.cover else CoverChoice.Current(null))
    }

    fun startsAt(form: EventForm = _uiState.value.form): Instant? =
        instantOf(form.date, form.startTime)

    /** Every rule the database applies, as the message shown under the field. */
    fun errors(form: EventForm = _uiState.value.form): EventFormErrors {
        val start = startsAt(form)
        val end = instantOf(form.date, form.endTime)
        val capacity = form.capacity.trim()
        return EventFormErrors(
            title = if (form.title.isBlank()) "Give the event a title." else null,
            start = when {
                start == null -> "Pick a date and a start time."
                start < now() -> "An event can't start in the past."
                else -> null
            },
            end = if (start != null && end != null && end < start) "It can't end before it starts." else null,
            meetingUrl = if (form.isOnline && form.meetingUrl.isNotBlank() && !OrganiserRules.isHttpUrl(form.meetingUrl)) {
                "Meeting links must start with http:// or https://"
            } else {
                null
            },
            capacity = when {
                capacity.isEmpty() -> null
                capacity.toIntOrNull() == null -> "A whole number, please."
                capacity.toInt() !in 1..OrganiserRules.MAX_CAPACITY -> "Between 1 and ${OrganiserRules.MAX_CAPACITY}."
                else -> null
            },
            coverLink = if (form.coverLink.isNotBlank() && !OrganiserRules.isHttpsUrl(form.coverLink)) {
                "Image links must start with https://"
            } else {
                null
            },
        )
    }

    fun create() {
        val state = _uiState.value
        val userId = auth.currentUserId() ?: return
        if (state.isSaving || state.createdEventId != null) return
        val form = state.form
        if (!errors(form).isEmpty) {
            _uiState.update { it.copy(showErrors = true) }
            viewModelScope.launch { _messages.send("Check the highlighted fields.") }
            return
        }
        val start = startsAt(form) ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            var uploaded: String? = null
            val outcome = runCatching {
                val coverUrl = when (val cover = form.cover) {
                    is CoverChoice.Picked -> coverUploader.upload(userId, cover.uri).also { uploaded = it }
                    else -> form.coverLink.trim().ifEmpty { null }
                }
                organiser.createEvent(
                    userId,
                    EventDraft(
                        clubId = clubId,
                        title = form.title,
                        description = form.description,
                        coverUrl = coverUrl,
                        startsAt = start,
                        endsAt = instantOf(form.date, form.endTime),
                        isOnline = form.isOnline,
                        locationName = form.venue,
                        meetingUrl = form.meetingUrl,
                        membersOnly = form.membersOnly,
                        capacity = form.capacity.trim().toIntOrNull(),
                    ),
                )
            }
            _uiState.update { it.copy(isSaving = false) }
            // A refused event must not leave its photo behind in storage.
            if (outcome.getOrNull() !is CreateEventOutcome.Created) {
                uploaded?.let { organiser.removeCover(CoverBucket.EVENTS, it) }
            }
            outcome
                .onSuccess {
                    when (it) {
                        // The database tells the club's members (trg_notify_club_event_created).
                        is CreateEventOutcome.Created -> _uiState.update { s -> s.copy(createdEventId = it.eventId) }
                        CreateEventOutcome.MonthlyLimitReached ->
                            _messages.send("This club has already used its event for this month. Try again next month.")
                        CreateEventOutcome.CapacityTooHigh ->
                            _messages.send("That capacity is above the current limit of ${OrganiserRules.MAX_CAPACITY} people.")
                        CreateEventOutcome.StartsInPast -> _messages.send("An event can't start in the past.")
                        CreateEventOutcome.NotAllowed -> _messages.send("Only the club's organiser can schedule its events.")
                        CreateEventOutcome.Suspended ->
                            _messages.send("Your account is suspended, so you can't schedule events until it ends.")
                        CreateEventOutcome.Invalid -> {
                            _uiState.update { s -> s.copy(showErrors = true) }
                            _messages.send("Some of the details weren't accepted. Check the form and try again.")
                        }
                    }
                }
                .onFailure { _messages.send(it.toCoverAwareMessage()) }
        }
    }

    private fun instantOf(date: LocalDate?, time: LocalTime?): Instant? =
        if (date == null || time == null) {
            null
        } else {
            Instant.fromEpochMilliseconds(date.atTime(time).atZone(zone).toInstant().toEpochMilli())
        }
}

data class EventFormErrors(
    val title: String? = null,
    val start: String? = null,
    val end: String? = null,
    val meetingUrl: String? = null,
    val capacity: String? = null,
    val coverLink: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(title, start, end, meetingUrl, capacity, coverLink).all { it == null }
}

/** Typing stops at what the database accepts. */
private fun EventForm.capped() = copy(
    title = title.take(OrganiserRules.EVENT_TITLE_CHARS),
    description = description.take(OrganiserRules.EVENT_DESCRIPTION_CHARS),
    venue = venue.take(OrganiserRules.VENUE_CHARS),
    meetingUrl = meetingUrl.take(2048),
    coverLink = coverLink.take(2048),
    capacity = capacity.filter(Char::isDigit).take(3),
)
