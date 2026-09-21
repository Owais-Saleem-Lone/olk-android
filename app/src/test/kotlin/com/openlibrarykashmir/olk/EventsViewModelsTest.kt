package com.openlibrarykashmir.olk

import com.openlibrarykashmir.olk.core.data.model.BrowseClub
import com.openlibrarykashmir.olk.core.data.model.BrowseEvent
import com.openlibrarykashmir.olk.core.data.model.Club
import com.openlibrarykashmir.olk.core.data.model.ClubMember
import com.openlibrarykashmir.olk.core.data.model.ClubPost
import com.openlibrarykashmir.olk.core.data.model.ClubRating
import com.openlibrarykashmir.olk.core.data.model.Event
import com.openlibrarykashmir.olk.core.data.model.MembershipStatus
import com.openlibrarykashmir.olk.core.data.model.VISIBILITY_MEMBERS_ONLY
import com.openlibrarykashmir.olk.core.data.repository.ClubsRepository
import com.openlibrarykashmir.olk.core.data.repository.EventsRepository
import com.openlibrarykashmir.olk.core.data.repository.PostOutcome
import com.openlibrarykashmir.olk.core.data.repository.RsvpOutcome
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.events.EventDetailViewModel
import com.openlibrarykashmir.olk.feature.events.EventsViewModel
import com.openlibrarykashmir.olk.feature.events.eventSummaryLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EventsViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuth(private val userId: String?) : AuthRepository {
        override val authState: Flow<AuthState> = emptyFlow()
        override fun currentUserId() = userId
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    /** Only membership matters to the event page. */
    private class FakeMembership(var status: MembershipStatus = MembershipStatus.NONE) : ClubsRepository {
        override suspend fun membership(clubId: String, userId: String) = status
        override suspend fun browse(query: String, interest: String?, limit: Int, offset: Int) = emptyList<BrowseClub>()
        override suspend fun club(id: String): Club? = null
        override suspend fun members(clubId: String) = emptyList<ClubMember>()
        override suspend fun pendingApplicants(clubId: String) = emptyList<ClubMember>()
        override suspend fun myMemberships(userId: String) = emptyMap<String, MembershipStatus>()
        override suspend fun requestToJoin(clubId: String, userId: String) = Unit
        override suspend fun leave(clubId: String, userId: String) = Unit
        override suspend fun approve(clubId: String, userId: String) = Unit
        override suspend fun reject(clubId: String, userId: String) = Unit
        override suspend fun posts(clubId: String, limit: Int) = emptyList<ClubPost>()
        override suspend fun sendPost(clubId: String, authorId: String, content: String) = PostOutcome.Sent
        override suspend fun myRating(clubId: String, userId: String): ClubRating? = null
        override suspend fun rate(clubId: String, userId: String, score: Int, comment: String?) = Unit
    }

    private val now = Instant.parse("2026-09-21T12:00:00Z")

    private fun event(
        creator: String = "organiser",
        visibility: String = "public",
        online: Boolean = false,
        capacity: Int? = null,
        attendees: Int = 0,
        startsAt: String = "2026-10-01T18:00:00+00:00",
        active: Boolean = true,
    ) = Event(
        id = "e1", clubId = "c1", clubName = "Chess Club", creatorId = creator,
        title = "Reading circle", description = null, coverUrl = null,
        startsAt = startsAt, endsAt = null, isOnline = online, locationName = "Cafe Fiction",
        visibility = visibility, capacity = capacity, attendeeCount = attendees, active = active,
    )

    private fun detailViewModel(
        events: FakeEvents,
        user: String? = "me",
        membership: MembershipStatus = MembershipStatus.NONE,
    ) = EventDetailViewModel("e1", events, FakeMembership(membership), FakeAuth(user), now = { now })

    private fun browseEvent(id: String) = BrowseEvent(
        id = id, clubId = "c1", clubName = "Chess Club", title = "Event $id",
        startsAt = "2026-10-01T18:00:00+00:00",
    )

    // ── The events list ──

    @Test
    fun `the list loads, and a short page means there is no more`() = runTest {
        val events = FakeEvents(page = listOf(browseEvent("a"), browseEvent("b")))
        val viewModel = EventsViewModel(events)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("a", "b"), state.events.map { it.id })
        assertTrue(state.endReached)
        assertFalse(state.isLoading)
    }

    @Test
    fun `typing searches once, after a pause`() = runTest {
        val events = FakeEvents()
        val viewModel = EventsViewModel(events)
        advanceUntilIdle()

        viewModel.onQueryChange("c")
        viewModel.onQueryChange("ch")
        viewModel.onQueryChange("chess")
        advanceTimeBy(301)
        advanceUntilIdle()

        assertEquals(listOf("" to 0, "chess" to 0), events.browsed)
    }

    @Test
    fun `a full page pages in the rest at the right offset`() = runTest {
        val full = (1..EventsRepository.PAGE_SIZE).map { browseEvent("e$it") }
        val events = FakeEvents(page = full)
        val viewModel = EventsViewModel(events)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.endReached)

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals("" to EventsRepository.PAGE_SIZE, events.browsed.last())
        assertTrue(viewModel.uiState.value.endReached)
    }

    @Test
    fun `the card line reads place, distance and head count`() {
        assertEquals(
            "Cafe Fiction · ~3 km · 4 going",
            eventSummaryLine(browseEvent("a").copy(locationName = "Cafe Fiction", distanceKm = 3.2, attendeeCount = 4)),
        )
        assertEquals("Online · 0 going", eventSummaryLine(browseEvent("a").copy(isOnline = true)))
    }

    // ── The event page ──

    @Test
    fun `anyone may RSVP to a public event`() = runTest {
        val viewModel = detailViewModel(FakeEvents(detail = event()))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canRsvp)
    }

    @Test
    fun `a members-only event takes RSVPs from approved members, not from applicants`() = runTest {
        val events = FakeEvents(detail = event(visibility = VISIBILITY_MEMBERS_ONLY))

        val applicant = detailViewModel(events, membership = MembershipStatus.PENDING)
        advanceUntilIdle()
        // The website offered "I'm going" here and then showed a raw RLS error.
        assertFalse(applicant.uiState.value.canRsvp)

        val member = detailViewModel(events, membership = MembershipStatus.APPROVED)
        advanceUntilIdle()
        assertTrue(member.uiState.value.canRsvp)

        val organiser = detailViewModel(FakeEvents(detail = event(creator = "me", visibility = VISIBILITY_MEMBERS_ONLY)))
        advanceUntilIdle()
        assertTrue(organiser.uiState.value.canRsvp)
    }

    @Test
    fun `nobody is asked for the attendee list or the link unless the database would answer`() = runTest {
        val events = FakeEvents(detail = event(online = true), link = "https://meet.example/abc")
        val viewModel = detailViewModel(events)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.canSeeAttendees)
        assertNull(state.meetingUrl)
        assertEquals(0, events.attendeesAsked)
        assertEquals(0, events.linkAsked)
    }

    @Test
    fun `going brings the count, the list and the joining link with it`() = runTest {
        val events = FakeEvents(detail = event(online = true, attendees = 2), link = "https://meet.example/abc")
        val viewModel = detailViewModel(events)
        advanceUntilIdle()

        viewModel.toggleRsvp()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isGoing)
        assertEquals(3, state.event?.attendeeCount)
        assertEquals(listOf("me"), state.attendees.map { it.userId })
        assertEquals("https://meet.example/abc", state.meetingUrl)
        assertEquals("You're going.", state.message)
    }

    @Test
    fun `cancelling an RSVP takes it all away again`() = runTest {
        val events = FakeEvents(
            detail = event(online = true, attendees = 1),
            going = mutableSetOf("me"),
            link = "https://meet.example/abc",
        )
        val viewModel = detailViewModel(events)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isGoing)

        viewModel.toggleRsvp()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isGoing)
        assertEquals(0, state.event?.attendeeCount)
        assertNull(state.meetingUrl)
    }

    @Test
    fun `losing the race for the last place says so`() = runTest {
        val events = FakeEvents(detail = event(capacity = 5, attendees = 4), rsvpOutcome = RsvpOutcome.Full)
        val viewModel = detailViewModel(events)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isFull)

        viewModel.toggleRsvp()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isGoing)
        assertEquals("This event just filled up.", viewModel.uiState.value.message)
    }

    @Test
    fun `a full event is full to newcomers but not to someone already going`() = runTest {
        val newcomer = detailViewModel(FakeEvents(detail = event(capacity = 2, attendees = 2)))
        advanceUntilIdle()
        assertTrue(newcomer.uiState.value.isFull)

        val attendee = detailViewModel(FakeEvents(detail = event(capacity = 2, attendees = 2), going = mutableSetOf("me")))
        advanceUntilIdle()
        assertFalse(attendee.uiState.value.isFull)
    }

    @Test
    fun `the organiser's cancel is read back from the database`() = runTest {
        val events = FakeEvents(detail = event(creator = "me"))
        val viewModel = detailViewModel(events)
        advanceUntilIdle()

        viewModel.cancelEvent()
        advanceUntilIdle()

        assertEquals(1, events.cancelled)
        assertTrue(viewModel.uiState.value.isCancelled)
        assertEquals("Event cancelled.", viewModel.uiState.value.message)
    }

    @Test
    fun `a refused cancel is reported, not presented as done`() = runTest {
        val events = FakeEvents(detail = event(creator = "me"), failCancel = IllegalStateException("42501"))
        val viewModel = detailViewModel(events)
        advanceUntilIdle()

        viewModel.cancelEvent()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCancelled)
        assertEquals("Could not cancel the event. Try again.", viewModel.uiState.value.message)
    }

    @Test
    fun `only the organiser can cancel`() = runTest {
        val events = FakeEvents(detail = event(creator = "someone-else"))
        val viewModel = detailViewModel(events)
        advanceUntilIdle()

        viewModel.cancelEvent()
        advanceUntilIdle()

        assertEquals(0, events.cancelled)
    }

    @Test
    fun `a past event is marked as ended`() = runTest {
        val viewModel = detailViewModel(FakeEvents(detail = event(startsAt = "2026-09-01T18:00:00+00:00")))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasEnded)
    }

    @Test
    fun `an unreadable event -- cancelled, or its club taken down -- is not found`() = runTest {
        val viewModel = detailViewModel(FakeEvents(detail = null))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.notFound)
        assertFalse(viewModel.uiState.value.isLoading)
    }
}
