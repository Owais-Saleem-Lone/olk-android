package com.openlibrarykashmir.olk.feature.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.repository.AddWishOutcome
import com.openlibrarykashmir.olk.core.data.repository.ListsRepository
import com.openlibrarykashmir.olk.core.data.repository.SavedBook
import com.openlibrarykashmir.olk.core.data.repository.WishlistItem
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SavedUiState(
    val books: List<SavedBook> = emptyList(),
    /** True only until the first load finishes; later refreshes keep the list on screen. */
    val isLoading: Boolean = true,
    val error: String? = null,
)

class SavedViewModel(
    private val lists: ListsRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SavedUiState())
    val uiState: StateFlow<SavedUiState> = _uiState.asStateFlow()

    /** Called whenever the tab shows: a book saved on its detail page should already be here. */
    fun refresh() {
        val userId = auth.currentUserId() ?: return sessionEnded()
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            runCatching { lists.savedBooks(userId) }
                .onSuccess { books -> _uiState.update { it.copy(books = books, isLoading = false) } }
                .onFailure { t -> _uiState.update { it.copy(isLoading = false, error = t.toUserMessage()) } }
        }
    }

    /** Optimistic: the card goes at once and comes back if the delete fails. */
    fun remove(book: SavedBook) {
        val before = _uiState.value.books
        _uiState.update { it.copy(books = it.books - book) }
        viewModelScope.launch {
            runCatching { lists.removeSaved(book.bookmarkId) }.onFailure { t ->
                _uiState.update { it.copy(books = before, error = t.toUserMessage()) }
            }
        }
    }

    private fun sessionEnded() =
        _uiState.update { it.copy(isLoading = false, error = "Your session has ended. Sign in again.") }
}

data class WishlistUiState(
    val items: List<WishlistItem> = emptyList(),
    val title: String = "",
    val author: String = "",
    val isLoading: Boolean = true,
    val isAdding: Boolean = false,
    /** Why the last add was refused, shown under the form. */
    val formError: String? = null,
    val error: String? = null,
) {
    val canAdd: Boolean get() = title.isNotBlank() && !isAdding
}

class WishlistViewModel(
    private val lists: ListsRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WishlistUiState())
    val uiState: StateFlow<WishlistUiState> = _uiState.asStateFlow()

    fun refresh() {
        val userId = auth.currentUserId() ?: return sessionEnded()
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            runCatching { lists.wishlist(userId) }
                .onSuccess { items -> _uiState.update { it.copy(items = items, isLoading = false) } }
                .onFailure { t -> _uiState.update { it.copy(isLoading = false, error = t.toUserMessage()) } }
        }
    }

    fun onTitleChange(value: String) =
        _uiState.update { it.copy(title = value.take(ListsRepository.MAX_WISH_LENGTH), formError = null) }

    fun onAuthorChange(value: String) =
        _uiState.update { it.copy(author = value.take(ListsRepository.MAX_WISH_LENGTH), formError = null) }

    fun add() {
        val state = _uiState.value
        if (!state.canAdd) return
        val userId = auth.currentUserId() ?: return sessionEnded()
        viewModelScope.launch {
            _uiState.update { it.copy(isAdding = true, formError = null) }
            runCatching { lists.addWish(userId, state.title, state.author.ifBlank { null }) }
                .onSuccess { outcome ->
                    when (outcome) {
                        AddWishOutcome.Added -> {
                            _uiState.update { it.copy(isAdding = false, title = "", author = "") }
                            refresh()
                        }
                        AddWishOutcome.AlreadyOnList -> _uiState.update {
                            it.copy(isAdding = false, formError = "That title is already on your wishlist.")
                        }
                        AddWishOutcome.ListFull -> _uiState.update {
                            it.copy(isAdding = false, formError = "Your wishlist is full (100 books). Remove one to add another.")
                        }
                    }
                }
                .onFailure { t -> _uiState.update { it.copy(isAdding = false, formError = t.toUserMessage()) } }
        }
    }

    fun remove(item: WishlistItem) {
        val before = _uiState.value.items
        _uiState.update { it.copy(items = it.items - item) }
        viewModelScope.launch {
            runCatching { lists.removeWish(item.id) }.onFailure { t ->
                _uiState.update { it.copy(items = before, error = t.toUserMessage()) }
            }
        }
    }

    private fun sessionEnded() =
        _uiState.update { it.copy(isLoading = false, error = "Your session has ended. Sign in again.") }
}

private fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Something went wrong. Please try again."
    }
}
