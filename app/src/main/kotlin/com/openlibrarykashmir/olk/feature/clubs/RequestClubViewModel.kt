package com.openlibrarykashmir.olk.feature.clubs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.CLUB_INTERESTS
import com.openlibrarykashmir.olk.core.data.model.ClubEligibility
import com.openlibrarykashmir.olk.core.data.model.ClubRequestDraft
import com.openlibrarykashmir.olk.core.data.model.ClubRequestOutcome
import com.openlibrarykashmir.olk.core.data.model.ClubRequestStatus
import com.openlibrarykashmir.olk.core.data.model.ClubRequestSummary
import com.openlibrarykashmir.olk.core.data.model.CoverBucket
import com.openlibrarykashmir.olk.core.data.model.OrganiserRules
import com.openlibrarykashmir.olk.core.data.repository.ClubOrganiserRepository
import com.openlibrarykashmir.olk.core.data.repository.ProfileRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.feature.mybooks.CoverChoice
import com.openlibrarykashmir.olk.feature.mybooks.CoverUploader
import com.openlibrarykashmir.olk.feature.mybooks.toCoverAwareMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The website's "Request a Club" form. */
data class ClubRequestForm(
    val name: String = "",
    val interests: Set<String> = emptySet(),
    val description: String = "",
    val goal: String = "",
    val targetMembers: String = "",
    val areaName: String = "",
    /** A photo (uploaded on submit) or, instead, a pasted https link. */
    val cover: CoverChoice = CoverChoice.Current(null),
    val coverLink: String = "",
) {
    val nameError: String?
        get() = when {
            name.isBlank() -> "Give the club a name."
            OrganiserRules.wordCount(name) > OrganiserRules.NAME_WORDS -> "At most ${OrganiserRules.NAME_WORDS} words."
            else -> null
        }

    val interestsError: String?
        get() = if (interests.isEmpty()) "Pick at least one category." else null

    val descriptionError: String?
        get() = when {
            description.isBlank() -> "Say what the club is about."
            OrganiserRules.wordCount(description) > OrganiserRules.DESCRIPTION_WORDS ->
                "At most ${OrganiserRules.DESCRIPTION_WORDS} words."
            else -> null
        }

    val goalError: String? get() = shortTextError(goal)
    val targetMembersError: String? get() = shortTextError(targetMembers)

    val coverLinkError: String?
        get() = if (coverLink.isNotBlank() && !OrganiserRules.isHttpsUrl(coverLink)) {
            "Image links must start with https://"
        } else {
            null
        }

    val isValid: Boolean
        get() = listOf(nameError, interestsError, descriptionError, goalError, targetMembersError, coverLinkError)
            .all { it == null }

    private fun shortTextError(text: String): String? =
        if (OrganiserRules.wordCount(text) > OrganiserRules.SHORT_TEXT_WORDS) {
            "At most ${OrganiserRules.SHORT_TEXT_WORDS} words."
        } else {
            null
        }
}

sealed interface RequestClubUiState {
    data object Loading : RequestClubUiState
    data class Error(val message: String) : RequestClubUiState
    data class NotEligible(val eligibility: ClubEligibility) : RequestClubUiState
    data class UnderReview(val request: ClubRequestSummary, val isWithdrawing: Boolean = false) : RequestClubUiState
    data class Form(
        val form: ClubRequestForm,
        /** A rejected request (with the team's note) or the club an approved one created. */
        val previous: ClubRequestSummary?,
        val areaSuggestions: List<String>,
        val hasSavedLocation: Boolean,
        val isSubmitting: Boolean = false,
        val showErrors: Boolean = false,
    ) : RequestClubUiState
}

