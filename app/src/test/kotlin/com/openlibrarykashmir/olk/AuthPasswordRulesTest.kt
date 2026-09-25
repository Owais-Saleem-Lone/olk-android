package com.openlibrarykashmir.olk

import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.auth.AuthViewModel
import com.openlibrarykashmir.olk.feature.auth.PasswordRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Web F5 (2026-09-24): new passwords need 8+ characters with a letter and a digit. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthPasswordRulesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuth(private val signUpError: Throwable? = null) : AuthRepository {
        override val authState: Flow<AuthState> = emptyFlow()
        override fun currentUserId(): String? = null
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) {
            signUpError?.let { throw it }
        }
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    @Test
    fun `the rule matches what Supabase refuses and accepts`() {
        assertEquals(8, PasswordRules.MIN_LENGTH)
        assertFalse(PasswordRules.isAcceptable("short1"))
        assertFalse(PasswordRules.isAcceptable("longpassword"))
        assertFalse(PasswordRules.isAcceptable("12345678"))
        // Supabase counts only A-Z/a-z as letters.
        assertFalse(PasswordRules.isAcceptable("کتابکتاب12"))
        assertTrue(PasswordRules.isAcceptable("longpass1"))
    }

    @Test
    fun `an older, shorter password can still sign in`() {
        val vm = AuthViewModel(FakeAuth())
        vm.onEmailChange("member@example.com")
        vm.onPasswordChange("abc123")
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test
    fun `a new account needs a password that meets the rule`() {
        val vm = AuthViewModel(FakeAuth())
        vm.toggleMode()
        vm.onEmailChange("new@example.com")
        vm.onAgeConfirmedChange(true)
        vm.onPasswordChange("abc123")
        assertFalse(vm.uiState.value.canSubmit)
        vm.onPasswordChange("abcdefg1")
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test
    fun `a new account needs the 18-or-older confirmation, signing in does not`() {
        val vm = AuthViewModel(FakeAuth())
        vm.onEmailChange("member@example.com")
        vm.onPasswordChange("abcdefg1")
        assertTrue(vm.uiState.value.canSubmit)
        vm.toggleMode()
        assertFalse(vm.uiState.value.canSubmit)
        vm.onAgeConfirmedChange(true)
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test
    fun `Supabase's weak password message is replaced with plain words`() = runTest {
        val vm = AuthViewModel(
            FakeAuth(
                IllegalStateException(
                    "weak_password: Password should contain at least one character of each: abcdefghijklmnopqrstuvwxyz",
                ),
            ),
        )
        vm.toggleMode()
        vm.onEmailChange("new@example.com")
        vm.onPasswordChange("abcdefg1")
        vm.onAgeConfirmedChange(true)
        vm.submit()
        advanceUntilIdle()
        assertEquals(
            "Choose a stronger password: at least 8 characters, with a letter and a number.",
            vm.uiState.value.error,
        )
    }
}
