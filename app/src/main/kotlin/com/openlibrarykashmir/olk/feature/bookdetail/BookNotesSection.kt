package com.openlibrarykashmir.olk.feature.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openlibrarykashmir.olk.core.data.model.BookNote
import com.openlibrarykashmir.olk.core.data.model.BookNoteRules
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The website's community notes window, laid out inline on the book screen:
 * every note, then the viewer's own add/edit button when they may write one.
 */
@Composable
internal fun BookNotesSection(
    state: BookNotesUiState,
    onWrite: () -> Unit,
    onDelete: (BookNote) -> Unit,
    onRetry: () -> Unit,
) {
    // Which note is waiting for "Delete?" to be confirmed.
    var confirming by remember { mutableStateOf<BookNote?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "COMMUNITY NOTES",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            state.isLoading && state.notes.isEmpty() -> Hint("Loading notes…")
            state.loadFailed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Hint("Notes couldn't be loaded.")
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            state.notes.isEmpty() -> Hint(
                if (state.canWrite) {
                    "No notes yet — be the first to share your experience with this book."
                } else {
                    "No notes yet."
                },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            state.notes.forEach { note ->
                NoteCard(
                    note = note,
                    isMine = note.userId == state.viewerId,
                    canDelete = state.canDelete(note),
                    onDelete = { confirming = note },
                )
            }
        }

        when {
            state.canOpenEditor -> OutlinedButton(onClick = onWrite, modifier = Modifier.padding(top = 12.dp)) {
                Text(if (state.myNote != null) "Edit your note" else "Add your note")
            }
            state.isBookFull -> Hint("This book already has 10 community notes.")
            !state.isLoading && !state.loadFailed && !state.canWrite ->
                Hint("Notes are written by members who have owned or borrowed this book.")
        }
    }

    confirming?.let { note ->
        val isMine = note.userId == state.viewerId
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(if (isMine) "Delete your note?" else "Delete this note?") },
            text = {
                Text(
                    if (isMine) {
                        "It will be removed from this book for everyone."
                    } else {
                        "It will be removed for everyone, and the removal is recorded in the admin audit log."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onDelete(note)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NoteCard(note: BookNote, isMine: Boolean, canDelete: Boolean, onDelete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
            Text(text = note.note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                val name = note.authorName?.takeIf { it.isNotBlank() } ?: "Reader"
                val date = note.createdAt?.formatNoteDate()
                Text(
                    text = listOfNotNull(if (isMine) "$name (you)" else name, date).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isMine) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (canDelete) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Add or edit the viewer's note, with the website's live word counter. */
@Composable
internal fun NoteEditorDialog(
    initialText: String,
    isEditing: Boolean,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    val words = BookNoteRules.wordCount(text)
    val overLimit = words > BookNoteRules.WORD_LIMIT

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(if (isEditing) "Edit your note" else "Add your note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= BookNoteRules.CHAR_LIMIT) text = it },
                    placeholder = { Text("Share your personal experience with this book...") },
                    enabled = !isSaving,
                    isError = overLimit,
                    minLines = 4,
                    supportingText = { Text("$words/${BookNoteRules.WORD_LIMIT} words") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = !isSaving && !overLimit && text.isNotBlank()) {
                Text(if (isSaving) "Saving..." else if (isEditing) "Update note" else "Add note")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") } },
    )
}

@Composable
private fun Hint(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 8.dp),
)

// Built per call, so a language change while the app runs is picked up.
private fun String.formatNoteDate(): String? = runCatching {
    OffsetDateTime.parse(this).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
}.getOrNull()
