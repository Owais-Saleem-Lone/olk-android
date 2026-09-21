package com.openlibrarykashmir.olk.feature.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.BrowseEvent
import com.openlibrarykashmir.olk.core.data.repository.EventsRepository
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

data class EventsUiState(
    val events: List<BrowseEvent> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class EventsViewModel(
    private val events: EventsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EventsUiState())
    val uiState: StateFlow<EventsUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    // A newer search cancels the one in flight, so a slow earlier response can
    // never replace newer results.
    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            queryFlow
                .drop(1)
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { load() }
        }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        queryFlow.value = value
    }

    fun refresh() = load(pulled = true)

    fun retry() = load()

    private fun load(pulled: Boolean = false) {
        loadJob?.cancel()
        val query = _uiState.value.query
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = !pulled, isRefreshing = pulled, isLoadingMore = false, error = null)
            }
            runCatching { events.browse(query = query) }
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            events = page,
                            isLoading = false,
                            isRefreshing = false,
                            endReached = page.size < EventsRepository.PAGE_SIZE,
                        )
                    }
                }
                .onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    _uiState.update {
                        it.copy(isLoading = false, isRefreshing = false, error = throwable.toEventsMessage())
                    }
                }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isRefreshing || state.isLoadingMore || state.endReached) return

        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            runCatching { events.browse(query = state.query, offset = state.events.size) }
                .onSuccess { page ->
                    _uiState.update {
                        val seen = it.events.mapTo(HashSet()) { event -> event.id }
                        it.copy(
                            events = it.events + page.filterNot { event -> event.id in seen },
                            isLoadingMore = false,
                            endReached = page.size < EventsRepository.PAGE_SIZE,
                        )
                    }
                }
                .onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    _uiState.update { it.copy(isLoadingMore = false, error = throwable.toEventsMessage()) }
                }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

internal fun Throwable.toEventsMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Could not load events. Try again."
    }
}
