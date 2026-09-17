package com.openlibrarykashmir.olk.feature.mybooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.BookCondition
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.repository.AddBookOutcome
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
            runCatching {
                // Cover first: a failed upload should not leave a listing without
                // the photo the user chose.
                val coverUrl = state.form.cover.resolve(ownerId, uploader)
                repository.add(ownerId, state.form.toNewBook(coverUrl))
            }.onSuccess { outcome ->
                when (outcome) {
                    is AddBookOutcome.Added -> _events.send(EditBookEvent.Done("Book added"))
                    AddBookOutcome.DailyLimitReached ->
                        _events.send(EditBookEvent.Message("You've reached today's limit for new books. Try again tomorrow."))
                }
            }.onFailure { _events.send(EditBookEvent.Message(it.toCoverAwareMessage())) }
            _uiState.update { it.copy(isSaving = false) }
        }
    }
}
