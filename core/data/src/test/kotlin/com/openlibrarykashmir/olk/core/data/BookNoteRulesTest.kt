package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.model.BookNoteRules
import com.openlibrarykashmir.olk.core.data.model.NoteOutcome
import com.openlibrarykashmir.olk.core.data.repository.noteErrorOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class BookNoteRulesTest {

    @Test
    fun `limits match the database`() {
        assertEquals(500, BookNoteRules.WORD_LIMIT)
        assertEquals(5000, BookNoteRules.CHAR_LIMIT)
        assertEquals(10, BookNoteRules.MAX_NOTES)
    }

    @Test
    fun `words are counted like the website counts them`() {
        assertEquals(0, BookNoteRules.wordCount(""))
        assertEquals(0, BookNoteRules.wordCount("   \n\t "))
        assertEquals(3, BookNoteRules.wordCount("  read it\n twice  "))
        assertEquals(500, BookNoteRules.wordCount(List(500) { "word" }.joinToString(" ")))
    }

    @Test
    fun `the cap trigger means the book is full`() {
        assertEquals(NoteOutcome.BookFull, noteErrorOutcome("P0001", "This book already has 10 community notes"))
    }

    @Test
    fun `the word trigger and the length constraint mean too long`() {
        assertEquals(NoteOutcome.TooLong, noteErrorOutcome("P0001", "Note exceeds the 500-word limit (got 501 words)"))
        assertEquals(
            NoteOutcome.TooLong,
            noteErrorOutcome("23514", "new row for relation \"book_notes\" violates check constraint \"book_notes_note_length\""),
        )
    }

    @Test
    fun `a row-level refusal means not allowed, anything else stays an error`() {
        assertEquals(NoteOutcome.NotAllowed, noteErrorOutcome("42501", "new row violates row-level security policy"))
        assertEquals(null, noteErrorOutcome("23505", "duplicate key value"))
        assertEquals(null, noteErrorOutcome(null, null))
    }
}
