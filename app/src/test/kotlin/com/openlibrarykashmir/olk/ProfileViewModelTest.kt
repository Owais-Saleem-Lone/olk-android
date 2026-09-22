package com.openlibrarykashmir.olk

import app.cash.turbine.test
import com.openlibrarykashmir.olk.core.data.repository.AccountDeletionRepository
import com.openlibrarykashmir.olk.core.data.repository.DeleteAccountOutcome
import com.openlibrarykashmir.olk.core.data.repository.DeletionBlocker
import com.openlibrarykashmir.olk.core.data.repository.OwnProfile
import com.openlibrarykashmir.olk.core.data.repository.ProfileRepository
import com.openlibrarykashmir.olk.core.data.repository.ProfileUpdate
import com.openlibrarykashmir.olk.core.data.repository.SharedLocation
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.profile.LocateOutcome
import com.openlibrarykashmir.olk.feature.profile.ProfileUiState
import com.openlibrarykashmir.olk.feature.profile.ProfileViewModel
import com.openlibrarykashmir.olk.feature.profile.matchingAreas
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-09-18T12:00:00Z")

    private class FakeAuth : AuthRepository {
        var signedOut = false
        override val authState: Flow<AuthState> = emptyFlow()
        override fun currentUserId() = "me"
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() { signedOut = true }
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    private class FakeProfiles(
        var profile: OwnProfile? = OwnProfile(id = "me", displayName = "App Tester", areaName = "Rajbagh", bio = null, emailDigest = true),
        var location: SharedLocation? = null,
        var areas: () -> List<String> = { listOf("Rajbagh, Srinagar", "Anantnag Town, Anantnag") },
        var failSave: Boolean = false,
    ) : ProfileRepository {
        val saved = mutableListOf<ProfileUpdate>()
        override suspend fun myProfile() = profile
        override suspend fun myLocation(userId: String) = location
        override suspend fun areaSuggestions() = areas()
        override suspend fun save(userId: String, update: ProfileUpdate) {
            if (failSave) error("Unable to resolve host")
            saved += update
        }
    }

    private class FakeDeletion(
        var blockers: List<DeletionBlocker> = emptyList(),
        var outcome: DeleteAccountOutcome = DeleteAccountOutcome.Deleted,
    ) : AccountDeletionRepository {
        var deleteCalls = 0
        override suspend fun blockers() = blockers
        override suspend fun delete(): DeleteAccountOutcome {
            deleteCalls++
            return outcome
        }
    }

    private fun viewModel(
        repository: FakeProfiles = FakeProfiles(),
        auth: FakeAuth = FakeAuth(),
        deletion: FakeDeletion = FakeDeletion(),
        locate: suspend () -> LocateOutcome = { LocateOutcome.Found(SharedLocation(34.08, 74.8)) },
    ) = ProfileViewModel(repository, deletion, auth, { locate() }, now = { now })

    private fun ProfileViewModel.editing() = uiState.value as ProfileUiState.Editing

    @Test
    fun `loads the profile, location and area suggestions`() = runTest {
        val vm = viewModel(FakeProfiles(location = SharedLocation(34.0, 74.0)))
        advanceUntilIdle()

        val state = vm.editing()
        assertEquals("App Tester", state.form.displayName)
        assertEquals("Rajbagh", state.form.areaName)
        assertEquals(SharedLocation(34.0, 74.0), state.form.location)
        assertEquals(2, state.areaSuggestions.size)
        assertFalse(state.hasChanges)
    }

    @Test
    fun `the form still opens when the area suggestions fail`() = runTest {
        val vm = viewModel(FakeProfiles(areas = { error("boom") }))
        advanceUntilIdle()

        assertTrue(vm.editing().areaSuggestions.isEmpty())
    }

    @Test
    fun `fields are capped at the database limits`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onDisplayNameChange("x".repeat(80))
        vm.onAreaChange("y".repeat(150))
        vm.onBioChange("z".repeat(400))

        with(vm.editing().form) {
            assertEquals(50, displayName.length)
            assertEquals(100, areaName.length)
            assertEquals(300, bio.length)
        }
    }

    @Test
    fun `a blank display name is not saved`() = runTest {
        val repository = FakeProfiles()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.onDisplayNameChange("   ")
        vm.save()
        advanceUntilIdle()

        assertTrue(repository.saved.isEmpty())
        assertTrue(vm.editing().showErrors)
        assertNotNull(vm.editing().form.displayNameError)
    }

    @Test
    fun `saving sends every field and marks the form clean, trimmed as stored`() = runTest {
        val repository = FakeProfiles()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.onDisplayNameChange("  Owais S.  ")
        vm.onBioChange("Loves poetry ")
        vm.onEmailDigestChange(false)
        vm.save()
        advanceUntilIdle()

        assertEquals(
            ProfileUpdate(displayName = "  Owais S.  ", areaName = "Rajbagh", bio = "Loves poetry ", emailDigest = false, location = null),
            repository.saved.single(),
        )
        assertEquals("Owais S.", vm.editing().form.displayName)
        assertFalse(vm.editing().hasChanges)
    }

    @Test
    fun `a failed save keeps the edits`() = runTest {
        val vm = viewModel(FakeProfiles(failSave = true))
        advanceUntilIdle()

        vm.onBioChange("Something")
        vm.save()
        advanceUntilIdle()

        assertEquals("Something", vm.editing().form.bio)
        assertTrue(vm.editing().hasChanges)
        assertEquals("No connection. Check your network and try again.", vm.messages.first())
    }

    @Test
    fun `sharing a location puts it in the form, to be stored on save`() = runTest {
        val repository = FakeProfiles()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.shareLocation()
        advanceUntilIdle()

        assertEquals(SharedLocation(34.08, 74.8), vm.editing().form.location)
        assertTrue(repository.saved.isEmpty())
        assertTrue(vm.editing().hasChanges)
    }

    @Test
    fun `a refused permission says how to allow it and changes nothing`() = runTest {
        val vm = viewModel(locate = { LocateOutcome.PermissionDenied })
        advanceUntilIdle()

        vm.shareLocation()
        advanceUntilIdle()

        assertNull(vm.editing().form.location)
        assertFalse(vm.editing().isLocating)
        assertEquals("Location access is off for this app. You can allow it in Settings.", vm.messages.first())
    }

    @Test
    fun `removing a location saves it as removed`() = runTest {
        val repository = FakeProfiles(location = SharedLocation(34.0, 74.0))
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.removeLocation()
        vm.save()
        advanceUntilIdle()

        assertNull(repository.saved.single().location)
    }

    @Test
    fun `an active suspension is shown with its reason and end`() = runTest {
        val vm = viewModel(
            FakeProfiles(
                profile = OwnProfile(id = "me", displayName = "A", isBanned = true, banReason = "Spam", banExpiresAt = "2026-09-20T00:00:00+00:00"),
            ),
        )
        advanceUntilIdle()

        val suspension = vm.editing().suspension!!
        assertEquals("Spam", suspension.reason)
        assertEquals(Instant.parse("2026-09-20T00:00:00Z"), suspension.until)
    }

    // Same rule as the website's proxy: once ban_expires_at has passed, the flag no longer applies.
    @Test
    fun `an expired suspension is not shown`() = runTest {
        val vm = viewModel(
            FakeProfiles(profile = OwnProfile(id = "me", displayName = "A", isBanned = true, banExpiresAt = "2026-09-01T00:00:00+00:00")),
        )
        advanceUntilIdle()

        assertNull(vm.editing().suspension)
    }

    @Test
    fun `a permanent suspension has no end date`() = runTest {
        val vm = viewModel(FakeProfiles(profile = OwnProfile(id = "me", displayName = "A", isBanned = true)))
        advanceUntilIdle()

        assertNull(vm.editing().suspension!!.until)
    }

    @Test
    fun `sign out goes through the auth repository`() = runTest {
        val auth = FakeAuth()
        val vm = viewModel(auth = auth)
        advanceUntilIdle()

        vm.signOut()
        advanceUntilIdle()

        assertTrue(auth.signedOut)
    }

    @Test
    fun `area suggestions match anywhere, ignore case, and leave out the exact value`() {
        val areas = listOf("Rajbagh, Srinagar", "Anantnag Town, Anantnag", "Bijbehara, Anantnag")

        assertEquals(listOf("Anantnag Town, Anantnag", "Bijbehara, Anantnag"), matchingAreas("anant", areas))
        assertEquals(emptyList<String>(), matchingAreas("  ", areas))
        assertEquals(emptyList<String>(), matchingAreas("Rajbagh, Srinagar", areas))
        assertEquals(1, matchingAreas("a", areas, limit = 1).size)
    }

    // ── Deleting the account ──

    @Test
    fun `offers deletion only once the answer is back, and says what is in the way`() = runTest {
        val deletion = FakeDeletion(blockers = listOf(DeletionBlocker.OWNS_CLUB))
        val vm = viewModel(deletion = deletion)
        advanceUntilIdle()
        assertEquals(null, vm.editing().deletionBlockers)

        vm.loadDeletionBlockers()
        advanceUntilIdle()
        assertEquals(listOf(DeletionBlocker.OWNS_CLUB), vm.editing().deletionBlockers)
    }

    @Test
    fun `deletes the account and signs out`() = runTest {
        val auth = FakeAuth()
        val deletion = FakeDeletion()
        val vm = viewModel(auth = auth, deletion = deletion)
        advanceUntilIdle()

        vm.deleteAccount()
        advanceUntilIdle()

        assertEquals(1, deletion.deleteCalls)
        assertTrue(auth.signedOut)
    }

    @Test
    fun `a refusal leaves the member signed in and shows what the website said`() = runTest {
        val auth = FakeAuth()
        val deletion = FakeDeletion(outcome = DeleteAccountOutcome.Refused("Too many attempts."))
        val vm = viewModel(auth = auth, deletion = deletion)
        advanceUntilIdle()

        vm.messages.test {
            vm.deleteAccount()
            advanceUntilIdle()
            assertEquals("Too many attempts.", awaitItem())
        }
        assertFalse(auth.signedOut)
        assertFalse(vm.editing().isDeleting)
    }

    @Test
    fun `a late blocker from the website is shown instead of deleting`() = runTest {
        val auth = FakeAuth()
        val deletion = FakeDeletion(
            outcome = DeleteAccountOutcome.Blocked(listOf(DeletionBlocker.UNFINISHED_EXCHANGE)),
        )
        val vm = viewModel(auth = auth, deletion = deletion)
        advanceUntilIdle()

        vm.deleteAccount()
        advanceUntilIdle()

        assertEquals(listOf(DeletionBlocker.UNFINISHED_EXCHANGE), vm.editing().deletionBlockers)
        assertFalse(auth.signedOut)
    }
}
