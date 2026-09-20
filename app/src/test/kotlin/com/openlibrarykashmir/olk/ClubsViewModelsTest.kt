package com.openlibrarykashmir.olk

import com.openlibrarykashmir.olk.core.data.model.BrowseClub
import com.openlibrarykashmir.olk.core.data.model.Club
import com.openlibrarykashmir.olk.core.data.model.ClubMember
import com.openlibrarykashmir.olk.core.data.model.ClubPost
import com.openlibrarykashmir.olk.core.data.model.ClubRating
import com.openlibrarykashmir.olk.core.data.model.MembershipStatus
import com.openlibrarykashmir.olk.core.data.repository.ClubsRepository
import com.openlibrarykashmir.olk.core.data.repository.PostOutcome
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.clubs.ClubDetailViewModel
import com.openlibrarykashmir.olk.feature.clubs.ClubsViewModel
import com.openlibrarykashmir.olk.feature.clubs.clubSummaryLine
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

@OptIn(ExperimentalCoroutinesApi::class)
class ClubsViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuth(private val userId: String?) : AuthRepository {
        override val authState: Flow<AuthState> = emptyFlow()
        override fun currentUserId() = userId
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    private class FakeClubs(
        var page: List<BrowseClub> = emptyList(),
        var detail: Club? = null,
        var membership: MembershipStatus = MembershipStatus.NONE,
        var roster: List<ClubMember> = emptyList(),
        var applicants: List<ClubMember> = emptyList(),
        var chat: List<ClubPost> = emptyList(),
        var rating: ClubRating? = null,
        var postOutcome: PostOutcome = PostOutcome.Sent,
        var memberships: Map<String, MembershipStatus> = emptyMap(),
        var failJoin: Throwable? = null,
    ) : ClubsRepository {
        val joined = mutableListOf<String>()
        val left = mutableListOf<String>()
        val approved = mutableListOf<String>()
        var lastPost: String? = null
        var lastRating: Pair<Int, String?>? = null
        var membersAsked = 0
        var chatAsked = 0

        override suspend fun browse(query: String, interest: String?, limit: Int, offset: Int) =
            if (offset > 0) emptyList() else page

        override suspend fun club(id: String) = detail
        override suspend fun members(clubId: String): List<ClubMember> {
            membersAsked++
            return roster
        }
        override suspend fun pendingApplicants(clubId: String) = applicants
        override suspend fun membership(clubId: String, userId: String) = membership
        override suspend fun myMemberships(userId: String) = memberships
        override suspend fun requestToJoin(clubId: String, userId: String) {
            failJoin?.let { throw it }
            joined += clubId
        }
        override suspend fun leave(clubId: String, userId: String) {
            left += clubId
            membership = MembershipStatus.NONE
        }
        override suspend fun approve(clubId: String, userId: String) {
            approved += userId
            applicants = applicants.filterNot { it.userId == userId }
        }
        override suspend fun reject(clubId: String, userId: String) = leave(clubId, userId)
        override suspend fun posts(clubId: String, limit: Int): List<ClubPost> {
            chatAsked++
            return chat
        }
        override suspend fun sendPost(clubId: String, authorId: String, content: String): PostOutcome {
            lastPost = content
            return postOutcome
        }
        override suspend fun myRating(clubId: String, userId: String) = rating
        override suspend fun rate(clubId: String, userId: String, score: Int, comment: String?) {
            lastRating = score to comment
        }
    }

    private fun club(id: String, name: String = "Club $id") = BrowseClub(
        id = id,
        name = name,
        creatorId = "owner-$id",
        memberCount = 3,
    )

    private fun detail(id: String, creator: String) = Club(
        id = id,
        name = "Club $id",
        creatorId = creator,
        memberCount = 3,
    )

    // ── The clubs list ──

    @Test
    fun `the list loads and labels each card with where the user stands`() = runTest {
        val clubs = FakeClubs(
            page = listOf(club("a"), club("b"), club("c")),
            memberships = mapOf("a" to MembershipStatus.APPROVED, "b" to MembershipStatus.PENDING),
        )
        val viewModel = ClubsViewModel(clubs, FakeAuth("me"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("a", "b", "c"), state.clubs.map { it.id })
        assertEquals(MembershipStatus.APPROVED, state.memberships["a"])
        assertEquals(MembershipStatus.PENDING, state.memberships["b"])
        // Never asked about: the card offers "Request to join".
        assertNull(state.memberships["c"])
        assertFalse(state.isLoading)
    }

    @Test
    fun `signed out, the list still loads and asks about no memberships`() = runTest {
        val clubs = FakeClubs(page = listOf(club("a")))
        val viewModel = ClubsViewModel(clubs, FakeAuth(null))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.clubs.size)
        assertTrue(viewModel.uiState.value.memberships.isEmpty())
    }

