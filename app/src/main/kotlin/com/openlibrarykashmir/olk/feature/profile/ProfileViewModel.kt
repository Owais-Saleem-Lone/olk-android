package com.openlibrarykashmir.olk.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.ui.Suspension
import com.openlibrarykashmir.olk.ui.activeSuspension
import com.openlibrarykashmir.olk.core.data.repository.AccountDeletionRepository
import com.openlibrarykashmir.olk.core.data.repository.DeleteAccountOutcome
import com.openlibrarykashmir.olk.core.data.repository.DeletionBlocker
import com.openlibrarykashmir.olk.core.data.repository.OwnProfile
import com.openlibrarykashmir.olk.core.data.repository.ProfileRepository
import com.openlibrarykashmir.olk.core.data.repository.ProfileUpdate
import com.openlibrarykashmir.olk.core.data.repository.SharedLocation
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.ui.parseTimestamp
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class ProfileForm(
    val displayName: String = "",
    val areaName: String = "",
    val bio: String = "",
    val emailDigest: Boolean = true,
    val location: SharedLocation? = null,
) {
    val displayNameError: String?
        get() = if (displayName.isBlank()) "Enter the name other readers will see" else null

    val isValid: Boolean get() = displayNameError == null

    fun toUpdate() = ProfileUpdate(
        displayName = displayName,
        areaName = areaName,
        bio = bio,
        emailDigest = emailDigest,
        location = location,
    )

    companion object {
        fun from(profile: OwnProfile, location: SharedLocation?) = ProfileForm(
            displayName = profile.displayName.orEmpty(),
            areaName = profile.areaName.orEmpty(),
            bio = profile.bio.orEmpty(),
            emailDigest = profile.emailDigest ?: true,
            location = location,
        )
    }
}

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class Error(val message: String) : ProfileUiState
    data class Editing(
        val form: ProfileForm,
        val saved: ProfileForm,
        val areaSuggestions: List<String> = emptyList(),
        val suspension: Suspension? = null,
        val isSaving: Boolean = false,
        val isLocating: Boolean = false,
        val showErrors: Boolean = false,
        /** Null until the answer is back; empty means nothing is in the way. */
        val deletionBlockers: List<DeletionBlocker>? = null,
        val isDeleting: Boolean = false,
    ) : ProfileUiState {
        val hasChanges: Boolean get() = form != saved
    }
}

