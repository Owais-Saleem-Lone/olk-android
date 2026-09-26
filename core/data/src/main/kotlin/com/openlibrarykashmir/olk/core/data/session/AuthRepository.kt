package com.openlibrarykashmir.olk.core.data.session

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.openlibrarykashmir.olk.core.data.model.TERMS_VERSION
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Who, if anyone, is signed in. */
sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String?) : AuthState
}

interface AuthRepository {
    /** Emits on every sign-in, sign-out and token refresh. */
    val authState: Flow<AuthState>

    /** The signed-in user's id, or null. */
    fun currentUserId(): String?

    suspend fun signIn(email: String, password: String)
    suspend fun signUp(email: String, password: String)
    suspend fun signOut()
    suspend fun sendPasswordReset(email: String)
}

/** Where the reset email's link lands: the website's /reset-password, via its /auth/confirm route. */
fun passwordResetRedirect(websiteUrl: String): String =
    "${websiteUrl.trimEnd('/')}/auth/confirm?next=/reset-password"

internal class SupabaseAuthRepository(
    private val client: SupabaseClient,
    private val websiteUrl: String,
) : AuthRepository {

    override val authState: Flow<AuthState> =
        client.auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Authenticated -> AuthState.SignedIn(
                    userId = status.session.user?.id.orEmpty(),
                    email = status.session.user?.email,
                )
                is SessionStatus.NotAuthenticated -> AuthState.SignedOut
                // Both remaining states mean "we do not know yet": the stored session
                // is still being loaded, or a refresh is in flight. Showing the login
                // screen during either would sign the user out on every cold start.
                else -> AuthState.Loading
            }
        }

    override fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    override suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    override suspend fun signUp(email: String, password: String) {
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            // Which Terms of use this member accepted, as the website records it.
            data = buildJsonObject { put("terms_accepted", TERMS_VERSION) }
        }
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }

    /**
     * The new password is set on the WEBSITE, from the link in the email. The app has no
     * screen for it and handles no `olk://` link, so without this the link would lead
     * nowhere. This is the exact URL the website's own form sends, which production's
     * redirect allow-list already accepts.
     */
    override suspend fun sendPasswordReset(email: String) {
        client.auth.resetPasswordForEmail(email.trim(), redirectUrl = passwordResetRedirect(websiteUrl))
    }
}
