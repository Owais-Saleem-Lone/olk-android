package com.openlibrarykashmir.olk

import app.cash.turbine.test
import com.openlibrarykashmir.olk.core.data.model.ClubEligibility
import com.openlibrarykashmir.olk.core.data.model.ClubRequestDraft
import com.openlibrarykashmir.olk.core.data.model.ClubRequestOutcome
import com.openlibrarykashmir.olk.core.data.model.ClubRequestStatus
import com.openlibrarykashmir.olk.core.data.model.ClubRequestSummary
import com.openlibrarykashmir.olk.core.data.model.CoverBucket
import com.openlibrarykashmir.olk.core.data.model.CreateEventOutcome
import com.openlibrarykashmir.olk.core.data.model.EventDraft
import com.openlibrarykashmir.olk.core.data.repository.ClubOrganiserRepository
import com.openlibrarykashmir.olk.core.data.repository.OwnProfile
import com.openlibrarykashmir.olk.core.data.repository.ProfileRepository
import com.openlibrarykashmir.olk.core.data.repository.ProfileUpdate
import com.openlibrarykashmir.olk.core.data.repository.SharedLocation
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.clubs.RequestClubUiState
import com.openlibrarykashmir.olk.feature.clubs.RequestClubViewModel
import com.openlibrarykashmir.olk.feature.events.CreateEventViewModel
import com.openlibrarykashmir.olk.feature.mybooks.CoverUploader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ClubOrganiserViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuth : AuthRepository {
        override val authState: Flow<AuthState> = emptyFlow()
        override fun currentUserId() = "me"
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    private class FakeProfiles : ProfileRepository {
        override suspend fun myProfile() = OwnProfile(id = "me", areaName = "Anantnag")
        override suspend fun myLocation(userId: String): SharedLocation? = null
        override suspend fun areaSuggestions() = listOf("Anantnag, Anantnag")
        override suspend fun save(userId: String, update: ProfileUpdate) = Unit
    }

    private class FakeOrganiser(
        var eligible: Boolean = true,
        var latest: ClubRequestSummary? = null,
        var requestOutcome: ClubRequestOutcome = ClubRequestOutcome.Submitted,
        var eventOutcome: CreateEventOutcome = CreateEventOutcome.Created("e1"),
    ) : ClubOrganiserRepository {
        var submitted: ClubRequestDraft? = null
        var createdEvent: EventDraft? = null
        var withdrawn: String? = null

        override suspend fun eligibility() = ClubEligibility(eligible, if (eligible) 5 else 2, 0, 5)
        override suspend fun latestRequest(userId: String) = latest
        override suspend fun submitRequest(userId: String, draft: ClubRequestDraft): ClubRequestOutcome {
            submitted = draft
            if (requestOutcome == ClubRequestOutcome.Submitted) {
                latest = ClubRequestSummary("r1", draft.name, ClubRequestStatus.PENDING, null, null, null)
            }
            return requestOutcome
        }
        override suspend fun withdrawRequest(requestId: String): Boolean {
            withdrawn = requestId
            latest = null
            return true
        }
        override suspend fun createEvent(userId: String, draft: EventDraft): CreateEventOutcome {
            createdEvent = draft
            return eventOutcome
        }
        override suspend fun uploadCover(bucket: CoverBucket, ownerId: String, webpBytes: ByteArray) = error("unused")
        override suspend fun removeCover(bucket: CoverBucket, publicUrl: String) = Unit
    }

    private val noUpload = CoverUploader { _, _ -> error("unexpected upload") }

    private fun requestVm(organiser: FakeOrganiser, uploader: CoverUploader = noUpload) =
        RequestClubViewModel(organiser, FakeProfiles(), FakeAuth(), uploader)

    // ── Request a club ──

    @Test
    fun `an ineligible member sees why, not the form`() = runTest {
        val vm = requestVm(FakeOrganiser(eligible = false))
        advanceUntilIdle()
        val state = vm.uiState.value as RequestClubUiState.NotEligible
        assertEquals(2, state.eligibility.completedExchanges)
    }

    @Test
    fun `a pending request shows the review screen, and can be withdrawn`() = runTest {
        val organiser = FakeOrganiser(latest = ClubRequestSummary("r1", "Poetry Circle", ClubRequestStatus.PENDING, null, null, null))
        val vm = requestVm(organiser)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is RequestClubUiState.UnderReview)

        vm.withdraw()
        advanceUntilIdle()
        assertEquals("r1", organiser.withdrawn)
        assertTrue(vm.uiState.value is RequestClubUiState.Form)
    }

    @Test
    fun `the form starts from the profile's area and sends only listed interests`() = runTest {
        val organiser = FakeOrganiser()
        val vm = requestVm(organiser)
        advanceUntilIdle()
        assertEquals("Anantnag", (vm.uiState.value as RequestClubUiState.Form).form.areaName)

        vm.onFormChange { it.copy(name = "Poetry Circle", description = "We read poems together.") }
        vm.toggleInterest("Poetry")
        vm.toggleInterest("Made Up")
        vm.messages.test {
            vm.submit()
            advanceUntilIdle()
            assertEquals("Your request has been sent for review.", awaitItem())
        }
        assertEquals(listOf("Poetry"), organiser.submitted?.interests)
        assertNull(organiser.submitted?.coverUrl)
        // The new request is pending, so the review screen replaces the form.
        assertTrue(vm.uiState.value is RequestClubUiState.UnderReview)
    }

    @Test
    fun `an incomplete form or an http cover link is never sent`() = runTest {
        val organiser = FakeOrganiser()
        val vm = requestVm(organiser)
        advanceUntilIdle()

        vm.submit() // no name, no interests, no description
        vm.onFormChange { it.copy(name = "Circle", description = "About books.") }
        vm.toggleInterest("Fiction")
        vm.onCoverLink("http://example.com/a.jpg")
        vm.submit()
        advanceUntilIdle()

        assertNull(organiser.submitted)
        val form = vm.uiState.value as RequestClubUiState.Form
        assertTrue(form.showErrors)
        assertNotNull(form.form.coverLinkError)
    }

    @Test
    fun `a pasted https link is sent as the cover`() = runTest {
        val organiser = FakeOrganiser()
        val vm = requestVm(organiser)
        advanceUntilIdle()

        vm.onFormChange { it.copy(name = "Circle", description = "About books.") }
        vm.toggleInterest("Fiction")
        vm.onCoverLink(" https://example.com/pasted.jpg ")
        vm.submit()
        advanceUntilIdle()
        assertEquals("https://example.com/pasted.jpg", organiser.submitted?.coverUrl)
    }

    @Test
    fun `a second pending request is explained`() = runTest {
        val vm = requestVm(FakeOrganiser(requestOutcome = ClubRequestOutcome.AlreadyPending))
        advanceUntilIdle()
        vm.onFormChange { it.copy(name = "Circle", description = "About books.") }
        vm.toggleInterest("Fiction")
        vm.messages.test {
            vm.submit()
            advanceUntilIdle()
            assertEquals("You already have a club request waiting for review.", awaitItem())
        }
    }

    @Test
    fun `someone who already runs a club is told so`() = runTest {
        val vm = requestVm(FakeOrganiser(requestOutcome = ClubRequestOutcome.AlreadyRunsClub))
        advanceUntilIdle()
        vm.onFormChange { it.copy(name = "Circle", description = "About books.") }
        vm.toggleInterest("Fiction")
        vm.messages.test {
            vm.submit()
            advanceUntilIdle()
            assertEquals("You already run a club. Each member can run one club.", awaitItem())
        }
    }

    // ── Schedule an event ──

    private val now = Instant.parse("2026-09-24T12:00:00Z")

    private fun eventVm(organiser: FakeOrganiser) = CreateEventViewModel(
        clubId = "c1",
        clubName = "Khanabal Chess Club",
        organiser = organiser,
        auth = FakeAuth(),
        coverUploader = noUpload,
        zone = ZoneOffset.UTC,
        now = { now },
    )

    @Test
    fun `an event cannot start in the past or end before it starts`() = runTest {
        val organiser = FakeOrganiser()
        val vm = eventVm(organiser)
        vm.onFormChange { it.copy(title = "Tournament", date = LocalDate.of(2026, 9, 24), startTime = LocalTime.of(11, 0)) }
        assertEquals("An event can't start in the past.", vm.errors().start)

        vm.onFormChange { it.copy(startTime = LocalTime.of(18, 0), endTime = LocalTime.of(17, 0)) }
        assertNull(vm.errors().start)
        assertEquals("It can't end before it starts.", vm.errors().end)

        vm.create()
        advanceUntilIdle()
        assertNull(organiser.createdEvent)
    }

    @Test
    fun `meeting links, capacity and cover links follow the database's rules`() = runTest {
        val vm = eventVm(FakeOrganiser())
        vm.onFormChange { it.copy(isOnline = true, meetingUrl = "javascript:alert(1)", capacity = "11", coverLink = "http://x.jpg") }
        val errors = vm.errors()
        assertNotNull(errors.meetingUrl)
        assertEquals("Between 1 and 10.", errors.capacity)
        assertNotNull(errors.coverLink)

        vm.onFormChange { it.copy(capacity = "0") }
        assertEquals("Between 1 and 10.", vm.errors().capacity)
    }

    @Test
    fun `a sound event is created with the exact start time and opens`() = runTest {
        val organiser = FakeOrganiser()
        val vm = eventVm(organiser)
        vm.onFormChange {
            it.copy(
                title = "Tournament",
                date = LocalDate.of(2026, 9, 25),
                startTime = LocalTime.of(18, 30),
                isOnline = true,
                meetingUrl = "https://meet.example/room",
                capacity = "8",
                membersOnly = true,
            )
        }
        vm.create()
        advanceUntilIdle()

        val draft = organiser.createdEvent!!
        assertEquals(Instant.parse("2026-09-25T18:30:00Z"), draft.startsAt)
        assertEquals(8, draft.capacity)
        assertTrue(draft.membersOnly)
        assertEquals("e1", vm.uiState.value.createdEventId)
    }

    @Test
    fun `the monthly limit is explained`() = runTest {
        val vm = eventVm(FakeOrganiser(eventOutcome = CreateEventOutcome.MonthlyLimitReached))
        vm.onFormChange { it.copy(title = "Tournament", date = LocalDate.of(2026, 9, 25), startTime = LocalTime.of(18, 0)) }
        vm.messages.test {
            vm.create()
            advanceUntilIdle()
            assertEquals("This club has already used its events for this month. Try again next month.", awaitItem())
        }
        assertNull(vm.uiState.value.createdEventId)
    }
}
