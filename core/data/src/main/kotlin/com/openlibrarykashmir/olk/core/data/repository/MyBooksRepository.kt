package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.BookCondition
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.CoverBucket
import com.openlibrarykashmir.olk.core.data.model.IdRow
import com.openlibrarykashmir.olk.core.data.model.ListingType
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

/** The listing fields an owner may change — the same set the web edit panel offers. */
data class BookEdit(
    val title: String,
    val author: String?,
    val status: BookStatus,
    val genre: String?,
    /** Only meaningful for lend listings; the web sends null for donations. */
    val lendingDurationMonths: Int?,
    /** A public URL from [MyBooksRepository.uploadCover], or null for no cover. */
    val coverUrl: String?,
)

/** A new listing — the fields the web's Add Book form collects. */
data class NewBook(
    val title: String,
    val author: String?,
    val condition: BookCondition,
    val listingType: ListingType,
    val genre: String,
    val description: String?,
    val publicationYear: Int?,
    /** Required for lend listings (1–3), null for donations. */
    val lendingDurationMonths: Int?,
    val coverUrl: String?,
)

sealed interface AddBookOutcome {
    data class Added(val bookId: String) : AddBookOutcome

    /** `enforce_book_listing_rate_limit` (`max_books_per_day`, default 20). */
    data object DailyLimitReached : AddBookOutcome

    /** The same trigger's `max_books_per_user` (25): books a member may have listed at once. */
    data class BookLimitReached(val limit: Int) : AddBookOutcome
}

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

    /**
     * Lists a book. People whose wishlist matches the title are notified by the
     * database (`notify_wishlist_matches`), not by the app.
     */
    suspend fun add(ownerId: String, book: NewBook): AddBookOutcome

    /** The saved row, or null when it no longer exists or is not the viewer's. */
    suspend fun update(bookId: String, edit: BookEdit): Book?

    suspend fun delete(bookId: String): DeleteOutcome

    /** Active genre names in the admin-defined display order. */
    suspend fun genres(): List<String>

    /**
     * Uploads an already-compressed WebP cover to `book-covers/<ownerId>/…` — the
     * only folder the storage policy lets this user write — and returns its
     * public URL.
     */
    suspend fun uploadCover(ownerId: String, webpBytes: ByteArray): String

    /**
     * Deletes a cover no book points at any more — replaced, removed, or its
     * book deleted or never saved. Only a file in [ownerId]'s own folder; best
     * effort.
     */
    suspend fun removeCover(ownerId: String, publicUrl: String?)
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

    override suspend fun add(ownerId: String, book: NewBook): AddBookOutcome =
        try {
            val row = client.from(SupabaseBookRepository.TABLE).insert(
                buildJsonObject {
                    put("owner_id", ownerId)
                    put("title", book.title)
                    put("author", book.author)
                    put("condition", Json.encodeToJsonElement(BookCondition.serializer(), book.condition))
                    put("listing_type", Json.encodeToJsonElement(ListingType.serializer(), book.listingType))
                    put("genre", book.genre)
                    put("description", book.description)
                    put("publication_year", book.publicationYear)
                    put("lending_duration_months", book.lendingDurationMonths)
                    put("cover_url", book.coverUrl)
                },
            ) { select(Columns.list("id")) }.decodeSingle<IdRow>()
            AddBookOutcome.Added(row.id)
        } catch (e: PostgrestRestException) {
            addBookErrorOutcome("${e.error} ${e.message}") ?: throw e
        }

    override suspend fun update(bookId: String, edit: BookEdit): Book? =
        client.from(SupabaseBookRepository.TABLE).update(
            // Built by hand so a cleared author, cover or lending period is sent as
            // an explicit null rather than dropped by the serializer's null handling.
            buildJsonObject {
                put("title", edit.title)
                put("author", edit.author)
                put("status", Json.encodeToJsonElement(BookStatus.serializer(), edit.status))
                put("genre", edit.genre)
                put("lending_duration_months", edit.lendingDurationMonths)
                put("cover_url", edit.coverUrl)
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

    override suspend fun genres(): List<String> = client.activeGenres()

    override suspend fun uploadCover(ownerId: String, webpBytes: ByteArray): String {
        // Same `<userId>/<timestamp>.<ext>` layout the web form uses.
        val path = "$ownerId/${Clock.System.now().toEpochMilliseconds()}.webp"
        val bucket = client.storage.from(CoverBucket.BOOKS.id)
        bucket.upload(path, webpBytes) {
            upsert = false
            contentType = ContentType.parse("image/webp")
        }
        return bucket.publicUrl(path)
    }

    override suspend fun removeCover(ownerId: String, publicUrl: String?) =
        client.removeOwnCover(CoverBucket.BOOKS, publicUrl, ownerId)
}

/** The listing limits `enforce_book_listing_rate_limit` raises (web migration 20260924193921). */
internal fun addBookErrorOutcome(message: String): AddBookOutcome? = when {
    "BOOK_LIMIT_REACHED" in message ->
        AddBookOutcome.BookLimitReached(Regex("max (\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull() ?: 25)
    "RATE_LIMIT_EXCEEDED" in message -> AddBookOutcome.DailyLimitReached
    else -> null
}
