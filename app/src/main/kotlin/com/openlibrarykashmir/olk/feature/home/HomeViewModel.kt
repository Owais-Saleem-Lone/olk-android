package com.openlibrarykashmir.olk.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.repository.HomeFeed
import com.openlibrarykashmir.olk.core.data.repository.HomeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

data class HomeUiState(
    val feed: HomeFeed = HomeFeed(),
    /** True only until the first load finishes; later refreshes keep Home on screen. */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(
    private val home: HomeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Called whenever Home shows, so new listings and stats are there without pulling. */
    fun refresh(userInitiated: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = userInitiated, error = null) }
            runCatching { home.feed() }
                .onSuccess { feed -> _uiState.update { it.copy(feed = feed, isLoading = false, isRefreshing = false) } }
                .onFailure { t ->
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = t.toUserMessage()) }
                }
        }
    }
}

/** Same wording as the website's `getTimeAgo` on the homepage. */
fun timeAgo(iso: String, now: Instant = Clock.System.now()): String {
    val then = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val mins = (now - then).inWholeMinutes
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

/** The website shows the part of an email-style display name before the @, or "Someone". */
fun activityName(displayName: String?): String =
    displayName?.substringBefore('@')?.takeIf { it.isNotBlank() } ?: "Someone"

private fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Could not load Home. Please try again."
    }
}
