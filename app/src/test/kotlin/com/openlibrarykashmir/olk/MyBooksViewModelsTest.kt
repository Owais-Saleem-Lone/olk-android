package com.openlibrarykashmir.olk

import app.cash.turbine.test
import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.repository.BookEdit
import com.openlibrarykashmir.olk.core.data.repository.BookRepository
import com.openlibrarykashmir.olk.core.data.repository.DeleteOutcome
import com.openlibrarykashmir.olk.core.data.repository.MyBooksRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.mybooks.EditBookEvent
import com.openlibrarykashmir.olk.feature.mybooks.EditBookUiState
import com.openlibrarykashmir.olk.feature.mybooks.EditBookViewModel
import com.openlibrarykashmir.olk.feature.mybooks.MyBooksViewModel
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
class MyBooksViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun book(
        id: String = "b1",
        ownerId: String = ME,
        listingType: ListingType = ListingType.LEND,
        acquiredViaDonation: Boolean = false,
    ) = Book(
        id = id,
        ownerId = ownerId,
        title = "Book $id",
        author = "An Author",
        listingType = listingType,
        genre = "Poetry",
        lendingDurationMonths = if (listingType == ListingType.LEND) 2 else null,
        acquiredViaDonation = acquiredViaDonation,
    )

    private class FakeAuth(private val userId: String?) : AuthRepository {
        override val authState: Flow<AuthState> = emptyFlow()
        override fun currentUserId() = userId
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    private class FakeBooks(private val book: Book?) : BookRepository {
        override suspend fun browse(limit: Int, offset: Int, query: String?) = emptyList<Book>()
        override suspend fun byId(id: String) = book
    }

    private class FakeMyBooks(
        var books: List<Book> = emptyList(),
        var deleteOutcome: DeleteOutcome = DeleteOutcome.Deleted,
        var failWith: Throwable? = null,
    ) : MyBooksRepository {
        var lastEdit: BookEdit? = null
        var deleteCalls = 0

        override suspend fun list(ownerId: String): List<Book> {
            failWith?.let { throw it }
            return books
        }

        override suspend fun update(bookId: String, edit: BookEdit): Book? {
            failWith?.let { throw it }
            lastEdit = edit
            return books.firstOrNull { it.id == bookId }
        }

        override suspend fun delete(bookId: String): DeleteOutcome {
            deleteCalls++
            return deleteOutcome
        }

        override suspend fun genres() = listOf("General", "Fiction")
    }

    @Test
    fun `my books refresh keeps the list on screen when a later refresh fails`() = runTest {
        val repo = FakeMyBooks(books = listOf(book("a"), book("b")))
        val viewModel = MyBooksViewModel(repo, FakeAuth(ME))

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), viewModel.uiState.value.books.map { it.id })

        repo.failWith = RuntimeException("timeout")
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.books.size)
        assertEquals("No connection. Check your network and try again.", state.error)
    }

    @Test
    fun `edit form starts from the saved book and keeps a deactivated genre selectable`() = runTest {
        val viewModel = EditBookViewModel("b1", FakeBooks(book()), FakeMyBooks(), FakeAuth(ME))
        advanceUntilIdle()

        val state = viewModel.uiState.value as EditBookUiState.Editing
        assertEquals("Book b1", state.form.title)
        assertEquals(2, state.form.lendingDurationMonths)
        // "Poetry" is not in the fake's active genres, but it is the book's genre.
        assertEquals(listOf("General", "Fiction", "Poetry"), state.genreOptions)
    }

    @Test
    fun `someone else's book is reported as not found rather than editable`() = runTest {
        val viewModel = EditBookViewModel("b1", FakeBooks(book(ownerId = "someone-else")), FakeMyBooks(), FakeAuth(ME))
        advanceUntilIdle()

        assertEquals(EditBookUiState.NotFound, viewModel.uiState.value)
    }

    @Test
    fun `blank title is caught before any request is made`() = runTest {
        val repo = FakeMyBooks(books = listOf(book()))
        val viewModel = EditBookViewModel("b1", FakeBooks(book()), repo, FakeAuth(ME))
        advanceUntilIdle()

        viewModel.onFormChange { it.copy(title = "   ") }
        viewModel.save()
        advanceUntilIdle()

        assertNull(repo.lastEdit)
        assertTrue((viewModel.uiState.value as EditBookUiState.Editing).showErrors)
    }

    @Test
    fun `saving trims fields, clears a blank author and marks the book given`() = runTest {
        val repo = FakeMyBooks(books = listOf(book()))
        val viewModel = EditBookViewModel("b1", FakeBooks(book()), repo, FakeAuth(ME))
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onFormChange { it.copy(title = "  New Title ", author = "  ", status = BookStatus.GIVEN) }
            viewModel.save()
            advanceUntilIdle()

            assertEquals(EditBookEvent.Done("Changes saved"), awaitItem())
        }
        assertEquals(
            BookEdit(title = "New Title", author = null, status = BookStatus.GIVEN, genre = "Poetry", lendingDurationMonths = 2),
            repo.lastEdit,
        )
    }

    @Test
    fun `a donation never sends a lending period`() = runTest {
        val donation = book(listingType = ListingType.DONATE)
        val repo = FakeMyBooks(books = listOf(donation))
        val viewModel = EditBookViewModel("b1", FakeBooks(donation), repo, FakeAuth(ME))
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertNull(repo.lastEdit?.lendingDurationMonths)
    }

    @Test
    fun `a book in circulation cannot be deleted from the app`() = runTest {
        val circulating = book(acquiredViaDonation = true)
        val repo = FakeMyBooks(books = listOf(circulating))
        val viewModel = EditBookViewModel("b1", FakeBooks(circulating), repo, FakeAuth(ME))
        advanceUntilIdle()

        assertFalse((viewModel.uiState.value as EditBookUiState.Editing).canDelete)
        viewModel.delete()
        advanceUntilIdle()

        assertEquals(0, repo.deleteCalls)
    }

    @Test
    fun `a delete the database refuses keeps the user on the form with a message`() = runTest {
        val repo = FakeMyBooks(books = listOf(book()), deleteOutcome = DeleteOutcome.NotAllowed)
        val viewModel = EditBookViewModel("b1", FakeBooks(book()), repo, FakeAuth(ME))
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.delete()
            advanceUntilIdle()

            assertEquals(EditBookEvent.Message("This book can't be deleted."), awaitItem())
        }
        assertFalse((viewModel.uiState.value as EditBookUiState.Editing).isDeleting)
    }

    private companion object {
        const val ME = "me"
    }
}
