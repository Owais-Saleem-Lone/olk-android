package com.openlibrarykashmir.olk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.repository.FeatureFlags
import com.openlibrarykashmir.olk.core.data.repository.PlatformSettingsRepository
import com.openlibrarykashmir.olk.core.data.repository.ProfileRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.ui.Suspension
import com.openlibrarykashmir.olk.ui.activeSuspension
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Holds the state the whole app hangs off: whether someone is signed in, which
 * features an admin has left switched on, and whether the signed-in account is
 * suspended.
 *
 * Kept at the activity level so the splash screen can stay up until [AuthState] is
 * something other than [AuthState.Loading] — without that, a cold start with a valid
 * stored session flashes the login screen for a frame before resolving.
 */
class MainViewModel(
    authRepository: AuthRepository,
    private val platformSettings: PlatformSettingsRepository,
    private val profiles: ProfileRepository,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = AuthState.Loading,
    )

    private val _featureFlags = MutableStateFlow(FeatureFlags())
    val featureFlags: StateFlow<FeatureFlags> = _featureFlags.asStateFlow()

    private val _suspension = MutableStateFlow<Suspension?>(null)

    /** Null unless the signed-in account is suspended right now. */
    val suspension: StateFlow<Suspension?> = _suspension.asStateFlow()

    init {
        refreshFeatureFlags()
        // A sign-in (or switching accounts) asks again; signing out forgets it.
        viewModelScope.launch {
            authRepository.authState
                .map { (it as? AuthState.SignedIn)?.userId }
                .distinctUntilChanged()
                .collect { userId -> if (userId == null) _suspension.value = null else refreshSuspension() }
        }
    }

    /**
     * Re-read on every return to the foreground: an admin can suspend someone,
     * or lift it, while the app sits in the background. The database enforces
     * the suspension either way; this only decides whether the app says so. A
     * failed read keeps the last answer rather than guessing.
     */
    fun refreshSuspension() {
        viewModelScope.launch {
            runCatching { profiles.myProfile() }
                .onSuccess { profile -> _suspension.value = activeSuspension(profile, now()) }
        }
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
