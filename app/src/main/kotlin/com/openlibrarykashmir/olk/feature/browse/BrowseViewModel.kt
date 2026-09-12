package com.openlibrarykashmir.olk.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.repository.BookRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val books: List<Book> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class BrowseViewModel(
    private val bookRepository: BookRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        // First page loads immediately; `drop(1)` skips queryFlow's initial "" so the
        // cold start does not sit behind the search debounce.
        load(query = "")

        viewModelScope.launch {
            // Debounced so that typing a search term costs one round trip rather
            // than one per keystroke — this matters on a throttled connection.
            queryFlow
                .drop(1)
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { load(it) }
        }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        queryFlow.value = value
    }

    fun retry() = load(_uiState.value.query)

    private fun load(query: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                bookRepository.browse(query = query.takeIf { it.isNotBlank() })
            }.onSuccess { books ->
                _uiState.update {
                    it.copy(
                        books = books,
                        isLoading = false,
                        endReached = books.size < BookRepository.PAGE_SIZE,
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(isLoading = false, error = throwable.toUserMessage())
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || state.endReached) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            runCatching {
                bookRepository.browse(
                    offset = state.books.size,
                    query = state.query.takeIf { it.isNotBlank() },
                )
            }.onSuccess { page ->
                _uiState.update {
                    it.copy(
                        books = it.books + page,
                        isLoadingMore = false,
                        endReached = page.size < BookRepository.PAGE_SIZE,
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(isLoadingMore = false, error = throwable.toUserMessage())
                }
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

private fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Could not load books. Pull to retry."
    }
}
