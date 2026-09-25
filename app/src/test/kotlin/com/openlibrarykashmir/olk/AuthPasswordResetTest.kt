package com.openlibrarykashmir.olk

import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.auth.AuthViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** "Forgot password?" on the sign-in screen: the link itself is handled by the website. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthPasswordResetTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuth(private val resetError: Throwable? = null) : AuthRepository {
        val resetsSentTo = mutableListOf<String>()
        override val authState: Flow<AuthState> = emptyFlow()
        override fun currentUserId(): String? = null
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) {
            resetError?.let { throw it }
            resetsSentTo += email
        }
    }

    @Test
    fun `the dialog starts from the email already typed`() {
        val vm = AuthViewModel(FakeAuth())
        vm.onEmailChange(" member@example.com ")
        vm.openPasswordReset()
        assertEquals("member@example.com", vm.uiState.value.reset?.email)
    }

    @Test
    fun `no link without an email address`() {
        val auth = FakeAuth()
        val vm = AuthViewModel(auth)
        vm.openPasswordReset()
        assertFalse(vm.uiState.value.reset!!.canSend)
        vm.sendPasswordReset()
        assertTrue(auth.resetsSentTo.isEmpty())
    }

    @Test
    fun `a sent link closes the dialog and never says whether the account exists`() = runTest {
        val auth = FakeAuth()
        val vm = AuthViewModel(auth)
        vm.openPasswordReset()
        vm.onResetEmailChange("member@example.com")
        vm.sendPasswordReset()
        advanceUntilIdle()
        assertEquals(listOf("member@example.com"), auth.resetsSentTo)
        assertNull(vm.uiState.value.reset)
        assertTrue(vm.uiState.value.notice!!.startsWith("If an account exists for member@example.com"))
        assertEquals("member@example.com", vm.uiState.value.email)
    }

    @Test
    fun `GoTrue's once-a-minute refusal is said plainly and keeps the dialog open`() = runTest {
        val vm = AuthViewModel(
            FakeAuth(IllegalStateException("For security purposes, you can only request this after 42 seconds.")),
        )
        vm.openPasswordReset()
        vm.onResetEmailChange("member@example.com")
        vm.sendPasswordReset()
        advanceUntilIdle()
        val reset = vm.uiState.value.reset!!
        assertFalse(reset.isSending)
        assertEquals("A link was sent a moment ago. Wait a minute before asking for another.", reset.error)
    }
}
