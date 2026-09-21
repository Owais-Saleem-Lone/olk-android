package com.openlibrarykashmir.olk

import com.openlibrarykashmir.olk.core.data.model.BrowseEvent
import com.openlibrarykashmir.olk.core.data.model.ClubEvent
import com.openlibrarykashmir.olk.core.data.model.Event
import com.openlibrarykashmir.olk.core.data.model.EventAttendee
import com.openlibrarykashmir.olk.core.data.repository.EventsRepository
import com.openlibrarykashmir.olk.core.data.repository.RsvpOutcome

/**
 * An in-memory [EventsRepository] that behaves like the database where it
 * matters: an RSVP adds to the count and the list, cancelling takes it away,
 * and the joining link is only handed to someone going or the organiser.
 */
internal class FakeEvents(
    var page: List<BrowseEvent> = emptyList(),
    var detail: Event? = null,
    var forClub: List<ClubEvent> = emptyList(),
    var going: MutableSet<String> = mutableSetOf(),
    var link: String? = null,
    var rsvpOutcome: RsvpOutcome = RsvpOutcome.Going,
    var failClubEvents: Throwable? = null,
    var failCancel: Throwable? = null,
) : EventsRepository {
    val browsed = mutableListOf<Pair<String, Int>>()
    var attendeesAsked = 0
    var linkAsked = 0
    var cancelled = 0

    override suspend fun browse(query: String, limit: Int, offset: Int): List<BrowseEvent> {
        browsed += query to offset
        return if (offset > 0) emptyList() else page
    }

    override suspend fun event(id: String) = detail

    override suspend fun clubEvents(clubId: String): List<ClubEvent> {
        failClubEvents?.let { throw it }
        return forClub
    }

    override suspend fun displayName(userId: String) = "Organiser"

    override suspend fun isGoing(eventId: String, userId: String) = userId in going

    override suspend fun attendees(eventId: String): List<EventAttendee> {
        attendeesAsked++
        return going.map { EventAttendee(it, "Person $it") }
    }

    override suspend fun meetingUrl(eventId: String): String? {
        linkAsked++
        return link
    }

    override suspend fun rsvp(eventId: String, userId: String): RsvpOutcome {
        if (rsvpOutcome == RsvpOutcome.Going) {
            going += userId
            detail = detail?.let { it.copy(attendeeCount = it.attendeeCount + 1) }
        }
        return rsvpOutcome
    }

    override suspend fun cancelRsvp(eventId: String, userId: String) {
        if (going.remove(userId)) {
            detail = detail?.let { it.copy(attendeeCount = it.attendeeCount - 1) }
        }
    }

    override suspend fun cancelEvent(eventId: String) {
        failCancel?.let { throw it }
        cancelled++
        detail = detail?.copy(active = false)
    }
}
