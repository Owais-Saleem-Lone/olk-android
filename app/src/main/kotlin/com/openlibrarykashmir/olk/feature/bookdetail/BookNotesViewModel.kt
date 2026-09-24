package com.openlibrarykashmir.olk.feature.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.BookNote
import com.openlibrarykashmir.olk.core.data.model.BookNoteRules
import com.openlibrarykashmir.olk.core.data.model.NoteOutcome
import com.openlibrarykashmir.olk.core.data.repository.BookNotesRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookNotesUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val notes: List<BookNote> = emptyList(),
    val viewerId: String? = null,
    val canWrite: Boolean = false,
    val isAdmin: Boolean = false,
    val isEditorOpen: Boolean = false,
    val isSaving: Boolean = false,
) {
    val myNote: BookNote?
        get() = notes.firstOrNull { it.userId == viewerId }

    private val othersCount: Int
        get() = notes.count { it.userId != viewerId }

    /** Adding needs the right AND room; editing an existing note needs only the right. */
    val canOpenEditor: Boolean
        get() = canWrite && (myNote != null || othersCount < BookNoteRules.MAX_NOTES)

    val isBookFull: Boolean
        get() = canWrite && myNote == null && othersCount >= BookNoteRules.MAX_NOTES

    /** A moderator may remove anyone's note; everyone may remove their own. */
    fun canDelete(note: BookNote): Boolean = note.userId == viewerId || isAdmin
}

class BookNotesViewModel(
    private val bookId: String,
    private val repository: BookNotesRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookNotesUiState(viewerId = auth.currentUserId()))
    val uiState: StateFlow<BookNotesUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }
            runCatching { repository.load(bookId) }
                .onSuccess { notes ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            notes = notes.notes,
                            canWrite = notes.canWrite,
                            isAdmin = notes.isAdmin,
                        )
                    }
                }
                .onFailure { _uiState.update { it.copy(isLoading = false, loadFailed = true) } }
        }
    }

    fun openEditor() = _uiState.update { if (it.canOpenEditor) it.copy(isEditorOpen = true) else it }

    fun dismissEditor() = _uiState.update { if (it.isSaving) it else it.copy(isEditorOpen = false) }

    fun save(text: String) {
        val state = _uiState.value
        val viewerId = state.viewerId ?: return
        if (state.isSaving || !state.canOpenEditor) return
        // The dialog already refuses these; checked again so nothing is sent
        // that the database would only refuse.
        if (text.isBlank() || BookNoteRules.wordCount(text) > BookNoteRules.WORD_LIMIT ||
            text.length > BookNoteRules.CHAR_LIMIT
        ) {
            return
        }
        val existing = state.myNote

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val outcome = runCatching { repository.save(bookId, viewerId, existing?.id, text) }
            _uiState.update { it.copy(isSaving = false) }
            outcome
                .onSuccess {
                    when (it) {
                        NoteOutcome.Saved -> {
                            _uiState.update { s -> s.copy(isEditorOpen = false) }
                            _messages.send(if (existing == null) "Note added!" else "Note updated!")
                            load()
                        }
                        NoteOutcome.BookFull -> {
                            _uiState.update { s -> s.copy(isEditorOpen = false) }
                            _messages.send("This book already has 10 community notes.")
                            load()
                        }
                        NoteOutcome.TooLong -> _messages.send(
                            "Notes can be at most ${BookNoteRules.WORD_LIMIT} words and " +
                                "${BookNoteRules.CHAR_LIMIT} characters.",
                        )
                        NoteOutcome.NotAllowed -> {
                            _uiState.update { s -> s.copy(isEditorOpen = false) }
                            _messages.send("You can only add a note to a book you have owned or borrowed.")
                            load()
                        }
                        NoteOutcome.Suspended -> {
                            _uiState.update { s -> s.copy(isEditorOpen = false) }
                            _messages.send("Your account is suspended, so you can't write notes until it ends.")
                        }
                    }
                }
                .onFailure { _messages.send(it.toUserMessage()) }
        }
    }

    fun delete(note: BookNote) {
        if (!_uiState.value.canDelete(note)) return
        viewModelScope.launch {
            runCatching { repository.delete(note.id) }
                .onSuccess { deleted ->
                    if (deleted) {
                        _uiState.update { it.copy(isEditorOpen = false) }
                        _messages.send("Note deleted.")
                    } else {
                        _messages.send("The note could not be deleted. Please try again.")
                    }
                    load()
                }
                .onFailure { _messages.send(it.toUserMessage()) }
        }
    }
}

private fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Something went wrong. Please try again."
    }
}
