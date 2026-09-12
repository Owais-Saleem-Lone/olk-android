package com.openlibrarykashmir.olk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Holds the one piece of state the whole app hangs off: whether someone is signed in.
 *
 * Kept at the activity level so the splash screen can stay up until [AuthState] is
 * something other than [AuthState.Loading] — without that, a cold start with a valid
 * stored session flashes the login screen for a frame before resolving.
 */
class MainViewModel(
    authRepository: AuthRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = AuthState.Loading,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
