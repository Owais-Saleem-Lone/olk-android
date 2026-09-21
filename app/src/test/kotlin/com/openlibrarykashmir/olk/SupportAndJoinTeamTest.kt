package com.openlibrarykashmir.olk

import com.openlibrarykashmir.olk.core.data.repository.OwnProfile
import com.openlibrarykashmir.olk.core.data.repository.ProfileRepository
import com.openlibrarykashmir.olk.core.data.repository.ProfileUpdate
import com.openlibrarykashmir.olk.core.data.repository.SharedLocation
import com.openlibrarykashmir.olk.core.data.repository.SupportMessage
import com.openlibrarykashmir.olk.core.data.repository.SupportRepository
import com.openlibrarykashmir.olk.core.data.repository.SupportSendOutcome
import com.openlibrarykashmir.olk.core.data.repository.TeamApplication
import com.openlibrarykashmir.olk.core.data.repository.TeamApplicationOutcome
import com.openlibrarykashmir.olk.core.data.repository.TeamApplicationRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.support.SupportUiState
import com.openlibrarykashmir.olk.feature.support.SupportViewModel
import com.openlibrarykashmir.olk.feature.team.JoinTeamUiState
import com.openlibrarykashmir.olk.feature.team.JoinTeamViewModel
import com.openlibrarykashmir.olk.feature.team.PickedCv
import com.openlibrarykashmir.olk.feature.team.cvProblem
import com.openlibrarykashmir.olk.feature.team.formProblem
import com.openlibrarykashmir.olk.feature.team.uploadMimeType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SupportAndJoinTeamTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuth(private val userId: String?, email: String? = "me@example.com") : AuthRepository {
        override val authState: Flow<AuthState> =
            flowOf(if (userId != null) AuthState.SignedIn(userId, email) else AuthState.SignedOut)
        override fun currentUserId() = userId
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    private fun message(id: String, from: String, admin: Boolean = false, at: String = "2026-09-21T10:00:00+00:00") =
        SupportMessage(id, "conv", from, admin, "text $id", at)

    private class FakeSupport(
        var history: List<SupportMessage> = emptyList(),
        var adminTeam: Boolean = false,
        var outcome: ((String) -> SupportSendOutcome)? = null,
    ) : SupportRepository {
        val live = MutableSharedFlow<SupportMessage>()
        var conversationsAsked = 0
        val sent = mutableListOf<Pair<String, Boolean>>()

        override suspend fun conversationId(userId: String): String {
            conversationsAsked++
            return "conv"
        }
        override suspend fun isAdminTeam() = adminTeam
        override suspend fun history(conversationId: String) = history
        override suspend fun send(conversationId: String, senderId: String, senderIsAdmin: Boolean, content: String): SupportSendOutcome {
            sent += content to senderIsAdmin
            return outcome?.invoke(content)
                ?: SupportSendOutcome.Sent(SupportMessage("new-${sent.size}", conversationId, senderId, senderIsAdmin, content, "2026-09-21T11:00:00+00:00"))
        }
        override fun newMessages(conversationId: String): Flow<SupportMessage> = live
    }

    // ── Contact Admin ──

    @Test
    fun `opening the page finds the thread and shows its history, oldest first`() = runTest {
        val support = FakeSupport(history = listOf(message("a", "me"), message("b", "staff", admin = true, at = "2026-09-21T10:05:00+00:00")))
        val viewModel = SupportViewModel(support, FakeAuth("me"))
        viewModel.start()
        advanceUntilIdle()

        val state = viewModel.uiState.value as SupportUiState.Ready
        assertEquals(listOf("a", "b"), state.messages.map { it.id })
        assertTrue(state.messages[1].senderIsAdmin)
        assertFalse(state.viewerIsAdminTeam)
    }

    @Test
    fun `an admin reply arriving live appears once, even if history also has it`() = runTest {
        val reply = message("r", "staff", admin = true, at = "2026-09-21T10:10:00+00:00")
        val support = FakeSupport(history = listOf(message("a", "me")))
        val viewModel = SupportViewModel(support, FakeAuth("me"))
        viewModel.start()
        advanceUntilIdle()

        support.live.emit(reply)
        advanceUntilIdle()
        // Coming back from the background reloads history, which now has it too.
        support.history = listOf(message("a", "me"), reply)
        viewModel.start()
        advanceUntilIdle()

        val state = viewModel.uiState.value as SupportUiState.Ready
        assertEquals(listOf("a", "r"), state.messages.map { it.id })
        // The thread is looked up once, then kept.
        assertEquals(1, support.conversationsAsked)
    }

    @Test
    fun `a member's message goes out without the admin badge, staff's with it`() = runTest {
        val member = FakeSupport()
        SupportViewModel(member, FakeAuth("me")).run {
            start(); advanceUntilIdle()
            onDraftChange("  I need help  "); send(); advanceUntilIdle()
        }
        assertEquals(listOf("I need help" to false), member.sent)

        val staff = FakeSupport(adminTeam = true)
        SupportViewModel(staff, FakeAuth("mod")).run {
            start(); advanceUntilIdle()
            onDraftChange("Looking into it"); send(); advanceUntilIdle()
        }
        assertEquals(listOf("Looking into it" to true), staff.sent)
    }

    @Test
    fun `hitting the hourly limit keeps what was typed`() = runTest {
        val support = FakeSupport(outcome = { SupportSendOutcome.RateLimited })
        val viewModel = SupportViewModel(support, FakeAuth("me"))
        viewModel.start()
        advanceUntilIdle()

        viewModel.onDraftChange("one more")
        viewModel.send()
        advanceUntilIdle()

        val state = viewModel.uiState.value as SupportUiState.Ready
        assertEquals("one more", state.draft)
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun `a draft cannot grow past the database's limit`() = runTest {
        val viewModel = SupportViewModel(FakeSupport(), FakeAuth("me"))
        viewModel.start()
        advanceUntilIdle()

        viewModel.onDraftChange("x".repeat(SupportRepository.MAX_LENGTH + 50))

        assertEquals(SupportRepository.MAX_LENGTH, (viewModel.uiState.value as SupportUiState.Ready).draft.length)
    }

    // ── Join the team: the form's checks ──

    private val complete = JoinTeamUiState(
        fullName = "Aisha Khan", age = "22", email = "aisha@example.com", profession = "Student",
        studies = "BSc CS", field = "Information Technology", motivation = "I love books", contribution = "Code",
    )

    @Test
    fun `a complete form has no problem`() {
        assertNull(formProblem(complete))
    }

    @Test
    fun `each check uses the website's wording`() {
        assertEquals("Please enter a valid age.", formProblem(complete.copy(age = "12")))
        assertEquals("Please enter a valid age.", formProblem(complete.copy(age = "")))
        assertEquals("Please enter a valid email address.", formProblem(complete.copy(email = "not-an-email")))
        assertEquals("Please select a field.", formProblem(complete.copy(field = "")))
        assertEquals(
            "Your \"why join\" answer is over the 250-word limit.",
            formProblem(complete.copy(motivation = "word ".repeat(251))),
        )
        assertEquals(
            "Your \"how you'd contribute\" answer is over the 300-word limit.",
            formProblem(complete.copy(contribution = "word ".repeat(301))),
        )
    }

    @Test
    fun `a CV must be a PDF or Word file of at most 4 MB`() {
        assertNull(cvProblem(PickedCv("cv.pdf", "application/pdf", ByteArray(10))))
        assertNull(cvProblem(PickedCv("CV.DOCX", "application/octet-stream", ByteArray(10))))
        assertEquals(
            "CV must be a PDF or Word document (.pdf, .doc, .docx).",
            cvProblem(PickedCv("photo.jpg", "image/jpeg", ByteArray(10))),
        )
        assertEquals(
            "CV must be smaller than 4MB.",
            cvProblem(PickedCv("cv.pdf", "application/pdf", ByteArray(4 * 1024 * 1024 + 1))),
        )
    }

    @Test
    fun `a vague file type is replaced by the one its name implies`() {
        // The website refuses a stated type it does not list.
        assertEquals("application/pdf", uploadMimeType("cv.pdf", "application/octet-stream"))
        assertEquals("application/msword", uploadMimeType("cv.doc", "application/octet-stream"))
        assertEquals("application/pdf", uploadMimeType("cv.pdf", "application/pdf"))
    }

    // ── Join the team: sending ──

    private class FakeApplications(var outcome: TeamApplicationOutcome = TeamApplicationOutcome.Submitted) : TeamApplicationRepository {
        val submitted = mutableListOf<TeamApplication>()
        override suspend fun submit(application: TeamApplication): TeamApplicationOutcome {
            submitted += application
            return outcome
        }
    }

    private class FakeProfiles : ProfileRepository {
        override suspend fun myProfile() = OwnProfile(id = "me", displayName = "Aisha Khan")
        override suspend fun myLocation(userId: String): SharedLocation? = null
        override suspend fun areaSuggestions() = emptyList<String>()
        override suspend fun save(userId: String, update: ProfileUpdate) = Unit
    }

    private fun joinTeam(applications: FakeApplications, cv: PickedCv? = PickedCv("cv.pdf", "application/pdf", ByteArray(10))) =
        JoinTeamViewModel(applications, FakeProfiles(), FakeAuth("me", email = "aisha@example.com")) { cv }

    @Test
    fun `the form starts with the account's name and email`() = runTest {
        val viewModel = joinTeam(FakeApplications())
        advanceUntilIdle()

        assertEquals("Aisha Khan", viewModel.uiState.value.fullName)
        assertEquals("aisha@example.com", viewModel.uiState.value.email)
    }

    @Test
    fun `nothing is sent without a CV`() = runTest {
        val applications = FakeApplications()
        val viewModel = joinTeam(applications)
        advanceUntilIdle()
        viewModel.update { complete }

        viewModel.submit()
        advanceUntilIdle()

        assertEquals("Please attach your CV.", viewModel.uiState.value.error)
        assertTrue(applications.submitted.isEmpty())
    }

    @Test
    fun `a complete application is sent and thanked for`() = runTest {
        val applications = FakeApplications()
        val viewModel = joinTeam(applications)
        advanceUntilIdle()
        viewModel.update { complete }
        viewModel.onCvPicked("content://cv")
        advanceUntilIdle()

        viewModel.submit()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.submitted)
        assertEquals(22, applications.submitted.single().age)
        assertEquals("cv.pdf", applications.submitted.single().cvFileName)
    }

    @Test
    fun `the website's refusal is shown as it said it`() = runTest {
        val applications = FakeApplications(TeamApplicationOutcome.Refused("You have already applied recently."))
        val viewModel = joinTeam(applications)
        advanceUntilIdle()
        viewModel.update { complete }
        viewModel.onCvPicked("content://cv")
        advanceUntilIdle()

        viewModel.submit()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.submitted)
        assertEquals("You have already applied recently.", viewModel.uiState.value.error)
    }

    @Test
    fun `a wrong file is refused on picking, before any upload`() = runTest {
        val viewModel = joinTeam(FakeApplications(), cv = PickedCv("photo.jpg", "image/jpeg", ByteArray(10)))
        advanceUntilIdle()

        viewModel.onCvPicked("content://photo")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.cvName)
        assertEquals("CV must be a PDF or Word document (.pdf, .doc, .docx).", viewModel.uiState.value.cvError)
    }
}
