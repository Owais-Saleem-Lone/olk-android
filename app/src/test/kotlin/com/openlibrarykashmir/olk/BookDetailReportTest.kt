package com.openlibrarykashmir.olk

import app.cash.turbine.test
import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.BookCondition
import com.openlibrarykashmir.olk.core.data.model.BookDetail
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.model.ReportOutcome
import com.openlibrarykashmir.olk.core.data.model.ReportReason
import com.openlibrarykashmir.olk.core.data.model.RequestOutcome
import com.openlibrarykashmir.olk.core.data.repository.BookDetailRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.bookdetail.BookDetailUiState
import com.openlibrarykashmir.olk.feature.bookdetail.BookDetailViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailReportTest {

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

    private class FakeBookDetail(
        val ownerId: String = "someone-else",
        var outcome: ReportOutcome = ReportOutcome.Sent,
    ) : BookDetailRepository {
        var reported: Triple<String, ReportReason, String?>? = null

        override suspend fun load(bookId: String, viewerId: String) = BookDetail(
            book = Book(
                id = bookId, title = "A Book", author = null, coverUrl = null,
                condition = BookCondition.GOOD, listingType = ListingType.LEND,
                status = BookStatus.AVAILABLE, ownerId = ownerId,
            ),
            owner = null,
            readingProgressPct = null,
            isOwnBook = ownerId == viewerId,
            myRequestStatus = null,
            isSaved = false,
        )

        override suspend fun requestBook(bookId: String, requesterId: String): RequestOutcome =
            RequestOutcome.AlreadyRequested

        override suspend fun setSaved(bookId: String, userId: String, saved: Boolean) = Unit

        override suspend fun report(
            bookId: String,
            reporterId: String,
            reason: ReportReason,
            details: String?,
        ): ReportOutcome {
            reported = Triple(bookId, reason, details)
            return outcome
        }
    }

    private fun viewModel(repo: FakeBookDetail) = BookDetailViewModel("b1", repo, FakeAuth())

    private fun content(vm: BookDetailViewModel) = vm.uiState.value as BookDetailUiState.Content

    @Test
    fun `reasons are exactly the five the database accepts`() {
        assertEquals(
            listOf(
                "Inappropriate content", "Spam or fake listing", "Offensive language",
                "Suspicious activity", "Other",
            ),
            ReportReason.entries.map { it.label },
        )
    }

    @Test
    fun `sends the report and says so`() = runTest {
        val repo = FakeBookDetail()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.messages.test {
            vm.openReport()
            assertTrue(content(vm).isReportOpen)

            vm.report(ReportReason.SPAM, " looks fake ")
            advanceUntilIdle()

            assertEquals(Triple("b1", ReportReason.SPAM, " looks fake "), repo.reported)
            assertEquals("Thanks. The OLK team will review your report.", awaitItem())
            assertFalse(content(vm).isReportOpen)
        }
    }

    @Test
    fun `explains a repeat report, the daily cap and a suspension`() = runTest {
        val cases = mapOf(
            ReportOutcome.AlreadyReported to "You've already reported this book, and it's waiting for review.",
            ReportOutcome.DailyLimitReached to "You've sent a lot of reports today. Please try again tomorrow.",
            ReportOutcome.Suspended to "Your account is suspended, so you can't send reports until it ends.",
        )
        for ((outcome, expected) in cases) {
            val vm = viewModel(FakeBookDetail(outcome = outcome))
            advanceUntilIdle()
            vm.messages.test {
                vm.report(ReportReason.OTHER, "")
                advanceUntilIdle()
                assertEquals(expected, awaitItem())
            }
        }
    }

    @Test
    fun `never offers to report your own book`() = runTest {
        val vm = viewModel(FakeBookDetail(ownerId = "me"))
        advanceUntilIdle()

        assertFalse(content(vm).canReport)
        vm.openReport()
        assertFalse(content(vm).isReportOpen)

        val repo = FakeBookDetail(ownerId = "me")
        val own = viewModel(repo)
        advanceUntilIdle()
        own.report(ReportReason.OTHER, "")
        advanceUntilIdle()
        assertEquals(null, repo.reported)
    }
}