class RequestClubViewModel(
    private val organiser: ClubOrganiserRepository,
    private val profiles: ProfileRepository,
    private val auth: AuthRepository,
    private val coverUploader: CoverUploader,
) : ViewModel() {

    private val _uiState = MutableStateFlow<RequestClubUiState>(RequestClubUiState.Loading)
    val uiState: StateFlow<RequestClubUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        val userId = auth.currentUserId() ?: run {
            _uiState.value = RequestClubUiState.Error("Your session has ended. Sign in again.")
            return
        }
        viewModelScope.launch {
            _uiState.value = RequestClubUiState.Loading
            _uiState.value = runCatching { loadFor(userId) }
                .getOrElse { RequestClubUiState.Error(it.toCoverAwareMessage()) }
        }
    }

    private suspend fun loadFor(userId: String): RequestClubUiState = coroutineScope {
        val eligibility = async { organiser.eligibility() }
        val latest = async { organiser.latestRequest(userId) }
        // Only for pre-filling and suggesting; none of these may block the form.
        val profile = async { runCatching { profiles.myProfile() }.getOrNull() }
        val location = async { runCatching { profiles.myLocation(userId) }.getOrNull() }
        val areas = async { runCatching { profiles.areaSuggestions() }.getOrDefault(emptyList()) }

        val request = latest.await()
        when {
            // Checked first, as on the website: a pending request is shown even to
            // someone whose eligibility has since changed.
            request?.status == ClubRequestStatus.PENDING -> RequestClubUiState.UnderReview(request)
            !eligibility.await().eligible -> RequestClubUiState.NotEligible(eligibility.await())
            else -> RequestClubUiState.Form(
                form = ClubRequestForm(areaName = profile.await()?.areaName.orEmpty()),
                previous = request,
                areaSuggestions = areas.await(),
                hasSavedLocation = location.await() != null,
            )
        }
    }

    fun onFormChange(transform: (ClubRequestForm) -> ClubRequestForm) = updateForm { state ->
        state.copy(form = transform(state.form).capped())
    }

    fun toggleInterest(interest: String) = onFormChange {
        if (interest !in CLUB_INTERESTS) return@onFormChange it
        it.copy(interests = if (interest in it.interests) it.interests - interest else it.interests + interest)
    }

    /** A photo and a pasted link replace each other, as on the website. */
    fun onCoverChoice(choice: CoverChoice) = onFormChange { it.copy(cover = choice, coverLink = "") }

    fun onCoverLink(link: String) = onFormChange {
        it.copy(coverLink = link, cover = if (link.isBlank()) it.cover else CoverChoice.Current(null))
    }

    fun submit() {
        val state = _uiState.value as? RequestClubUiState.Form ?: return
        val userId = auth.currentUserId() ?: return
        if (state.isSubmitting) return
        if (!state.form.isValid) {
            updateForm { it.copy(showErrors = true) }
            // The first problem may be scrolled out of sight.
            viewModelScope.launch { _messages.send("Check the highlighted fields.") }
            return
        }
        val form = state.form

        viewModelScope.launch {
            updateForm { it.copy(isSubmitting = true) }
            var uploaded: String? = null
            val outcome = runCatching {
                val coverUrl = when (val cover = form.cover) {
                    is CoverChoice.Picked -> coverUploader.upload(userId, cover.uri).also { uploaded = it }
                    else -> form.coverLink.trim().ifEmpty { null }
                }
                organiser.submitRequest(
                    userId,
                    ClubRequestDraft(
                        name = form.name,
                        interests = CLUB_INTERESTS.filter { it in form.interests },
                        description = form.description,
                        goal = form.goal,
                        targetMembers = form.targetMembers,
                        areaName = form.areaName,
                        coverUrl = coverUrl,
                    ),
                )
            }
            // A refused request must not leave its photo behind in storage.
            if (outcome.getOrNull() != ClubRequestOutcome.Submitted) {
                uploaded?.let { organiser.removeCover(CoverBucket.CLUBS, it) }
            }
            outcome
                .onSuccess {
                    when (it) {
                        ClubRequestOutcome.Submitted -> {
                            _messages.send("Your request has been sent for review.")
                            load()
                        }
                        ClubRequestOutcome.AlreadyPending -> {
                            _messages.send("You already have a club request waiting for review.")
                            load()
                        }
                        ClubRequestOutcome.AlreadyRunsClub -> {
                            updateForm { s -> s.copy(isSubmitting = false) }
                            _messages.send("You already run a club. Each member can run one club.")
                        }
                        is ClubRequestOutcome.NotEligible -> {
                            _messages.send(it.reason)
                            load()
                        }
                        ClubRequestOutcome.Suspended -> {
                            updateForm { s -> s.copy(isSubmitting = false) }
                            _messages.send("Your account is suspended, so you can't request a club until it ends.")
                        }
                        ClubRequestOutcome.Invalid -> {
                            updateForm { s -> s.copy(isSubmitting = false, showErrors = true) }
                            _messages.send("Some of the details weren't accepted. Check the form and try again.")
                        }
                    }
                }
                .onFailure {
                    updateForm { s -> s.copy(isSubmitting = false) }
                    _messages.send(it.toCoverAwareMessage())
                }
        }
    }

    fun withdraw() {
        val state = _uiState.value as? RequestClubUiState.UnderReview ?: return
        if (state.isWithdrawing) return
        viewModelScope.launch {
            _uiState.value = state.copy(isWithdrawing = true)
            runCatching { organiser.withdrawRequest(state.request.id) }
                .onSuccess { withdrawn ->
                    _messages.send(
                        if (withdrawn) "Request withdrawn." else "That request has already been reviewed.",
                    )
                }
                .onFailure { _messages.send(it.toCoverAwareMessage()) }
            load()
        }
    }

    private inline fun updateForm(transform: (RequestClubUiState.Form) -> RequestClubUiState.Form) =
        _uiState.update { if (it is RequestClubUiState.Form) transform(it) else it }
}

/** Typing stops at what the database accepts. */
private fun ClubRequestForm.capped() = copy(
    name = name.take(OrganiserRules.NAME_CHARS),
    description = description.take(OrganiserRules.DESCRIPTION_CHARS),
    goal = goal.take(OrganiserRules.SHORT_TEXT_CHARS),
    targetMembers = targetMembers.take(OrganiserRules.SHORT_TEXT_CHARS),
    areaName = areaName.take(OrganiserRules.AREA_CHARS),
    coverLink = coverLink.take(2048),
)