class ProfileViewModel(
    private val repository: ProfileRepository,
    private val deletion: AccountDeletionRepository,
    private val auth: AuthRepository,
    private val locator: Locator,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        val userId = auth.currentUserId() ?: run {
            _uiState.value = ProfileUiState.Error("Your session has ended. Sign in again.")
            return
        }
        _uiState.value = ProfileUiState.Loading
        viewModelScope.launch {
            runCatching {
                val profile = async { repository.myProfile() }
                val location = async { repository.myLocation(userId) }
                // Suggestions are a nicety; the form must not fail without them.
                val areas = async { runCatching { repository.areaSuggestions() }.getOrDefault(emptyList()) }
                Triple(profile.await(), location.await(), areas.await())
            }.onSuccess { (profile, location, areas) ->
                _uiState.value = if (profile == null) {
                    ProfileUiState.Error("Could not find your profile.")
                } else {
                    val form = ProfileForm.from(profile, location)
                    ProfileUiState.Editing(
                        form = form,
                        saved = form,
                        areaSuggestions = areas,
                        suspension = activeSuspension(profile, now()),
                    )
                }
            }.onFailure { _uiState.value = ProfileUiState.Error(it.toUserMessage()) }
        }
    }

    fun onDisplayNameChange(value: String) =
        edit { it.copy(displayName = value.take(ProfileRepository.MAX_DISPLAY_NAME)) }

    fun onAreaChange(value: String) = edit { it.copy(areaName = value.take(ProfileRepository.MAX_AREA)) }

    fun onBioChange(value: String) = edit { it.copy(bio = value.take(ProfileRepository.MAX_BIO)) }

    fun onEmailDigestChange(value: Boolean) = edit { it.copy(emailDigest = value) }

    fun removeLocation() = edit { it.copy(location = null) }

    /**
     * Called once the screen has the permission answer. The location only goes
     * into the form: like every other field it is stored when the user saves.
     */
    fun shareLocation() {
        val state = _uiState.value as? ProfileUiState.Editing ?: return
        if (state.isLocating) return
        viewModelScope.launch {
            updateEditing { it.copy(isLocating = true) }
            val outcome = runCatching { locator.locate() }.getOrDefault(LocateOutcome.Unavailable)
            updateEditing { it.copy(isLocating = false) }
            when (outcome) {
                is LocateOutcome.Found -> edit { it.copy(location = outcome.location) }
                LocateOutcome.PermissionDenied ->
                    _messages.send("Location access is off for this app. You can allow it in Settings.")
                LocateOutcome.LocationOff -> _messages.send("Turn on location on your phone, then try again.")
                LocateOutcome.Unavailable -> _messages.send("Couldn't find your location. Try again in a moment.")
            }
        }
    }

    fun save() {
        val state = _uiState.value as? ProfileUiState.Editing ?: return
        if (state.isSaving) return
        if (!state.form.isValid) {
            updateEditing { it.copy(showErrors = true) }
            return
        }
        val userId = auth.currentUserId() ?: return
        viewModelScope.launch {
            updateEditing { it.copy(isSaving = true) }
            runCatching { repository.save(userId, state.form.toUpdate()) }
                .onSuccess {
                    // What is on screen now is what is stored, trimmed as the database has it.
                    updateEditing { current ->
                        val stored = current.form.copy(
                            displayName = current.form.displayName.trim(),
                            areaName = current.form.areaName.trim(),
                            bio = current.form.bio.trim(),
                        )
                        current.copy(form = stored, saved = stored, isSaving = false, showErrors = false)
                    }
                    _messages.send("Profile saved.")
                }
                .onFailure {
                    updateEditing { it.copy(isSaving = false) }
                    _messages.send(it.toUserMessage())
                }
        }
    }

    fun loadDeletionBlockers() {
        val editing = _uiState.value as? ProfileUiState.Editing ?: return
        if (editing.deletionBlockers != null) return
        viewModelScope.launch {
            val blockers = runCatching { deletion.blockers() }.getOrNull() ?: return@launch
            updateEditing { it.copy(deletionBlockers = blockers) }
        }
    }

    /** Deletion is final: the caller asks only after its own confirmation. */
    fun deleteAccount() {
        val editing = _uiState.value as? ProfileUiState.Editing ?: return
        if (editing.isDeleting) return
        viewModelScope.launch {
            updateEditing { it.copy(isDeleting = true) }
            runCatching { deletion.delete() }
                .onSuccess { outcome ->
                    updateEditing { it.copy(isDeleting = false) }
                    when (outcome) {
                        // Signing out is what takes the app back to the login
                        // screen; the account itself is already gone.
                        DeleteAccountOutcome.Deleted -> {
                            _messages.send("Your account has been deleted.")
                            auth.signOut()
                        }
                        is DeleteAccountOutcome.Blocked ->
                            updateEditing { it.copy(deletionBlockers = outcome.blockers) }
                        is DeleteAccountOutcome.Refused -> _messages.send(outcome.message)
                    }
                }
                .onFailure {
                    updateEditing { it.copy(isDeleting = false) }
                    _messages.send(it.toUserMessage())
                }
        }
    }

    fun signOut() {
        viewModelScope.launch { runCatching { auth.signOut() } }
    }

    private inline fun edit(transform: (ProfileForm) -> ProfileForm) =
        updateEditing { it.copy(form = transform(it.form)) }

    private inline fun updateEditing(transform: (ProfileUiState.Editing) -> ProfileUiState.Editing) =
        _uiState.update { if (it is ProfileUiState.Editing) transform(it) else it }
}

private fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Something went wrong. Please try again."
    }
}
