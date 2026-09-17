package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.IdRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The listing fields an owner may change — the same set the web edit panel offers. */
data class BookEdit(
    val title: String,
    val author: String?,
    val status: BookStatus,
    val genre: String?,
    /** Only meaningful for lend listings; the web sends null for donations. */
    val lendingDurationMonths: Int?,
)

sealed interface DeleteOutcome {
    data object Deleted : DeleteOutcome

    /**
     * The delete policy matched no row. In practice that means the book is in
     * permanent circulation (`acquired_via_donation`) or was already gone —
     * Postgres reports a policy-filtered DELETE as zero rows, not as an error.
     */
    data object NotAllowed : DeleteOutcome
}

interface MyBooksRepository {
    /** Books owned by [ownerId], whatever their status, newest first. */
    suspend fun list(ownerId: String): List<Book>

    /** The saved row, or null when it no longer exists or is not the viewer's. */
    suspend fun update(bookId: String, edit: BookEdit): Book?

    suspend fun delete(bookId: String): DeleteOutcome

    /** Active genre names in the admin-defined display order. */
    suspend fun genres(): List<String>
}

internal class SupabaseMyBooksRepository(
    private val client: SupabaseClient,
) : MyBooksRepository {

    override suspend fun list(ownerId: String): List<Book> =
        client.from(SupabaseBookRepository.TABLE).select(SupabaseBookRepository.COLUMNS) {
            filter { eq("owner_id", ownerId) }
            order("created_at", Order.DESCENDING)
            // Tie-breaker, so an edit never makes a book jump to a new place in the list.
            order("id", Order.ASCENDING)
        }.decodeList()

    override suspend fun update(bookId: String, edit: BookEdit): Book? =
        client.from(SupabaseBookRepository.TABLE).update(
            // Built by hand so a cleared author or lending period is sent as an
            // explicit null rather than dropped by the serializer's null handling.
            buildJsonObject {
                put("title", edit.title)
                put("author", edit.author)
                put("status", Json.encodeToJsonElement(BookStatus.serializer(), edit.status))
                put("genre", edit.genre)
                put("lending_duration_months", edit.lendingDurationMonths)
            },
        ) {
            filter { eq("id", bookId) }
            select(SupabaseBookRepository.COLUMNS)
        }.decodeSingleOrNull()

    override suspend fun delete(bookId: String): DeleteOutcome {
        val deleted = client.from(SupabaseBookRepository.TABLE).delete {
            filter { eq("id", bookId) }
            select(Columns.list("id"))
        }.decodeList<IdRow>()
        return if (deleted.isEmpty()) DeleteOutcome.NotAllowed else DeleteOutcome.Deleted
    }

    override suspend fun genres(): List<String> =
        client.from("genres").select(Columns.list("name")) {
            filter { eq("active", true) }
            order("display_order", Order.ASCENDING)
        }.decodeList<GenreRow>().map { it.name }
}

@Serializable
private data class GenreRow(val name: String)
