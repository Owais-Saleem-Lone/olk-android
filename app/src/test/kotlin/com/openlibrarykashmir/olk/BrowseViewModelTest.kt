package com.openlibrarykashmir.olk

import app.cash.turbine.test
import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.repository.BookRepository
import com.openlibrarykashmir.olk.feature.browse.BrowseViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun book(id: String) = Book(
        id = id,
        ownerId = "owner-1",
        title = "Book $id",
        listingType = ListingType.DONATE,
    )

    private class FakeBookRepository(
        private val pages: List<List<Book>>,
        private val failWith: Throwable? = null,
    ) : BookRepository {
        var lastQuery: String? = null
        var callCount = 0

        override suspend fun browse(limit: Int, offset: Int, query: String?): List<Book> {
            failWith?.let { throw it }
            lastQuery = query
            callCount++
            return pages.getOrElse(offset / BookRepository.PAGE_SIZE) { emptyList() }
        }

        override suspend fun byId(id: String) = null
    }

    @Test
    fun `loads first page immediately without waiting for the search debounce`() = runTest {
        val repo = FakeBookRepository(pages = listOf(listOf(book("a"), book("b"))))
        val viewModel = BrowseViewModel(repo)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("a", "b"), state.books.map { it.id })
        assertEquals(1, repo.callCount)
        // A short first page means there is nothing more to fetch.
        assertTrue(state.endReached)
    }

    @Test
    fun `network failure surfaces an actionable message rather than the raw exception`() = runTest {
        val repo = FakeBookRepository(
            pages = emptyList(),
            failWith = RuntimeException("Unable to resolve host api.supabase.co"),
        )
        val viewModel = BrowseViewModel(repo)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("No connection. Check your network and try again.", state.error)
    }

    @Test
    fun `loadMore appends the next page and stops at the end`() = runTest {
        val firstPage = (1..BookRepository.PAGE_SIZE).map { book("p1-$it") }
        val repo = FakeBookRepository(pages = listOf(firstPage, listOf(book("p2-1"))))
        val viewModel = BrowseViewModel(repo)
        advanceUntilIdle()

        // A full first page means more may exist.
        assertFalse(viewModel.uiState.value.endReached)

        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(BookRepository.PAGE_SIZE + 1, state.books.size)
        assertEquals("p2-1", state.books.last().id)
        assertTrue(state.endReached)
    }

    @Test
    fun `typing collapses into a single query after the debounce`() = runTest {
        val repo = FakeBookRepository(pages = listOf(emptyList()))
        val viewModel = BrowseViewModel(repo)
        advanceUntilIdle()
        val callsAfterInitialLoad = repo.callCount

        viewModel.onQueryChange("r")
        viewModel.onQueryChange("ru")
        viewModel.onQueryChange("rum")
        advanceUntilIdle()

        assertEquals("rum", repo.lastQuery)
        // Three keystrokes, one round trip.
        assertEquals(callsAfterInitialLoad + 1, repo.callCount)
    }

    @Test
    fun `blank query is sent as null so the repository omits the filter`() = runTest {
        val repo = FakeBookRepository(pages = listOf(emptyList()))
        val viewModel = BrowseViewModel(repo)
        advanceUntilIdle()

        viewModel.onQueryChange("   ")
        advanceUntilIdle()

        assertEquals(null, repo.lastQuery)
    }

    @Test
    fun `uiState emits loading before content`() = runTest {
        val repo = FakeBookRepository(pages = listOf(listOf(book("a"))))
        val viewModel = BrowseViewModel(repo)

        viewModel.uiState.test {
            assertTrue(awaitItem().isLoading)
            advanceUntilIdle()
            assertEquals(listOf("a"), expectMostRecentItem().books.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
