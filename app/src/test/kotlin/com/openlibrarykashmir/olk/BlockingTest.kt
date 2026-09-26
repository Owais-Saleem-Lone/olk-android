package com.openlibrarykashmir.olk

import app.cash.turbine.test
import com.openlibrarykashmir.olk.core.data.model.ReportOutcome
import com.openlibrarykashmir.olk.core.data.model.ReportReason
import com.openlibrarykashmir.olk.core.data.repository.BlockOutcome
import com.openlibrarykashmir.olk.core.data.repository.BlockedMember
import com.openlibrarykashmir.olk.core.data.repository.BlocksRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.people.BlockEvent
import com.openlibrarykashmir.olk.feature.people.BlockViewModel
import com.openlibrarykashmir.olk.feature.people.BlockedMembersUiState
import com.openlibrarykashmir.olk.feature.people.BlockedMembersViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BlockingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuth(private val id: String? = "me") : AuthRepository {
        override val authState: Flow<AuthState> = emptyFlow()
        override fun currentUserId() = id
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    private class FakeBlocks(
        val blockedIds: MutableSet<String> = mutableSetOf(),
        var outcome: BlockOutcome = BlockOutcome.Blocked,
    ) : BlocksRepository {
        override suspend fun isBlocked(userId: String) = userId in blockedIds
        override suspend fun block(myId: String, userId: String): BlockOutcome {
            if (outcome == BlockOutcome.Blocked) blockedIds += userId
            return outcome
        }
        override suspend fun unblock(userId: String) = blockedIds.remove(userId)
        override suspend fun blocked() = blockedIds.map { BlockedMember(it, "Name $it") }

        var reportOutcome: ReportOutcome = ReportOutcome.Sent
        val reports = mutableListOf<List<Any?>>()
        override suspend fun reportMember(
            myId: String,
            userId: String,
            reason: ReportReason,
            details: String?,
            context: String?,
        ): ReportOutcome {
            reports += listOf(myId, userId, reason, details, context)
            return reportOutcome
        }
    }

    @Test
    fun `there is nothing to block on your own profile or signed out`() = runTest {
        val self = BlockViewModel("me", FakeBlocks(), FakeAuth())
        val signedOut = BlockViewModel("them", FakeBlocks(), FakeAuth(null))
        advanceUntilIdle()
        assertFalse(self.uiState.value.available)
        assertFalse(signedOut.uiState.value.available)
    }

    @Test
    fun `blocking says so and tells the screen to reload`() = runTest {
        val blocks = FakeBlocks()
        val vm = BlockViewModel("them", blocks, FakeAuth())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.available)

        vm.events.test {
            vm.block()
            advanceUntilIdle()
            assertEquals(BlockEvent.Message("Member blocked."), awaitItem())
            assertEquals(BlockEvent.Changed, awaitItem())
        }
        assertTrue(vm.uiState.value.blocked)
        assertEquals(setOf("them"), blocks.blockedIds)
    }

    @Test
    fun `an existing block shows as Unblock, and unblocking undoes it`() = runTest {
        val blocks = FakeBlocks(mutableSetOf("them"))
        val vm = BlockViewModel("them", blocks, FakeAuth())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.blocked)

        vm.unblock()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.blocked)
        assertTrue(blocks.blockedIds.isEmpty())
    }

    @Test
    fun `the block limit is explained and nothing changes`() = runTest {
        val vm = BlockViewModel("them", FakeBlocks(outcome = BlockOutcome.LimitReached), FakeAuth())
        advanceUntilIdle()
        vm.events.test {
            vm.block()
            advanceUntilIdle()
            assertEquals(BlockEvent.Message("You've blocked the most members you can. Unblock someone first."), awaitItem())
        }
        assertFalse(vm.uiState.value.blocked)
    }

    @Test
    fun `the blocked list drops a member once unblocked`() = runTest {
        val vm = BlockedMembersViewModel(FakeBlocks(mutableSetOf("a", "b")))
        advanceUntilIdle()
        val member = (vm.uiState.value as BlockedMembersUiState.Content).members.first { it.userId == "a" }
        vm.unblock(member)
        advanceUntilIdle()
        assertEquals(listOf("b"), (vm.uiState.value as BlockedMembersUiState.Content).members.map { it.userId })
    }

    @Test
    fun `reporting a member sends where it came from, closes the dialog and thanks them`() = runTest {
        val blocks = FakeBlocks()
        val vm = BlockViewModel("them", blocks, FakeAuth())
        advanceUntilIdle()
        vm.openReport()
        assertTrue(vm.uiState.value.isReportOpen)

        vm.events.test {
            vm.report(ReportReason.HARASSMENT, "Rude", "Reported from the chat about \"X\" (request r1).")
            advanceUntilIdle()
            assertEquals(BlockEvent.Message("Thanks. The OLK team will review your report."), awaitItem())
        }
        assertFalse(vm.uiState.value.isReportOpen)
        assertEquals(
            listOf(listOf("me", "them", ReportReason.HARASSMENT, "Rude", "Reported from the chat about \"X\" (request r1).")),
            blocks.reports,
        )
    }

    @Test
    fun `an open report on the same member is explained`() = runTest {
        val vm = BlockViewModel("them", FakeBlocks().apply { reportOutcome = ReportOutcome.AlreadyReported }, FakeAuth())
        advanceUntilIdle()
        vm.events.test {
            vm.report(ReportReason.OTHER, "", null)
            advanceUntilIdle()
            assertEquals(
                BlockEvent.Message("You've already reported this member, and it's waiting for review."),
                awaitItem(),
            )
        }
    }
}
