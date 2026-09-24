package com.openlibrarykashmir.olk.feature.mybooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.repository.BookEdit
import com.openlibrarykashmir.olk.core.data.repository.BookRepository
import com.openlibrarykashmir.olk.core.data.repository.DeleteOutcome
import com.openlibrarykashmir.olk.core.data.repository.MyBooksRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Form fields, kept separate from the loaded [Book] so Cancel costs nothing. */
data class BookForm(
    val title: String,
    val author: String,
    val status: BookStatus,
    val genre: String,
    val lendingDurationMonths: Int,
    val cover: CoverChoice = CoverChoice.Current(null),
) {
    val titleError: String?
        get() = when {
            title.isBlank() -> "Title is required"
            title.trim().length > MAX_TEXT_LENGTH -> "Title is too long"
            else -> null
        }

    val authorError: String?
        get() = if (author.trim().length > MAX_TEXT_LENGTH) "Author is too long" else null

    companion object {
        /** `books.title` and `books.author` are varchar(500). */
        const val MAX_TEXT_LENGTH = 500
        const val DEFAULT_GENRE = "General"
        val LENDING_PERIODS = listOf(1, 2, 3)

        fun from(book: Book) = BookForm(
            title = book.title,
            author = book.author.orEmpty(),
            status = book.status,
            genre = book.genre ?: DEFAULT_GENRE,
            lendingDurationMonths = book.lendingDurationMonths ?: LENDING_PERIODS.first(),
            cover = CoverChoice.Current(book.coverUrl),
        )
    }
}

sealed interface EditBookUiState {
    data object Loading : EditBookUiState
    data object NotFound : EditBookUiState
    data class Error(val message: String) : EditBookUiState
    data class Editing(
        val book: Book,
        val form: BookForm,
        val genres: List<String>,
        val isSaving: Boolean = false,
        val isDeleting: Boolean = false,
        val showErrors: Boolean = false,
    ) : EditBookUiState {
        /** A genre already on the book stays selectable even if an admin deactivated it. */
        val genreOptions: List<String>
            get() = if (form.genre in genres) genres else genres + form.genre

        val isBusy: Boolean get() = isSaving || isDeleting

        /** Books in permanent circulation can only be passed on, never deleted. */
        val canDelete: Boolean get() = !book.acquiredViaDonation
    }
}

/** One-off results the screen reacts to once. */
sealed interface EditBookEvent {
    /** Shown here; the user stays on the form. */
    data class Message(val text: String) : EditBookEvent

    /** Leave the screen; [message] is shown on the list the user lands on. */
    data class Done(val message: String) : EditBookEvent
}

class EditBookViewModel(
    private val bookId: String,
    private val books: BookRepository,
    private val repository: MyBooksRepository,
    private val auth: AuthRepository,
    private val uploader: CoverUploader,
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditBookUiState>(EditBookUiState.Loading)
    val uiState: StateFlow<EditBookUiState> = _uiState.asStateFlow()

    private val _events = Channel<EditBookEvent>(Channel.BUFFERED)
    val events: Flow<EditBookEvent> = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        val viewerId = auth.currentUserId() ?: run {
            _uiState.value = EditBookUiState.Error("Your session has ended. Sign in again.")
            return
        }
        viewModelScope.launch {
            _uiState.value = EditBookUiState.Loading
            runCatching {
                // Genres are a nicety: if they fail, the current genre is still offered.
                val genres = async { runCatching { repository.genres() }.getOrDefault(emptyList()) }
                books.byId(bookId) to genres.await()
            }.onSuccess { (book, genres) ->
                _uiState.value = if (book == null || book.ownerId != viewerId) {
                    EditBookUiState.NotFound
                } else {
                    EditBookUiState.Editing(book = book, form = BookForm.from(book), genres = genres)
                }
            }.onFailure {
                _uiState.value = EditBookUiState.Error(it.toUserMessage())
            }
        }
    }

    fun onFormChange(transform: (BookForm) -> BookForm) =
        updateEditing { it.copy(form = transform(it.form)) }

    fun save() {
        val state = _uiState.value as? EditBookUiState.Editing ?: return
        if (state.isBusy) return
        val form = state.form
        if (form.titleError != null || form.authorError != null) {
            updateEditing { it.copy(showErrors = true) }
            return
        }

        viewModelScope.launch {
            updateEditing { it.copy(isSaving = true) }
            val ownerId = state.book.ownerId
            var coverUrl: String? = state.book.coverUrl
            val result = runCatching {
                coverUrl = form.cover.resolve(ownerId, uploader)
                val edit = BookEdit(
                    title = form.title.trim(),
                    author = form.author.trim().ifEmpty { null },
                    status = form.status,
                    genre = form.genre,
                    lendingDurationMonths = form.lendingDurationMonths.takeIf { state.book.listingType == ListingType.LEND },
                    coverUrl = coverUrl,
                )
                repository.update(bookId, edit)
            }
            // Whichever cover the book no longer points at is removed: the old one
            // once the change is saved, the new upload if it was not.
            if (coverUrl != state.book.coverUrl) {
                repository.removeCover(ownerId, if (result.getOrNull() != null) state.book.coverUrl else coverUrl)
            }
            result
                .onSuccess { saved ->
                    if (saved == null) {
                        _uiState.value = EditBookUiState.NotFound
                    } else {
                        _events.send(EditBookEvent.Done("Changes saved"))
                    }
                }
                .onFailure { _events.send(EditBookEvent.Message(it.toCoverAwareMessage())) }
            updateEditing { it.copy(isSaving = false) }
        }
    }

    fun delete() {
        val state = _uiState.value as? EditBookUiState.Editing ?: return
        if (state.isBusy || !state.canDelete) return

        viewModelScope.launch {
            updateEditing { it.copy(isDeleting = true) }
            runCatching { repository.delete(bookId) }
                .onSuccess { outcome ->
                    when (outcome) {
                        DeleteOutcome.Deleted -> {
                            repository.removeCover(state.book.ownerId, state.book.coverUrl)
                            _events.send(EditBookEvent.Done("Book deleted"))
                        }
                        DeleteOutcome.NotAllowed ->
                            _events.send(EditBookEvent.Message("This book can't be deleted."))
                    }
                }
                .onFailure { _events.send(EditBookEvent.Message(it.toUserMessage())) }
            updateEditing { it.copy(isDeleting = false) }
        }
    }

    private inline fun updateEditing(transform: (EditBookUiState.Editing) -> EditBookUiState.Editing) =
        _uiState.update { if (it is EditBookUiState.Editing) transform(it) else it }
}

private fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Something went wrong. Please try again."
    }
}
