package com.openlibrarykashmir.olk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.repository.FeatureFlags
import com.openlibrarykashmir.olk.core.data.repository.PlatformSettingsRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Holds the two pieces of state the whole app hangs off: whether someone is
 * signed in, and which features an admin has left switched on.
 *
 * Kept at the activity level so the splash screen can stay up until [AuthState] is
 * something other than [AuthState.Loading] — without that, a cold start with a valid
 * stored session flashes the login screen for a frame before resolving.
 */
class MainViewModel(
    authRepository: AuthRepository,
    private val platformSettings: PlatformSettingsRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = AuthState.Loading,
    )

    private val _featureFlags = MutableStateFlow(FeatureFlags())
    val featureFlags: StateFlow<FeatureFlags> = _featureFlags.asStateFlow()

    init {
        refreshFeatureFlags()
    }

    /**
     * Re-read whenever the app comes to the foreground, the way the website
     * re-reads platform_settings on every page load. A failure keeps the last
     * known answer (defaults on a cold start): never hide a feature because one
     * request did not come back.
     */
    fun refreshFeatureFlags() {
        viewModelScope.launch {
            runCatching { platformSettings.featureFlags() }
                .onSuccess { _featureFlags.value = it }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
