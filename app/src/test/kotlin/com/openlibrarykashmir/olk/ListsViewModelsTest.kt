package com.openlibrarykashmir.olk

import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.repository.AddWishOutcome
import com.openlibrarykashmir.olk.core.data.repository.ListsRepository
import com.openlibrarykashmir.olk.core.data.repository.SavedBook
import com.openlibrarykashmir.olk.core.data.repository.WishlistItem
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.lists.SavedViewModel
import com.openlibrarykashmir.olk.feature.lists.WishlistViewModel
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
class ListsViewModelsTest {

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

    private class FakeLists(
        var saved: List<SavedBook> = emptyList(),
        var wishes: List<WishlistItem> = emptyList(),
        var addOutcome: AddWishOutcome = AddWishOutcome.Added,
        var failRemove: Boolean = false,
    ) : ListsRepository {
        var lastAdd: Triple<String, String, String?>? = null
        val removedSaved = mutableListOf<String>()

        override suspend fun savedBooks(userId: String) = saved
        override suspend fun removeSaved(bookmarkId: String) {
            if (failRemove) throw RuntimeException("Unable to resolve host")
            removedSaved += bookmarkId
        }
        override suspend fun wishlist(userId: String) = wishes
        override suspend fun addWish(userId: String, title: String, author: String?): AddWishOutcome {
            lastAdd = Triple(userId, title, author)
            if (addOutcome == AddWishOutcome.Added) wishes = listOf(WishlistItem("new", title, author)) + wishes
            return addOutcome
        }
        override suspend fun removeWish(id: String) {
            if (failRemove) throw RuntimeException("Unable to resolve host")
            wishes = wishes.filterNot { it.id == id }
        }
    }

    private fun saved(id: String) = SavedBook(
        bookmarkId = "bm-$id",
        bookId = id,
        title = "Book $id",
        author = null,
        listingType = ListingType.DONATE,
        status = BookStatus.AVAILABLE,
        coverUrl = null,
    )

    @Test
    fun `saved books load on refresh`() = runTest {
        val vm = SavedViewModel(FakeLists(saved = listOf(saved("a"), saved("b"))), FakeAuth(ME))
        vm.refresh()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(listOf("a", "b"), vm.uiState.value.books.map { it.bookId })
    }

    @Test
    fun `removing a saved book is optimistic and deletes by bookmark id`() = runTest {
        val repo = FakeLists(saved = listOf(saved("a"), saved("b")))
        val vm = SavedViewModel(repo, FakeAuth(ME))
        vm.refresh()
        advanceUntilIdle()

        vm.remove(vm.uiState.value.books.first())
        advanceUntilIdle()

        assertEquals(listOf("b"), vm.uiState.value.books.map { it.bookId })
        assertEquals(listOf("bm-a"), repo.removedSaved)
    }

    @Test
    fun `a failed remove puts the book back and says why`() = runTest {
        val vm = SavedViewModel(FakeLists(saved = listOf(saved("a")), failRemove = true), FakeAuth(ME))
        vm.refresh()
        advanceUntilIdle()

        vm.remove(vm.uiState.value.books.first())
        advanceUntilIdle()

        assertEquals(listOf("a"), vm.uiState.value.books.map { it.bookId })
        assertEquals("No connection. Check your network and try again.", vm.uiState.value.error)
    }

    @Test
    fun `without a session the tab says so instead of spinning`() = runTest {
        val vm = SavedViewModel(FakeLists(), FakeAuth(null))
        vm.refresh()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals("Your session has ended. Sign in again.", vm.uiState.value.error)
    }

    @Test
    fun `adding a wish sends trimmed text, clears the form and reloads`() = runTest {
        val repo = FakeLists()
        val vm = WishlistViewModel(repo, FakeAuth(ME))
        vm.refresh()
        advanceUntilIdle()

        vm.onTitleChange("Scattered Souls")
        vm.onAuthorChange("   ")
        vm.add()
        advanceUntilIdle()

        assertEquals(Triple(ME, "Scattered Souls", null), repo.lastAdd)
        val state = vm.uiState.value
        assertEquals("", state.title)
        assertEquals(listOf("Scattered Souls"), state.items.map { it.title })
        assertNull(state.formError)
    }

    @Test
    fun `a blank title cannot be added`() = runTest {
        val repo = FakeLists()
        val vm = WishlistViewModel(repo, FakeAuth(ME))
        vm.onTitleChange("   ")

        assertFalse(vm.uiState.value.canAdd)
        vm.add()
        advanceUntilIdle()
        assertNull(repo.lastAdd)
    }

    @Test
    fun `duplicate and full wishlist are explained and keep what was typed`() = runTest {
        val repo = FakeLists(addOutcome = AddWishOutcome.AlreadyOnList)
        val vm = WishlistViewModel(repo, FakeAuth(ME))
        vm.onTitleChange("Curfewed Night")
        vm.add()
        advanceUntilIdle()
        assertEquals("That title is already on your wishlist.", vm.uiState.value.formError)
        assertEquals("Curfewed Night", vm.uiState.value.title)

        repo.addOutcome = AddWishOutcome.ListFull
        vm.add()
        advanceUntilIdle()
        assertEquals("Your wishlist is full (100 books). Remove one to add another.", vm.uiState.value.formError)
        assertTrue(vm.uiState.value.canAdd)
    }

    @Test
    fun `typing is capped at the database's 500 characters`() = runTest {
        val vm = WishlistViewModel(FakeLists(), FakeAuth(ME))
        vm.onTitleChange("x".repeat(600))
        assertEquals(500, vm.uiState.value.title.length)
    }

    private companion object {
        const val ME = "user-me"
    }
}