    @Test
    fun `asking to join marks the card pending without waiting for a reload`() = runTest {
        val clubs = FakeClubs(page = listOf(club("a")))
        val viewModel = ClubsViewModel(clubs, FakeAuth("me"))
        advanceUntilIdle()

        viewModel.requestToJoin("a")
        advanceUntilIdle()

        assertEquals(listOf("a"), clubs.joined)
        assertEquals(MembershipStatus.PENDING, viewModel.uiState.value.memberships["a"])
        assertNull(viewModel.uiState.value.joining)
    }

    @Test
    fun `asking twice is not an error -- the row is already there`() = runTest {
        val clubs = FakeClubs(
            page = listOf(club("a")),
            failJoin = RuntimeException("duplicate key value violates unique constraint (23505)"),
        )
        val viewModel = ClubsViewModel(clubs, FakeAuth("me"))
        advanceUntilIdle()

        viewModel.requestToJoin("a")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(MembershipStatus.PENDING, state.memberships["a"])
        assertEquals("You have already asked to join this club.", state.message)
    }

    @Test
    fun `a failed load offers a retry rather than an empty list`() = runTest {
        val clubs = object : ClubsRepository by FakeClubs() {
            override suspend fun browse(query: String, interest: String?, limit: Int, offset: Int): List<BrowseClub> =
                throw RuntimeException("Unable to resolve host")
        }
        val viewModel = ClubsViewModel(clubs, FakeAuth("me"))
        advanceUntilIdle()

        assertEquals(
            "No connection. Check your network and try again.",
            viewModel.uiState.value.error,
        )
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `the card summary leaves out what the database did not return`() {
        assertEquals("3 members", clubSummaryLine(club("a")))
        assertEquals(
            "1 member · ★ 4.5 · < 1 km · Anantnag",
            clubSummaryLine(
                club("a").copy(memberCount = 1, ratingAvg = 4.5, distanceKm = 0.4, areaName = "Anantnag"),
            ),
        )
    }

    // ── A single club ──

    @Test
    fun `a non-member gets no roster and no chat`() = runTest {
        val clubs = FakeClubs(
            detail = detail("a", creator = "someone-else"),
            membership = MembershipStatus.NONE,
            roster = listOf(ClubMember("m1", "Aamir", null, null)),
            chat = listOf(ClubPost("p1", "m1", "Aamir", "hello", null)),
        )
        val viewModel = ClubDetailViewModel("a", clubs, FakeAuth("me"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.canSeeChat)
        assertTrue(state.posts.isEmpty())
        assertTrue(state.members.isEmpty())
        // Not even asked for: the database would refuse anyway.
        assertEquals(0, clubs.membersAsked)
        assertEquals(0, clubs.chatAsked)
    }

    @Test
    fun `a member sees the roster, the chat and their own rating`() = runTest {
        val clubs = FakeClubs(
            detail = detail("a", creator = "someone-else"),
            membership = MembershipStatus.APPROVED,
            roster = listOf(ClubMember("m1", "Aamir", "Sopore", null)),
            chat = listOf(ClubPost("p1", "m1", "Aamir", "hello", null)),
            rating = ClubRating(score = 4, comment = "good club"),
        )
        val viewModel = ClubDetailViewModel("a", clubs, FakeAuth("me"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.canSeeChat)
        assertEquals(listOf("hello"), state.posts.map { it.content })
        assertEquals(listOf("Aamir"), state.members.map { it.displayName })
        assertEquals(4, state.myScore)
        assertEquals("good club", state.myComment)
        assertFalse(state.isOwner)
    }

    @Test
    fun `the owner sees the chat and the people waiting to be let in`() = runTest {
        val clubs = FakeClubs(
            detail = detail("a", creator = "me"),
            // The owner has an approved row in practice, but the screen must not
            // depend on it: owning the club is enough.
            membership = MembershipStatus.NONE,
            applicants = listOf(ClubMember("u2", "Nusrat", null, null)),
        )
        val viewModel = ClubDetailViewModel("a", clubs, FakeAuth("me"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isOwner)
        assertTrue(state.canSeeChat)
        assertEquals(listOf("Nusrat"), state.applicants.map { it.displayName })
    }

    @Test
    fun `approving an applicant sends only the approval, and the trigger does the rest`() = runTest {
        val clubs = FakeClubs(
            detail = detail("a", creator = "me"),
            applicants = listOf(ClubMember("u2", "Nusrat", null, null)),
        )
        val viewModel = ClubDetailViewModel("a", clubs, FakeAuth("me"))
        advanceUntilIdle()

        viewModel.approve("u2")
        advanceUntilIdle()

        assertEquals(listOf("u2"), clubs.approved)
        assertTrue(viewModel.uiState.value.applicants.isEmpty())
    }

    @Test
    fun `sending a message clears the box and reloads the chat`() = runTest {
        val clubs = FakeClubs(detail = detail("a", creator = "me"))
        val viewModel = ClubDetailViewModel("a", clubs, FakeAuth("me"))
        advanceUntilIdle()

        viewModel.onDraftChange("  hello club  ")
        viewModel.sendPost()
        advanceUntilIdle()

        assertEquals("hello club", clubs.lastPost)
        assertEquals("", viewModel.uiState.value.draft)
        assertFalse(viewModel.uiState.value.isSending)
    }

    @Test
    fun `hitting the hourly limit says so and keeps what was typed`() = runTest {
        val clubs = FakeClubs(
            detail = detail("a", creator = "me"),
            postOutcome = PostOutcome.RateLimited,
        )
        val viewModel = ClubDetailViewModel("a", clubs, FakeAuth("me"))
        advanceUntilIdle()

        viewModel.onDraftChange("one too many")
        viewModel.sendPost()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("one too many", state.draft)
        assertTrue(state.message.orEmpty().contains("hourly message limit"))
    }

    @Test
    fun `rating sends the comment with the stars and remembers the score`() = runTest {
        val clubs = FakeClubs(detail = detail("a", creator = "me"))
        val viewModel = ClubDetailViewModel("a", clubs, FakeAuth("me"))
        advanceUntilIdle()

        viewModel.onCommentChange("lovely people")
        viewModel.rate(5)
        advanceUntilIdle()

        assertEquals(5 to "lovely people", clubs.lastRating)
        assertEquals(5, viewModel.uiState.value.myScore)
    }

    @Test
    fun `a rating comment cannot exceed what the database accepts`() = runTest {
        val clubs = FakeClubs(detail = detail("a", creator = "me"))
        val viewModel = ClubDetailViewModel("a", clubs, FakeAuth("me"))
        advanceUntilIdle()

        viewModel.onCommentChange("x".repeat(ClubsRepository.MAX_COMMENT_LENGTH + 50))

        assertEquals(ClubsRepository.MAX_COMMENT_LENGTH, viewModel.uiState.value.myComment.length)
    }

    @Test
    fun `leaving drops the chat straight away`() = runTest {
        val clubs = FakeClubs(
            detail = detail("a", creator = "someone-else"),
            membership = MembershipStatus.APPROVED,
            chat = listOf(ClubPost("p1", "m1", "Aamir", "hello", null)),
        )
        val viewModel = ClubDetailViewModel("a", clubs, FakeAuth("me"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canSeeChat)

        viewModel.leave()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("a"), clubs.left)
        assertEquals(MembershipStatus.NONE, state.membership)
        assertFalse(state.canSeeChat)
        assertTrue(state.posts.isEmpty())
    }

    @Test
    fun `a club that is gone says so instead of showing an empty page`() = runTest {
        val viewModel = ClubDetailViewModel("a", FakeClubs(detail = null), FakeAuth("me"))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.notFound)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a refresh keeps a half-typed message`() = runTest {
        val clubs = FakeClubs(detail = detail("a", creator = "me"))
        val viewModel = ClubDetailViewModel("a", clubs, FakeAuth("me"))
        advanceUntilIdle()

        viewModel.onDraftChange("half written")
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("half written", viewModel.uiState.value.draft)
    }
}
