package com.openlibrarykashmir.olk.feature.clubs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.Club
import com.openlibrarykashmir.olk.core.data.model.ClubMember
import com.openlibrarykashmir.olk.core.data.model.ClubPost
import com.openlibrarykashmir.olk.core.data.model.MembershipStatus
import com.openlibrarykashmir.olk.core.data.repository.ClubsRepository
import com.openlibrarykashmir.olk.core.data.repository.PostOutcome
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ClubDetailUiState(
    val club: Club? = null,
    val membership: MembershipStatus = MembershipStatus.NONE,
    val isOwner: Boolean = false,
    val currentUserId: String? = null,
    val members: List<ClubMember> = emptyList(),
    val applicants: List<ClubMember> = emptyList(),
    val posts: List<ClubPost> = emptyList(),
    val draft: String = "",
    val isSending: Boolean = false,
    val myScore: Int = 0,
    val myComment: String = "",
    val isRating: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val notFound: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    /** The owner is a member too, but has an approved row only if the club was made normally. */
    val canSeeChat: Boolean get() = isOwner || membership == MembershipStatus.APPROVED
}

class ClubDetailViewModel(
    private val clubId: String,
    private val clubs: ClubsRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClubDetailUiState())
    val uiState: StateFlow<ClubDetailUiState> = _uiState.asStateFlow()

    init {
        load(initial = true)
    }

    fun refresh() = load(initial = false)

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun onDraftChange(value: String) = _uiState.update { it.copy(draft = value) }

    fun onCommentChange(value: String) = _uiState.update {
        it.copy(myComment = value.take(ClubsRepository.MAX_COMMENT_LENGTH))
    }

    fun requestToJoin() {
        val userId = _uiState.value.currentUserId ?: return
        viewModelScope.launch {
            runCatching { clubs.requestToJoin(clubId, userId) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            membership = MembershipStatus.PENDING,
                            message = "Request sent. The club's owner will decide.",
                        )
                    }
                }
                .onFailure { reportFailure(it, "Could not send the request. Try again.") }
        }
    }

    /** Also used to withdraw a request that is still pending — both just delete the row. */
    fun leave() {
        val userId = _uiState.value.currentUserId ?: return
        viewModelScope.launch {
            runCatching { clubs.leave(clubId, userId) }
                .onSuccess {
                    _uiState.update { it.copy(membership = MembershipStatus.NONE, posts = emptyList()) }
                    load(initial = false)
                }
                .onFailure { reportFailure(it, "Could not leave the club. Try again.") }
        }
    }

    fun approve(userId: String) {
        viewModelScope.launch {
            runCatching { clubs.approve(clubId, userId) }
                .onSuccess {
                    // The new member's notification is written by a database trigger.
                    _uiState.update { it.copy(message = "Member approved.") }
                    load(initial = false)
                }
                .onFailure { reportFailure(it, "Could not approve. Try again.") }
        }
    }

    fun reject(userId: String) {
        viewModelScope.launch {
            runCatching { clubs.reject(clubId, userId) }
                .onSuccess {
                    _uiState.update { it.copy(message = "Request declined.") }
                    load(initial = false)
                }
                .onFailure { reportFailure(it, "Could not decline. Try again.") }
        }
    }

    fun sendPost() {
        val state = _uiState.value
        val userId = state.currentUserId ?: return
        val content = state.draft.trim()
        if (content.isEmpty() || state.isSending) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            runCatching { clubs.sendPost(clubId, userId, content) }
                .onSuccess { outcome ->
                    when (outcome) {
                        PostOutcome.Sent -> {
                            _uiState.update { it.copy(draft = "", isSending = false) }
                            reloadPosts()
                        }
                        PostOutcome.RateLimited -> _uiState.update {
                            it.copy(
                                isSending = false,
                                message = "You've hit the hourly message limit for this club. " +
                                    "Please wait before sending more.",
                            )
                        }
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isSending = false) }
                    reportFailure(it, "Could not send the message. Try again.")
                }
        }
    }

    fun rate(score: Int) {
        val state = _uiState.value
        val userId = state.currentUserId ?: return
        if (state.isRating) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRating = true) }
            runCatching { clubs.rate(clubId, userId, score, state.myComment) }
                .onSuccess {
                    _uiState.update { it.copy(myScore = score, isRating = false, message = "Thanks for rating.") }
                    // rating_avg / rating_count are maintained by a trigger, so the
                    // club row has to be read again to show the new average.
                    reloadClub()
                }
                .onFailure {
                    _uiState.update { it.copy(isRating = false) }
                    reportFailure(it, "Could not save your rating. Try again.")
                }
        }
    }

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = initial && it.club == null, isRefreshing = !initial, error = null)
            }

            val userId = auth.currentUserId()
            runCatching {
                val club = clubs.club(clubId)
                val membership = if (userId == null) {
                    MembershipStatus.NONE
                } else {
                    clubs.membership(clubId, userId)
                }
                val isOwner = club != null && club.creatorId == userId
                val canSeeChat = isOwner || membership == MembershipStatus.APPROVED
                val rating = if (userId != null && canSeeChat) clubs.myRating(clubId, userId) else null

                ClubDetailUiState(
                    club = club,
                    membership = membership,
                    isOwner = isOwner,
                    currentUserId = userId,
                    members = if (canSeeChat) clubs.members(clubId) else emptyList(),
                    // Only ever non-empty for the owner: RLS hides pending rows from
                    // everyone else. Asked for anyway so the owner's review list is
                    // there without a second trip.
                    applicants = if (isOwner) clubs.pendingApplicants(clubId) else emptyList(),
                    posts = if (canSeeChat) clubs.posts(clubId) else emptyList(),
                    myScore = rating?.score ?: 0,
                    myComment = rating?.comment.orEmpty(),
                    isLoading = false,
                    notFound = club == null,
                )
            }.onSuccess { loaded ->
                // The draft is kept: a refresh must not throw away what is half-typed.
                _uiState.update { loaded.copy(draft = it.draft, message = it.message) }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, error = throwable.toClubsMessage())
                }
            }
        }
    }

    private suspend fun reloadPosts() {
        runCatching { clubs.posts(clubId) }
            .onSuccess { posts -> _uiState.update { it.copy(posts = posts) } }
    }

    private suspend fun reloadClub() {
        runCatching { clubs.club(clubId) }
            .onSuccess { club -> _uiState.update { it.copy(club = club ?: it.club) } }
    }

    private fun reportFailure(throwable: Throwable, fallback: String) {
        if (throwable is CancellationException) throw throwable
        _uiState.update { it.copy(message = fallback) }
    }
}
