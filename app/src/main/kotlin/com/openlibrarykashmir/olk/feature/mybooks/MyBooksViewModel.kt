package com.openlibrarykashmir.olk.feature.mybooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.repository.MyBooksRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MyBooksUiState(
    val books: List<Book> = emptyList(),
    /** True only until the first load finishes; later refreshes keep the list on screen. */
    val isLoading: Boolean = true,
    val error: String? = null,
)

class MyBooksViewModel(
    private val repository: MyBooksRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MyBooksUiState())
    val uiState: StateFlow<MyBooksUiState> = _uiState.asStateFlow()

    /**
     * Called every time the screen starts, not from `init`: coming back from the
     * edit screen (or from the website on another device) should show the saved
     * state without the user having to pull anything.
     */
    fun refresh() {
        val ownerId = auth.currentUserId() ?: run {
            _uiState.update { it.copy(isLoading = false, error = "Your session has ended. Sign in again.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            runCatching { repository.list(ownerId) }
                .onSuccess { books -> _uiState.update { it.copy(books = books, isLoading = false) } }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, error = throwable.toUserMessage()) }
                }
        }
    }
}

private fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Could not load your books. Please try again."
    }
}
