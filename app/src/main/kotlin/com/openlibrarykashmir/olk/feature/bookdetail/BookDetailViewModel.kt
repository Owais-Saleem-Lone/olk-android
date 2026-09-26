package com.openlibrarykashmir.olk.feature.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.BookDetail
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.ReportReason
import com.openlibrarykashmir.olk.ui.message
import com.openlibrarykashmir.olk.core.data.model.RequestOutcome
import com.openlibrarykashmir.olk.core.data.model.RequestStatus
import com.openlibrarykashmir.olk.core.data.repository.BookDetailRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface BookDetailUiState {
    data object Loading : BookDetailUiState
    data object NotFound : BookDetailUiState
    data class Error(val message: String) : BookDetailUiState
    data class Content(
        val detail: BookDetail,
        val isRequesting: Boolean = false,
        val isReportOpen: Boolean = false,
        val isReporting: Boolean = false,
    ) : BookDetailUiState {
        val primaryAction: PrimaryAction
            get() {
                val requestStatus = detail.myRequestStatus
                return when {
                    detail.book.status == BookStatus.GIVEN -> PrimaryAction.Unavailable("Donated")
                    detail.book.status == BookStatus.UNAVAILABLE -> PrimaryAction.Unavailable("Currently being read")
                    detail.isOwnBook -> PrimaryAction.Unavailable("This is your book")
                    requestStatus != null -> PrimaryAction.AlreadyRequested(requestStatus)
                    else -> PrimaryAction.Request
                }
            }

        /** Saving is offered only where the web offers it: someone else's available book. */
        val canSave: Boolean
            get() = !detail.isOwnBook && detail.book.status == BookStatus.AVAILABLE

        /** As on the website: any book but your own. */
        val canReport: Boolean
            get() = !detail.isOwnBook
    }
}

/** The one main button, in the same precedence the web page resolves it. */
sealed interface PrimaryAction {
    data object Request : PrimaryAction
    data class AlreadyRequested(val status: RequestStatus) : PrimaryAction
    data class Unavailable(val label: String) : PrimaryAction
}

class BookDetailViewModel(
    private val bookId: String,
    private val repository: BookDetailRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BookDetailUiState>(BookDetailUiState.Loading)
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    // One-off messages. A Channel rather than state so a snackbar is shown once and
    // is not replayed on rotation.
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        val viewerId = auth.currentUserId() ?: run {
            _uiState.value = BookDetailUiState.Error("Your session has ended. Sign in again.")
            return
        }
        viewModelScope.launch {
            _uiState.value = BookDetailUiState.Loading
            _uiState.value = runCatching { repository.load(bookId, viewerId) }.fold(
                onSuccess = { it?.let(BookDetailUiState::Content) ?: BookDetailUiState.NotFound },
                onFailure = { BookDetailUiState.Error(it.toUserMessage()) },
            )
        }
    }

    fun requestBook() {
        val content = _uiState.value as? BookDetailUiState.Content ?: return
        if (content.isRequesting || content.primaryAction != PrimaryAction.Request) return
        val viewerId = auth.currentUserId() ?: return

        viewModelScope.launch {
            updateContent { it.copy(isRequesting = true) }
            runCatching { repository.requestBook(bookId, viewerId) }
                .onSuccess { outcome ->
                    when (outcome) {
                        is RequestOutcome.Created -> {
                            setRequestStatus(RequestStatus.PENDING)
                            _messages.send("Requested. The owner will be in touch.")
                        }
                        RequestOutcome.AlreadyRequested -> {
                            // Requested from the website or another device meanwhile —
                            // show the true state rather than just an error.
                            setRequestStatus(RequestStatus.PENDING)
                            _messages.send("You already have an active request for this book.")
                        }
                        RequestOutcome.DailyLimitReached ->
                            _messages.send("You've reached your daily request limit. Try again tomorrow.")
                        RequestOutcome.NoLongerAvailable -> {
                            _messages.send("This book is no longer available.")
                            load()
                        }
                        RequestOutcome.Suspended ->
                            _messages.send("Your account is suspended, so you can't request books until it ends.")
                    }
                }
                .onFailure { _messages.send(it.toUserMessage()) }
            updateContent { it.copy(isRequesting = false) }
        }
    }

    fun toggleSaved() {
        val content = _uiState.value as? BookDetailUiState.Content ?: return
        if (!content.canSave) return
        val viewerId = auth.currentUserId() ?: return
        val target = !content.detail.isSaved

        // Optimistic: a bookmark is cheap to undo and the tap should feel instant.
        updateContent { it.copy(detail = it.detail.copy(isSaved = target)) }
        viewModelScope.launch {
            runCatching { repository.setSaved(bookId, viewerId, target) }
                .onFailure {
                    updateContent { it.copy(detail = it.detail.copy(isSaved = !target)) }
                    _messages.send(it.toUserMessage())
                }
        }
    }

    fun openReport() = updateContent { if (it.canReport) it.copy(isReportOpen = true) else it }

    fun dismissReport() = updateContent { if (it.isReporting) it else it.copy(isReportOpen = false) }

    fun report(reason: ReportReason, details: String) {
        val content = _uiState.value as? BookDetailUiState.Content ?: return
        if (content.isReporting || !content.canReport) return
        val viewerId = auth.currentUserId() ?: return

        viewModelScope.launch {
            updateContent { it.copy(isReporting = true) }
            runCatching { repository.report(bookId, viewerId, reason, details) }
                .onSuccess { outcome ->
                    // Every answer but a failure closes the dialog: there is
                    // nothing more the member can do with it.
                    updateContent { it.copy(isReporting = false, isReportOpen = false) }
                    _messages.send(outcome.message("this book"))
                }
                .onFailure {
                    updateContent { it.copy(isReporting = false) }
                    _messages.send(it.toUserMessage())
                }
        }
    }

    private fun setRequestStatus(status: RequestStatus) =
        updateContent { it.copy(detail = it.detail.copy(myRequestStatus = status)) }

    private inline fun updateContent(transform: (BookDetailUiState.Content) -> BookDetailUiState.Content) =
        _uiState.update { if (it is BookDetailUiState.Content) transform(it) else it }
}

private fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Something went wrong. Please try again."
    }
}
