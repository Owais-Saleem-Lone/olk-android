package com.openlibrarykashmir.olk

import app.cash.turbine.test
import com.openlibrarykashmir.olk.core.data.model.BrowseBook
import com.openlibrarykashmir.olk.core.data.model.BrowseFilters
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.repository.BookRepository
import com.openlibrarykashmir.olk.feature.browse.BrowseViewModel
import com.openlibrarykashmir.olk.feature.browse.formatDistance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

    private fun book(id: String) = BrowseBook(
        id = id,
        ownerId = "owner-1",
        title = "Book $id",
        listingType = ListingType.DONATE,
    )

    private class FakeBookRepository(
        private val pages: List<List<BrowseBook>>,
        private val failWith: Throwable? = null,
        private val hasLocation: Boolean = false,
    ) : BookRepository {
        var lastQuery: String? = null
        var lastFilters: BrowseFilters? = null
        var callCount = 0

        /** When set, the next browse() call waits on it; lets a test hold a response back. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun browse(filters: BrowseFilters, limit: Int, offset: Int): List<BrowseBook> {
            failWith?.let { throw it }
            // Mirrors the repository, which sends a blank query as no filter at all.
            lastQuery = filters.query.trim().takeIf { it.isNotEmpty() }
            lastFilters = filters
            callCount++
            gate?.also { gate = null }?.await()
            return pages.getOrElse(offset / BookRepository.PAGE_SIZE) { emptyList() }
        }

        override suspend fun byId(id: String) = null

        override suspend fun genres() = listOf("Poetry", "History")

        override suspend fun hasSavedLocation() = hasLocation
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

    @Test
    fun `applying filters reloads at once, without the search debounce, and keeps the search text`() = runTest {
        val repo = FakeBookRepository(pages = listOf(listOf(book("a"))))
        val viewModel = BrowseViewModel(repo)
        advanceUntilIdle()
        viewModel.onQueryChange("rumi")
        advanceUntilIdle()
        val calls = repo.callCount

        viewModel.applyFilters(BrowseFilters(genre = "Poetry", listingType = ListingType.LEND))
        runCurrent()

        assertEquals(calls + 1, repo.callCount)
        assertEquals(
            BrowseFilters(query = "rumi", genre = "Poetry", listingType = ListingType.LEND),
            repo.lastFilters,
        )
    }

    @Test
    fun `applying the same filters again does not refetch`() = runTest {
        val repo = FakeBookRepository(pages = listOf(emptyList()))
        val viewModel = BrowseViewModel(repo)
        advanceUntilIdle()
        viewModel.applyFilters(BrowseFilters(genre = "Poetry"))
        advanceUntilIdle()
        val calls = repo.callCount

        viewModel.applyFilters(BrowseFilters(genre = "Poetry"))
        advanceUntilIdle()

        assertEquals(calls, repo.callCount)
    }

    @Test
    fun `a slow earlier load never overwrites results for newer filters`() = runTest {
        val repo = FakeBookRepository(pages = listOf(listOf(book("a"))))
        val viewModel = BrowseViewModel(repo)
        advanceUntilIdle()

        repo.gate = CompletableDeferred()
        viewModel.applyFilters(BrowseFilters(genre = "History"))
        runCurrent()
        viewModel.applyFilters(BrowseFilters(genre = "Poetry"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Poetry", state.filters.genre)
        assertEquals("Poetry", repo.lastFilters?.genre)
    }

    @Test
    fun `loadMore keeps the active filters`() = runTest {
        val firstPage = (1..BookRepository.PAGE_SIZE).map { book("p1-$it") }
        val repo = FakeBookRepository(pages = listOf(firstPage, listOf(book("p2-1"))))
        val viewModel = BrowseViewModel(repo)
        advanceUntilIdle()
        viewModel.applyFilters(BrowseFilters(listingType = ListingType.DONATE))
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(ListingType.DONATE, repo.lastFilters?.listingType)
        assertEquals(BookRepository.PAGE_SIZE + 1, viewModel.uiState.value.books.size)
    }

    @Test
    fun `opening the sheet loads genres and whether a location is saved`() = runTest {
        val repo = FakeBookRepository(pages = listOf(emptyList()), hasLocation = true)
        val viewModel = BrowseViewModel(repo)

        viewModel.onFilterSheetOpened()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Poetry", "History"), state.genres)
        assertTrue(state.hasLocation)
    }

    @Test
    fun `a radius is dropped when there is no saved location, since it would match nothing`() = runTest {
        val repo = FakeBookRepository(pages = listOf(emptyList()), hasLocation = false)
        val viewModel = BrowseViewModel(repo)
        advanceUntilIdle()
        viewModel.applyFilters(BrowseFilters(radiusKm = 5, genre = "Poetry"))
        advanceUntilIdle()

        viewModel.onFilterSheetOpened()
        advanceUntilIdle()

        assertEquals(BrowseFilters(genre = "Poetry"), viewModel.uiState.value.filters)
        assertEquals(null, repo.lastFilters?.radiusKm)
    }

    @Test
    fun `distance labels match the web's wording`() {
        assertEquals(null, formatDistance(null))
        assertEquals("< 1 km", formatDistance(0.0))
        assertEquals("< 1 km", formatDistance(0.9))
        assertEquals("~1 km", formatDistance(1.1))
        assertEquals("~9 km", formatDistance(9.4))
        assertEquals("~10 km", formatDistance(11.1))
        assertEquals("~15 km", formatDistance(13.0))
        assertEquals("~20 km", formatDistance(19.6))
    }

    @Test
    fun `opened from Home's search box, the first load already uses that query`() = runTest {
        val repo = FakeBookRepository(pages = listOf(listOf(book("a"))))
        val viewModel = BrowseViewModel(repo, initialQuery = "rumi")
        advanceUntilIdle()

        assertEquals("rumi", viewModel.uiState.value.filters.query)
        assertEquals("rumi", repo.lastQuery)
        // Straight away, not after the debounce, and only once.
        assertEquals(1, repo.callCount)
    }
}
