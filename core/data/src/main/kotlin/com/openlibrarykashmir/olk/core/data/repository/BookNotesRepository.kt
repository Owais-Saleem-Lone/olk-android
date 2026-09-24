package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.BookNote
import com.openlibrarykashmir.olk.core.data.model.BookNoteRules
import com.openlibrarykashmir.olk.core.data.model.BookNotes
import com.openlibrarykashmir.olk.core.data.model.IdRow
import com.openlibrarykashmir.olk.core.data.model.NoteOutcome
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface BookNotesRepository {
    suspend fun load(bookId: String): BookNotes

    /** Adds the viewer's note, or replaces the text of [existingNoteId]. */
    suspend fun save(bookId: String, userId: String, existingNoteId: String?, text: String): NoteOutcome

    /** Own note, or anyone's for an admin. False when nothing was deleted. */
    suspend fun delete(noteId: String): Boolean
}

internal class SupabaseBookNotesRepository(private val client: SupabaseClient) : BookNotesRepository {

    override suspend fun load(bookId: String): BookNotes = coroutineScope {
        // Oldest first, as the website's notes window lists them; a book never
        // holds more than MAX_NOTES from others plus the viewer's own.
        val notes = async {
            client.from("book_notes").select(
                Columns.raw("id, user_id, note, created_at, profiles!book_notes_user_id_fkey(display_name)"),
            ) {
                filter { eq("book_id", bookId) }
                order("created_at", Order.ASCENDING)
                limit((BookNoteRules.MAX_NOTES + 1).toLong())
            }.decodeList<NoteRow>().map { it.toNote() }
        }
        val canWrite = async {
            client.postgrest.rpc("can_write_book_note", buildJsonObject { put("p_book_id", bookId) })
                .decodeAs<Boolean?>() == true
        }
        // Only decides whether a Delete is shown on others' notes; the database
        // decides whether it works, so a failed question just hides the button.
        val isAdmin = async {
            runCatching { client.postgrest.rpc("is_admin").decodeAs<Boolean?>() == true }.getOrDefault(false)
        }
        BookNotes(notes = notes.await(), canWrite = canWrite.await(), isAdmin = isAdmin.await())
    }

    override suspend fun save(bookId: String, userId: String, existingNoteId: String?, text: String): NoteOutcome =
        try {
            // Only these columns are writable: the id and date are the
            // database's, and an existing note's book and author never change.
            if (existingNoteId == null) {
                client.from("book_notes").insert(
                    buildJsonObject {
                        put("book_id", bookId)
                        put("user_id", userId)
                        put("note", text.trim())
                    },
                )
            } else {
                client.from("book_notes").update(buildJsonObject { put("note", text.trim()) }) {
                    filter { eq("id", existingNoteId) }
                }
            }
            NoteOutcome.Saved
        } catch (e: PostgrestRestException) {
            when (val outcome = noteErrorOutcome(e.code, "${e.error} ${e.message}")) {
                NoteOutcome.NotAllowed ->
                    if (client.refusedBecauseSuspended()) NoteOutcome.Suspended else NoteOutcome.NotAllowed
                null -> throw e
                else -> outcome
            }
        }

    override suspend fun delete(noteId: String): Boolean =
        // A delete RLS doesn't allow removes nothing and raises nothing, so ask
        // for the row back to know it really went.
        client.from("book_notes").delete {
            select(Columns.list("id"))
            filter { eq("id", noteId) }
        }.decodeList<IdRow>().isNotEmpty()
}

/**
 * The database's refusals of a note, by what the member can do about them. The
 * two trigger messages come from `check_book_notes_limits()`; 23514 is the
 * blank/5000-character constraint; 42501 is the owned-or-borrowed rule (or a
 * suspension, which the caller asks about separately).
 */
internal fun noteErrorOutcome(code: String?, message: String?): NoteOutcome? {
    val text = message.orEmpty()
    return when {
        "10 community notes" in text -> NoteOutcome.BookFull
        "word limit" in text -> NoteOutcome.TooLong
        code == "23514" -> NoteOutcome.TooLong
        code == "42501" -> NoteOutcome.NotAllowed
        else -> null
    }
}

@Serializable
private data class NoteRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val note: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("profiles") val profile: NoteAuthor? = null,
) {
    fun toNote() = BookNote(id = id, userId = userId, authorName = profile?.displayName, note = note, createdAt = createdAt)
}

@Serializable
private data class NoteAuthor(@SerialName("display_name") val displayName: String? = null)
