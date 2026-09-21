package com.openlibrarykashmir.olk

import com.openlibrarykashmir.olk.core.data.repository.FeatureFlags
import com.openlibrarykashmir.olk.core.data.repository.OwnProfile
import com.openlibrarykashmir.olk.core.data.repository.PlatformSettingsRepository
import com.openlibrarykashmir.olk.core.data.repository.ProfileRepository
import com.openlibrarykashmir.olk.core.data.repository.ProfileUpdate
import com.openlibrarykashmir.olk.core.data.repository.SharedLocation
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.ui.Suspension
import com.openlibrarykashmir.olk.ui.activeSuspension
import com.openlibrarykashmir.olk.ui.suspensionHeadline
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SuspensionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-09-21T12:00:00Z")

    private fun profile(banned: Boolean, until: String? = null, reason: String? = "Spam") =
        OwnProfile(id = "me", isBanned = banned, banExpiresAt = until, banReason = reason)

    // ── The rule ──

    @Test
    fun `a suspension without an end date is in force`() {
        assertEquals(Suspension("Spam", null), activeSuspension(profile(true), now))
    }

    @Test
    fun `a suspension is in force until its end date, and not after`() {
        assertEquals(
            Suspension("Spam", Instant.parse("2026-10-01T00:00:00Z")),
            activeSuspension(profile(true, "2026-10-01T00:00:00+00:00"), now),
        )
        assertNull(activeSuspension(profile(true, "2026-09-20T00:00:00+00:00"), now))
        // The same instant as the end: over, as in the database's `> now()`.
        assertNull(activeSuspension(profile(true, "2026-09-21T12:00:00+00:00"), now))
    }

    @Test
    fun `no suspension, or no profile, is none`() {
        assertNull(activeSuspension(profile(false), now))
        assertNull(activeSuspension(null, now))
    }

    @Test
    fun `the notice says until when, if there is an end`() {
        val zone = ZoneId.of("Asia/Kolkata")
        assertEquals(
            "Your account is suspended until 4 October 2026",
            suspensionHeadline(Suspension(null, Instant.parse("2026-10-04T06:00:00Z")), zone),
        )
        assertEquals("Your account is suspended", suspensionHeadline(Suspension(null, null), zone))
    }

    // ── Kept up to date across the app ──

    private class FakeAuth(initial: AuthState) : AuthRepository {
        val state = MutableStateFlow(initial)
        override val authState = state
        override fun currentUserId() = (state.value as? AuthState.SignedIn)?.userId
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    private class FakeSettings : PlatformSettingsRepository {
        override suspend fun featureFlags() = FeatureFlags()
    }

    private class FakeProfiles(var profile: OwnProfile?, var fail: Boolean = false) : ProfileRepository {
        var reads = 0
        override suspend fun myProfile(): OwnProfile? {
            reads++
            if (fail) error("offline")
            return profile
        }
        override suspend fun myLocation(userId: String): SharedLocation? = null
        override suspend fun areaSuggestions() = emptyList<String>()
        override suspend fun save(userId: String, update: ProfileUpdate) = Unit
    }

    private fun mainViewModel(auth: FakeAuth, profiles: FakeProfiles) =
        MainViewModel(auth, FakeSettings(), profiles, now = { now })

    @Test
    fun `signing in asks, and a suspended account is known app-wide`() = runTest {
        val auth = FakeAuth(AuthState.SignedIn("me", null))
        val viewModel = mainViewModel(auth, FakeProfiles(profile(true)))
        advanceUntilIdle()

        assertEquals(Suspension("Spam", null), viewModel.suspension.value)
    }

    @Test
    fun `a suspension lifted while the app was away is gone on return`() = runTest {
        val profiles = FakeProfiles(profile(true))
        val viewModel = mainViewModel(FakeAuth(AuthState.SignedIn("me", null)), profiles)
        advanceUntilIdle()

        profiles.profile = profile(false)
        viewModel.refreshSuspension()
        advanceUntilIdle()

        assertNull(viewModel.suspension.value)
    }

    @Test
    fun `a failed read keeps the last answer rather than guessing`() = runTest {
        val profiles = FakeProfiles(profile(true))
        val viewModel = mainViewModel(FakeAuth(AuthState.SignedIn("me", null)), profiles)
        advanceUntilIdle()

        profiles.fail = true
        viewModel.refreshSuspension()
        advanceUntilIdle()

        assertEquals(Suspension("Spam", null), viewModel.suspension.value)
    }

    @Test
    fun `signing out forgets it`() = runTest {
        val auth = FakeAuth(AuthState.SignedIn("me", null))
        val viewModel = mainViewModel(auth, FakeProfiles(profile(true)))
        advanceUntilIdle()

        auth.state.value = AuthState.SignedOut
        advanceUntilIdle()

        assertNull(viewModel.suspension.value)
    }
}
