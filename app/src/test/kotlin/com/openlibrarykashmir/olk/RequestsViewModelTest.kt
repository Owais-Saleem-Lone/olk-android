package com.openlibrarykashmir.olk

import app.cash.turbine.test
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.model.RequestStatus
import com.openlibrarykashmir.olk.core.data.repository.BookRequestItem
import com.openlibrarykashmir.olk.core.data.repository.PersonSummary
import com.openlibrarykashmir.olk.core.data.repository.RateOutcome
import com.openlibrarykashmir.olk.core.data.repository.RequestActionOutcome
import com.openlibrarykashmir.olk.core.data.repository.RequestBook
import com.openlibrarykashmir.olk.core.data.repository.RequestsRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.requests.RequestAction
import com.openlibrarykashmir.olk.feature.requests.RequestDirection
import com.openlibrarykashmir.olk.feature.requests.RequestsViewModel
import com.openlibrarykashmir.olk.feature.requests.actionsFor
import com.openlibrarykashmir.olk.feature.requests.canRate
import com.openlibrarykashmir.olk.feature.requests.dueDaysLeft
import com.openlibrarykashmir.olk.feature.requests.showsProgress
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun request(
        id: String = "r1",
        status: RequestStatus = RequestStatus.PENDING,
        listingType: ListingType = ListingType.LEND,
    ) = BookRequestItem(
        id = id,
        status = status,
        createdAt = null,
        handedOverAt = null,
        book = RequestBook("b1", "A Book", "owner", listingType, lendingDurationMonths = 1, coverUrl = null),
        otherParty = PersonSummary("p1", "Reader", null),
    )

    private class FakeAuth : AuthRepository {
        override val authState: Flow<AuthState> = emptyFlow()
        override fun currentUserId() = "me"
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    private class FakeRequests(
        var incoming: List<BookRequestItem> = emptyList(),
        var outcome: RequestActionOutcome = RequestActionOutcome.Done,
        var outgoing: List<BookRequestItem> = emptyList(),
        var rated: Set<String> = emptySet(),
        var savedProgress: Map<String, Int> = emptyMap(),
        var rateOutcome: RateOutcome = RateOutcome.Rated,
        var progressOutcome: RequestActionOutcome = RequestActionOutcome.Done,
    ) : RequestsRepository {
        val calls = mutableListOf<String>()
        var loads = 0

        override suspend fun incoming(ownerId: String): List<BookRequestItem> = incoming.also { loads++ }
        override suspend fun outgoing(requesterId: String) = outgoing
        override suspend fun ratedRequestIds(raterId: String) = rated
        override suspend fun rate(requestId: String, raterId: String, ratedUserId: String, score: Int, comment: String?) =
            rateOutcome.also { calls += "rate:$requestId:$ratedUserId:$score:$comment" }
        override suspend fun progress(requestIds: List<String>) = savedProgress.filterKeys { it in requestIds }
        override suspend fun setProgress(requestId: String, percent: Int) =
            progressOutcome.also { calls += "progress:$requestId:$percent" }
        override suspend fun accept(requestId: String) = outcome.also { calls += "accept:$requestId" }
        override suspend fun decline(requestId: String) = outcome.also { calls += "decline:$requestId" }
        override suspend fun confirmHandover(requestId: String) = outcome.also { calls += "handover:$requestId" }
        override suspend fun confirmReturn(requestId: String) = outcome.also { calls += "return:$requestId" }
        override suspend fun completeDonatedReading(requestId: String) = outcome.also { calls += "finish:$requestId" }
    }

    @Test
    fun `buttons follow the web's request lifecycle`() {
        val incoming = RequestDirection.INCOMING
        val outgoing = RequestDirection.OUTGOING

        assertEquals(listOf(RequestAction.ACCEPT, RequestAction.DECLINE), actionsFor(request(), incoming))
        assertEquals(emptyList<RequestAction>(), actionsFor(request(), outgoing))
        assertEquals(
            listOf(RequestAction.CONFIRM_HANDOVER),
            actionsFor(request(status = RequestStatus.ACCEPTED), outgoing),
        )
        assertEquals(
            listOf(RequestAction.CONFIRM_RETURN),
            actionsFor(request(status = RequestStatus.HANDED_OVER), incoming),
        )
        // Only the new reader finishes a donation — it hands them ownership.
        val donated = request(status = RequestStatus.HANDED_OVER, listingType = ListingType.DONATE)
        assertEquals(listOf(RequestAction.FINISH_READING), actionsFor(donated, outgoing))
        assertEquals(emptyList<RequestAction>(), actionsFor(donated, incoming))
        assertEquals(emptyList<RequestAction>(), actionsFor(request(status = RequestStatus.RETURNED), incoming))
        // Closed by a suspension: nothing left to do on either side.
        for (direction in listOf(incoming, outgoing)) {
            assertEquals(emptyList<RequestAction>(), actionsFor(request(status = RequestStatus.CANCELLED), direction))
        }
    }

    @Test
    fun `due date matches the website, including month-end roll-over`() {
        val midMonth = "2026-04-10T10:00:00+00:00"
        val may1 = Instant.parse("2026-05-01T10:00:00Z")
        assertEquals(9L, dueDaysLeft(midMonth, 1, now = may1, zone = ZoneOffset.UTC))

        // JS: new Date('2026-01-31T10:00Z').setMonth(1) lands on Mar 3, not Feb 28.
        val monthEnd = "2026-01-31T10:00:00+00:00"
        val feb20 = Instant.parse("2026-02-20T10:00:00Z")
        assertEquals(11L, dueDaysLeft(monthEnd, 1, now = feb20, zone = ZoneOffset.UTC))

        val mar5 = Instant.parse("2026-03-05T10:00:00Z")
        assertEquals(-2L, dueDaysLeft(monthEnd, 1, now = mar5, zone = ZoneOffset.UTC))

        assertNull(dueDaysLeft(null, 1))
        assertNull(dueDaysLeft(monthEnd, null))
    }

    @Test
    fun `decline asks for confirmation before calling the database`() = runTest {
        val repo = FakeRequests(incoming = listOf(request()))
        val viewModel = RequestsViewModel(repo, FakeAuth())
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.onAction(request(), RequestAction.DECLINE)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), repo.calls)
        assertEquals(RequestAction.DECLINE, viewModel.uiState.value.confirmation?.action)

        viewModel.confirm()
        advanceUntilIdle()
        assertEquals(listOf("decline:r1"), repo.calls)
        assertNull(viewModel.uiState.value.confirmation)
    }

    @Test
    fun `a request that changed elsewhere reloads and says so`() = runTest {
        val repo = FakeRequests(incoming = listOf(request()), outcome = RequestActionOutcome.OutOfDate)
        val viewModel = RequestsViewModel(repo, FakeAuth())
        viewModel.refresh()
        advanceUntilIdle()
        val loadsBefore = repo.loads

        viewModel.messages.test {
            viewModel.onAction(request(), RequestAction.ACCEPT)
            advanceUntilIdle()
            assertEquals("This request has already changed. Showing the latest.", awaitItem())
        }
        assertEquals(loadsBefore + 1, repo.loads)
        assertNull(viewModel.uiState.value.busyRequestId)
    }

    @Test
    fun `pending badge counts only requests waiting for an answer`() = runTest {
        val repo = FakeRequests(
            incoming = listOf(
                request("a"),
                request("b"),
                request("c", status = RequestStatus.ACCEPTED),
            ),
        )
        val viewModel = RequestsViewModel(repo, FakeAuth())
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.incomingPendingCount)
    }

    @Test
    fun `rate is offered once, on handed-over or returned exchanges with a known other party`() {
        val handedOver = request(status = RequestStatus.HANDED_OVER)
        assertTrue(canRate(handedOver, emptySet()))
        assertTrue(canRate(request(status = RequestStatus.RETURNED), emptySet()))
        assertFalse(canRate(handedOver, setOf(handedOver.id)))
        assertFalse(canRate(request(status = RequestStatus.ACCEPTED), emptySet()))
        assertFalse(canRate(handedOver.copy(otherParty = null), emptySet()))
    }

    @Test
    fun `the progress slider is only for the reader while they have the book`() {
        val handedOver = request(status = RequestStatus.HANDED_OVER)
        assertTrue(showsProgress(handedOver, RequestDirection.OUTGOING))
        assertFalse(showsProgress(handedOver, RequestDirection.INCOMING))
        assertFalse(showsProgress(request(status = RequestStatus.RETURNED), RequestDirection.OUTGOING))
    }

    @Test
    fun `refresh loads what was rated and the reader's saved progress`() = runTest {
        val mine = request(status = RequestStatus.HANDED_OVER)
        val repo = FakeRequests(outgoing = listOf(mine), rated = setOf("other"), savedProgress = mapOf(mine.id to 30))
        val viewModel = RequestsViewModel(repo, FakeAuth())
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(setOf("other"), viewModel.uiState.value.ratedRequestIds)
        assertEquals(mapOf(mine.id to 30), viewModel.uiState.value.progress)
    }

    @Test
    fun `rating sends the score and comment to the other party, then hides Rate`() = runTest {
        val item = request(status = RequestStatus.RETURNED)
        val repo = FakeRequests(incoming = listOf(item))
        val viewModel = RequestsViewModel(repo, FakeAuth())
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.openRating(item)
        assertEquals(item, viewModel.uiState.value.rating)
        viewModel.submitRating(4, "Lovely copy")
        advanceUntilIdle()

        assertEquals("rate:${item.id}:${item.otherParty!!.id}:4:Lovely copy", repo.calls.last())
        assertNull(viewModel.uiState.value.rating)
        assertTrue(item.id in viewModel.uiState.value.ratedRequestIds)
    }

    @Test
    fun `a suspended rater keeps the Rate button for later and is told why`() = runTest {
        val item = request(status = RequestStatus.RETURNED)
        val repo = FakeRequests(incoming = listOf(item), rateOutcome = RateOutcome.Suspended)
        val viewModel = RequestsViewModel(repo, FakeAuth())
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.messages.test {
            viewModel.openRating(item)
            viewModel.submitRating(4, "")
            advanceUntilIdle()
            assertEquals("Your account is suspended, so you can't rate until it ends.", awaitItem())
        }
        // Nothing was rated, so Rate must still be there once the suspension ends.
        assertFalse(item.id in viewModel.uiState.value.ratedRequestIds)
    }

    @Test
    fun `saving progress shows it at once and reloads when the book has gone back`() = runTest {
        val mine = request(status = RequestStatus.HANDED_OVER)
        val repo = FakeRequests(outgoing = listOf(mine))
        val viewModel = RequestsViewModel(repo, FakeAuth())
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.saveProgress(mine, 55)
        assertEquals(55, viewModel.uiState.value.progress[mine.id])
        advanceUntilIdle()
        assertEquals("progress:${mine.id}:55", repo.calls.last())

        repo.progressOutcome = RequestActionOutcome.OutOfDate
        val loadsBefore = repo.loads
        viewModel.saveProgress(mine, 60)
        advanceUntilIdle()
        assertTrue(repo.loads > loadsBefore)
    }
}
