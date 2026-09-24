package com.openlibrarykashmir.olk

import app.cash.turbine.test
import com.openlibrarykashmir.olk.core.data.model.BookNote
import com.openlibrarykashmir.olk.core.data.model.BookNotes
import com.openlibrarykashmir.olk.core.data.model.NoteOutcome
import com.openlibrarykashmir.olk.core.data.repository.BookNotesRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.bookdetail.BookNotesViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookNotesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuth : AuthRepository {
        override val authState: Flow<AuthState> = emptyFlow()
        override fun currentUserId() = "me"
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    private class FakeNotes(
        var notes: List<BookNote> = emptyList(),
        var canWrite: Boolean = true,
        var isAdmin: Boolean = false,
        var outcome: NoteOutcome = NoteOutcome.Saved,
        var deletes: Boolean = true,
    ) : BookNotesRepository {
        var saved: Triple<String?, String, String>? = null
        var deleted: String? = null

        override suspend fun load(bookId: String) = BookNotes(notes, canWrite, isAdmin)

        override suspend fun save(bookId: String, userId: String, existingNoteId: String?, text: String): NoteOutcome {
            saved = Triple(existingNoteId, userId, text)
            return outcome
        }

        override suspend fun delete(noteId: String): Boolean {
            deleted = noteId
            return deletes
        }
    }

    private fun note(id: String, userId: String) = BookNote(id, userId, "Name", "text $id", null)

    private fun viewModel(repo: FakeNotes) = BookNotesViewModel("b1", repo, FakeAuth())

    @Test
    fun `a member who never had the book gets no form`() = runTest {
        val vm = viewModel(FakeNotes(canWrite = false))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canOpenEditor)
        vm.openEditor()
        assertFalse(vm.uiState.value.isEditorOpen)
    }

    @Test
    fun `adds a note and says so`() = runTest {
        val repo = FakeNotes()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.messages.test {
            vm.openEditor()
            assertTrue(vm.uiState.value.isEditorOpen)
            vm.save("Read it twice.")
            advanceUntilIdle()

            assertEquals(Triple(null, "me", "Read it twice."), repo.saved)
            assertEquals("Note added!", awaitItem())
            assertFalse(vm.uiState.value.isEditorOpen)
        }
    }

    @Test
    fun `editing sends the existing note's id`() = runTest {
        val repo = FakeNotes(notes = listOf(note("n1", "me")))
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.messages.test {
            vm.openEditor()
            vm.save("Changed my mind.")
            advanceUntilIdle()
            assertEquals("n1", repo.saved?.first)
            assertEquals("Note updated!", awaitItem())
        }
    }

    @Test
    fun `a full book offers no new note but still lets you edit yours`() = runTest {
        val others = (1..10).map { note("o$it", "other$it") }
        val full = viewModel(FakeNotes(notes = others))
        advanceUntilIdle()
        assertTrue(full.uiState.value.isBookFull)
        assertFalse(full.uiState.value.canOpenEditor)

        val mine = viewModel(FakeNotes(notes = others + note("n1", "me")))
        advanceUntilIdle()
        assertFalse(mine.uiState.value.isBookFull)
        assertTrue(mine.uiState.value.canOpenEditor)
    }

    @Test
    fun `never sends a blank or over-long note`() = runTest {
        val repo = FakeNotes()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.save("   ")
        vm.save(List(501) { "word" }.joinToString(" "))
        advanceUntilIdle()
        assertNull(repo.saved)
    }

    @Test
    fun `explains each refusal from the database`() = runTest {
        val cases = mapOf(
            NoteOutcome.BookFull to "This book already has 10 community notes.",
            NoteOutcome.TooLong to "Notes can be at most 500 words and 5000 characters.",
            NoteOutcome.NotAllowed to "You can only add a note to a book you have owned or borrowed.",
            NoteOutcome.Suspended to "Your account is suspended, so you can't write notes until it ends.",
        )
        for ((outcome, expected) in cases) {
            val vm = viewModel(FakeNotes(outcome = outcome))
            advanceUntilIdle()
            vm.messages.test {
                vm.save("A note.")
                advanceUntilIdle()
                assertEquals(expected, awaitItem())
            }
        }
    }

    @Test
    fun `only an admin may delete someone else's note`() = runTest {
        val theirs = note("o1", "other")
        val member = FakeNotes(notes = listOf(theirs))
        val asMember = viewModel(member)
        advanceUntilIdle()
        assertFalse(asMember.uiState.value.canDelete(theirs))
        asMember.delete(theirs)
        advanceUntilIdle()
        assertNull(member.deleted)

        val admin = FakeNotes(notes = listOf(theirs), isAdmin = true)
        val asAdmin = viewModel(admin)
        advanceUntilIdle()
        assertTrue(asAdmin.uiState.value.canDelete(theirs))
        asAdmin.delete(theirs)
        advanceUntilIdle()
        assertEquals("o1", admin.deleted)
    }

    @Test
    fun `a delete that removed nothing is reported, not assumed`() = runTest {
        val vm = viewModel(FakeNotes(notes = listOf(note("n1", "me")), deletes = false))
        advanceUntilIdle()

        vm.messages.test {
            vm.delete(vm.uiState.value.myNote!!)
            advanceUntilIdle()
            assertEquals("The note could not be deleted. Please try again.", awaitItem())
        }
    }
}
