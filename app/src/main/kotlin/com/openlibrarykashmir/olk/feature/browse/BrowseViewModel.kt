package com.openlibrarykashmir.olk.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.BrowseBook
import com.openlibrarykashmir.olk.core.data.model.BrowseFilters
import com.openlibrarykashmir.olk.core.data.repository.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val books: List<BrowseBook> = emptyList(),
    val filters: BrowseFilters = BrowseFilters(),
    val genres: List<String> = emptyList(),
    /** Whether the user has a saved location; the distance filter needs one. */
    val hasLocation: Boolean = false,
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

    // A newer load (new search or filters) cancels the one in flight, and any page
    // being appended, so a slow earlier response can never replace newer results.
    private var loadJob: Job? = null

    init {
        // First page loads immediately; `drop(1)` skips queryFlow's initial "" so the
        // cold start does not sit behind the search debounce.
        load()
        refreshLocation()

        viewModelScope.launch {
            // Debounced so that typing a search term costs one round trip rather
            // than one per keystroke — this matters on a throttled connection.
            queryFlow
                .drop(1)
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { load() }
        }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(filters = it.filters.copy(query = value)) }
        queryFlow.value = value
    }

    /** Applies the filter sheet (search text is kept as typed). Reloads at once: one tap, one request. */
    fun applyFilters(filters: BrowseFilters) {
        val current = _uiState.value.filters
        val next = filters.copy(query = current.query)
        if (next == current) return
        _uiState.update { it.copy(filters = next) }
        load()
    }

    fun clearFilters() = applyFilters(BrowseFilters())

    /**
     * Called when the filter sheet opens: loads genres once, and re-checks the saved
     * location, which may have been set or removed on the Profile screen since.
     */
    fun onFilterSheetOpened() {
        if (_uiState.value.genres.isEmpty()) {
            viewModelScope.launch {
                runCatching { bookRepository.genres() }
                    .onSuccess { genres -> _uiState.update { it.copy(genres = genres) } }
            }
        }
        refreshLocation()
    }

    fun retry() = load()

    private fun refreshLocation() {
        viewModelScope.launch {
            val hasLocation = runCatching { bookRepository.hasSavedLocation() }.getOrDefault(false)
            val radiusNowUseless = !hasLocation && _uiState.value.filters.radiusKm != null
            _uiState.update { it.copy(hasLocation = hasLocation) }
            // A radius without a saved location matches nothing; drop it rather than
            // show an empty list with no explanation.
            if (radiusNowUseless) applyFilters(_uiState.value.filters.copy(radiusKm = null))
        }
    }

    private fun load() {
        loadJob?.cancel()
        val filters = _uiState.value.filters
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isLoadingMore = false, error = null) }
            runCatching {
                bookRepository.browse(filters)
            }.onSuccess { books ->
                _uiState.update {
                    it.copy(
                        books = books,
                        isLoading = false,
                        endReached = books.size < BookRepository.PAGE_SIZE,
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                _uiState.update {
                    it.copy(isLoading = false, error = throwable.toUserMessage())
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || state.endReached) return

        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            runCatching {
                bookRepository.browse(state.filters, offset = state.books.size)
            }.onSuccess { page ->
                _uiState.update {
                    val seen = it.books.mapTo(HashSet()) { book -> book.id }
                    it.copy(
                        books = it.books + page.filterNot { book -> book.id in seen },
                        isLoadingMore = false,
                        endReached = page.size < BookRepository.PAGE_SIZE,
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
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
