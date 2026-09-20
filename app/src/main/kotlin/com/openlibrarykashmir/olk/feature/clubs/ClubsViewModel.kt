package com.openlibrarykashmir.olk.feature.clubs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.BrowseClub
import com.openlibrarykashmir.olk.core.data.model.MembershipStatus
import com.openlibrarykashmir.olk.core.data.repository.ClubsRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
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

data class ClubsUiState(
    val clubs: List<BrowseClub> = emptyList(),
    val query: String = "",
    val interest: String? = null,
    /** The signed-in user's standing with each club, for the card's button. */
    val memberships: Map<String, MembershipStatus> = emptyMap(),
    val joining: String? = null,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@OptIn(FlowPreview::class)
class ClubsViewModel(
    private val clubs: ClubsRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClubsUiState())
    val uiState: StateFlow<ClubsUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    // A newer search or filter cancels the one in flight, so a slow earlier
    // response can never replace newer results.
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

    /** Tapping the interest chip again clears it. */
    fun onInterestChange(interest: String?) {
        if (interest == _uiState.value.interest) return
        _uiState.update { it.copy(interest = interest) }
        load()
    }

    fun refresh() = load()

    fun retry() = load()

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun requestToJoin(clubId: String) {
        val userId = auth.currentUserId() ?: return
        if (_uiState.value.joining != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(joining = clubId) }
            runCatching { clubs.requestToJoin(clubId, userId) }
                .onSuccess {
                    // The owner's notification is written by a database trigger, so
                    // there is nothing to send from here.
                    _uiState.update {
                        it.copy(
                            joining = null,
                            memberships = it.memberships + (clubId to MembershipStatus.PENDING),
                            message = "Request sent. The club's owner will decide.",
                        )
                    }
                }
                .onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    // A duplicate means they already asked; treat it as success.
                    val alreadyAsked = throwable.message.orEmpty().contains("23505")
                    _uiState.update {
                        it.copy(
                            joining = null,
                            memberships = if (alreadyAsked) {
                                it.memberships + (clubId to MembershipStatus.PENDING)
                            } else {
                                it.memberships
                            },
                            message = if (alreadyAsked) {
                                "You have already asked to join this club."
                            } else {
                                "Could not send the request. Try again."
                            },
                        )
                    }
                }
        }
    }

    private fun load() {
        loadJob?.cancel()
        val state = _uiState.value
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isLoadingMore = false, error = null) }
            runCatching {
                clubs.browse(query = state.query, interest = state.interest)
            }.onSuccess { page ->
                _uiState.update {
                    it.copy(
                        clubs = page,
                        isLoading = false,
                        endReached = page.size < ClubsRepository.PAGE_SIZE,
                    )
                }
                loadMemberships()
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                _uiState.update { it.copy(isLoading = false, error = throwable.toClubsMessage()) }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || state.endReached) return

        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            runCatching {
                clubs.browse(query = state.query, interest = state.interest, offset = state.clubs.size)
            }.onSuccess { page ->
                _uiState.update {
                    val seen = it.clubs.mapTo(HashSet()) { club -> club.id }
                    it.copy(
                        clubs = it.clubs + page.filterNot { club -> club.id in seen },
                        isLoadingMore = false,
                        endReached = page.size < ClubsRepository.PAGE_SIZE,
                    )
                }
                loadMemberships()
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                _uiState.update { it.copy(isLoadingMore = false, error = throwable.toClubsMessage()) }
            }
        }
    }

    /**
     * All of the user's memberships in one round trip, as the website does — not
     * one lookup per card. Signed out there is nothing to ask about, and a failure
     * here only costs the cards their button state, so it never fails the list.
     */
    private suspend fun loadMemberships() {
        val userId = auth.currentUserId() ?: return
        runCatching { clubs.myMemberships(userId) }
            .onSuccess { found -> _uiState.update { it.copy(memberships = found) } }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

internal fun Throwable.toClubsMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Could not load clubs. Pull to retry."
    }
}
