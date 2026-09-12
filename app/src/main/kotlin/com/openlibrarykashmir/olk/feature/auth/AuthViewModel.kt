package com.openlibrarykashmir.olk.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { SIGN_IN, SIGN_UP }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val canSubmit: Boolean
        get() = !isSubmitting && email.contains('@') && password.length >= MIN_PASSWORD_LENGTH

    companion object {
        /** Matches the minimum Supabase Auth enforces server-side. */
        const val MIN_PASSWORD_LENGTH = 6
    }
}

class AuthViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }

    fun toggleMode() = _uiState.update {
        it.copy(
            mode = if (it.mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN,
            error = null,
            notice = null,
        )
    }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null, notice = null) }
            runCatching {
                when (state.mode) {
                    AuthMode.SIGN_IN -> authRepository.signIn(state.email, state.password)
                    AuthMode.SIGN_UP -> authRepository.signUp(state.email, state.password)
                }
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        // Sign-in navigates away on the auth-state change, so this
                        // notice is only ever seen after sign-up.
                        notice = if (state.mode == AuthMode.SIGN_UP) {
                            "Check your email to confirm your account."
                        } else {
                            null
                        },
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(isSubmitting = false, error = throwable.toUserMessage())
                }
            }
        }
    }

    fun dismissNotice() = _uiState.update { it.copy(notice = null, error = null) }
}

/**
 * Supabase surfaces auth failures as prose in the exception message. Map the cases
 * a user can actually act on; anything else gets a generic line rather than a raw
 * stack-trace string, which is both unreadable and a small information leak.
 */
private fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "invalid login credentials" in raw -> "That email and password do not match."
        "email not confirmed" in raw -> "Confirm your email address first — check your inbox."
        "user already registered" in raw -> "That email already has an account. Try signing in."
        "rate limit" in raw || "too many" in raw -> "Too many attempts. Wait a minute and try again."
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Something went wrong. Please try again."
    }
}
