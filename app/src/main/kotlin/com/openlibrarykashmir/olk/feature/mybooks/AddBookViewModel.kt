package com.openlibrarykashmir.olk.feature.mybooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.BookCondition
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.repository.AddBookOutcome
import com.openlibrarykashmir.olk.core.data.repository.IsbnLookupRepository
import com.openlibrarykashmir.olk.core.data.repository.MyBooksRepository
import com.openlibrarykashmir.olk.core.data.repository.NewBook
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The web Add Book form's fields and defaults. */
data class NewBookForm(
    val title: String = "",
    val author: String = "",
    val cover: CoverChoice = CoverChoice.Current(null),
    val genre: String = BookForm.DEFAULT_GENRE,
    val publicationYear: String = "",
    val description: String = "",
    val condition: BookCondition = BookCondition.GOOD,
    val listingType: ListingType = ListingType.DONATE,
    val lendingDurationMonths: Int = BookForm.LENDING_PERIODS.first(),
) {
    val titleError: String?
        get() = when {
            title.isBlank() -> "Title is required"
            title.trim().length > BookForm.MAX_TEXT_LENGTH -> "Title is too long"
            else -> null
        }

    val authorError: String?
        get() = if (author.trim().length > BookForm.MAX_TEXT_LENGTH) "Author is too long" else null

    /** `books_publication_year_check`: empty, or 1000–2200. */
    val yearError: String?
        get() = publicationYear.trim().takeIf { it.isNotEmpty() }?.let {
            val year = it.toIntOrNull()
            if (year == null || year !in MIN_YEAR..MAX_YEAR) "Enter a year between $MIN_YEAR and $MAX_YEAR" else null
        }

    val isValid: Boolean get() = titleError == null && authorError == null && yearError == null

    fun toNewBook(coverUrl: String?) = NewBook(
        title = title.trim(),
        author = author.trim().ifEmpty { null },
        condition = condition,
        listingType = listingType,
        genre = genre,
        description = description.trim().ifEmpty { null },
        publicationYear = publicationYear.trim().toIntOrNull(),
        lendingDurationMonths = lendingDurationMonths.takeIf { listingType == ListingType.LEND },
        coverUrl = coverUrl,
    )

    companion object {
        const val MIN_YEAR = 1000
        const val MAX_YEAR = 2200
    }
}

data class AddBookUiState(
    val form: NewBookForm = NewBookForm(),
    val genres: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val isLookingUp: Boolean = false,
    val showErrors: Boolean = false,
) {
    /** A category that is not (or no longer) active still shows if selected. */
    val genreOptions: List<String>
        get() = if (form.genre in genres) genres else genres + form.genre
}

class AddBookViewModel(
    private val repository: MyBooksRepository,
    private val auth: AuthRepository,
    private val uploader: CoverUploader,
    private val isbnLookup: IsbnLookupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddBookUiState())
    val uiState: StateFlow<AddBookUiState> = _uiState.asStateFlow()

    private val _events = Channel<EditBookEvent>(Channel.BUFFERED)
    val events: Flow<EditBookEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            runCatching { repository.genres() }.onSuccess { genres -> _uiState.update { it.copy(genres = genres) } }
        }
    }

    fun onFormChange(transform: (NewBookForm) -> NewBookForm) =
        _uiState.update { it.copy(form = transform(it.form)) }

    /**
     * Fills the form from Open Library. Only empty fields are filled, so a scan
     * after typing never wipes what the user wrote; the cover is only set when
     * they have not chosen a photo.
     */
    fun applyIsbn(isbn: String) {
        if (_uiState.value.isLookingUp) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLookingUp = true) }
            runCatching { isbnLookup.lookup(isbn) }
                .onSuccess { found ->
                    if (found == null) {
                        _events.send(EditBookEvent.Message("No book found for that ISBN. Fill the details in yourself."))
                    } else {
                        _uiState.update { state ->
                            val form = state.form
                            state.copy(
                                form = form.copy(
                                    title = form.title.ifBlank { found.title },
                                    author = form.author.ifBlank { found.author.orEmpty() },
                                    genre = if (form.genre == BookForm.DEFAULT_GENRE) found.genre ?: form.genre else form.genre,
                                    publicationYear = form.publicationYear.ifBlank { found.publicationYear?.toString().orEmpty() },
                                    description = form.description.ifBlank { found.description.orEmpty() },
                                    cover = if (form.cover == CoverChoice.Current(null) && found.coverUrl != null) {
                                        CoverChoice.Current(found.coverUrl)
                                    } else {
                                        form.cover
                                    },
                                ),
                            )
                        }
                        _events.send(EditBookEvent.Message("Found \"${found.title}\". Check the details before adding."))
                    }
                }
                .onFailure { _events.send(EditBookEvent.Message("Couldn't reach Open Library. Fill the details in yourself.")) }
            _uiState.update { it.copy(isLookingUp = false) }
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return
        if (!state.form.isValid) {
            _uiState.update { it.copy(showErrors = true) }
            return
        }
        val ownerId = auth.currentUserId() ?: run {
            viewModelScope.launch { _events.send(EditBookEvent.Message("Your session has ended. Sign in again.")) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            var uploaded: String? = null
            val result = runCatching {
                // Cover first: a failed upload should not leave a listing without
                // the photo the user chose.
                val coverUrl = state.form.cover.resolve(ownerId, uploader)
                if (state.form.cover is CoverChoice.Picked) uploaded = coverUrl
                repository.add(ownerId, state.form.toNewBook(coverUrl))
            }
            // A book that was never listed must not leave its photo in storage.
            if (result.getOrNull() !is AddBookOutcome.Added) repository.removeCover(ownerId, uploaded)
            result.onSuccess { outcome ->
                when (outcome) {
                    is AddBookOutcome.Added -> _events.send(EditBookEvent.Done("Book added"))
                    AddBookOutcome.DailyLimitReached ->
                        _events.send(EditBookEvent.Message("You've reached today's limit for new books. Try again tomorrow."))
                    is AddBookOutcome.BookLimitReached ->
                        _events.send(
                            EditBookEvent.Message(
                                "You can have at most ${outcome.limit} books listed. Delete one you no longer share to add another.",
                            ),
                        )
                }
            }.onFailure { _events.send(EditBookEvent.Message(it.toCoverAwareMessage())) }
            _uiState.update { it.copy(isSaving = false) }
        }
    }
}
