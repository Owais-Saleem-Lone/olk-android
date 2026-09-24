package com.openlibrarykashmir.olk.core.data.model

/** One community note on a book: what a member who had it thought of it. */
data class BookNote(
    val id: String,
    val userId: String,
    val authorName: String?,
    val note: String,
    val createdAt: String?,
)

/**
 * A book's notes and what the viewer may do with them. [canWrite] comes from the
 * database (`can_write_book_note`): members who have owned or borrowed the book
 * and aren't suspended. [isAdmin] lets a moderator remove anyone's note.
 */
data class BookNotes(
    val notes: List<BookNote>,
    val canWrite: Boolean,
    val isAdmin: Boolean,
)

sealed interface NoteOutcome {
    data object Saved : NoteOutcome
    data object BookFull : NoteOutcome
    data object TooLong : NoteOutcome
    data object NotAllowed : NoteOutcome
    data object Suspended : NoteOutcome
}

/**
 * The same limits the database enforces (web migration `20260924062618`):
 * `check_book_notes_limits()` and the `book_notes_note_length` constraint.
 */
object BookNoteRules {
    const val WORD_LIMIT = 500
    const val CHAR_LIMIT = 5000

    /** Notes by OTHER members after which nobody new may add one. */
    const val MAX_NOTES = 10

    /** Counted like the website's wordCount(): runs of whitespace separate words. */
    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
}
