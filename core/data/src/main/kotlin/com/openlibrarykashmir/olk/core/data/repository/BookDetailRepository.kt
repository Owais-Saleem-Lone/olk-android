package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.BookDetail
import com.openlibrarykashmir.olk.core.data.model.IdRow
import com.openlibrarykashmir.olk.core.data.model.OwnershipRow
import com.openlibrarykashmir.olk.core.data.model.ProgressRow
import com.openlibrarykashmir.olk.core.data.model.ReportOutcome
import com.openlibrarykashmir.olk.core.data.model.ReportReason
import com.openlibrarykashmir.olk.core.data.model.RequestOutcome
import com.openlibrarykashmir.olk.core.data.model.RequestStatus
import com.openlibrarykashmir.olk.core.data.model.RequestStatusRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface BookDetailRepository {
    /** Null when the book does not exist or RLS hides it from this viewer. */
    suspend fun load(bookId: String, viewerId: String): BookDetail?

    suspend fun requestBook(bookId: String, requesterId: String): RequestOutcome

    suspend fun setSaved(bookId: String, userId: String, saved: Boolean)

    suspend fun report(bookId: String, reporterId: String, reason: ReportReason, details: String?): ReportOutcome
}

internal class SupabaseBookDetailRepository(
    private val client: SupabaseClient,
    private val books: BookRepository,
) : BookDetailRepository {

    override suspend fun load(bookId: String, viewerId: String): BookDetail? = coroutineScope {
        val book: Book = books.byId(bookId) ?: return@coroutineScope null

        // Everything below depends only on the book row and the viewer, never on
        // each other — the same fan-out the web page does with Promise.all. On a
        // slow link, five round trips in parallel beat five in sequence by seconds.
        val owner = async { client.ownerSummary(book.ownerId) }
        // Public "X% read" for the current borrower, as the web book page shows.
        val progress = async {
            client.from("book_progress").select(Columns.list("progress_pct")) {
                filter { eq("book_id", bookId) }
                limit(1)
            }.decodeSingleOrNull<ProgressRow>()?.progressPct
        }
        val activeRequest = async {
            client.from("book_requests").select(Columns.list("status")) {
                filter {
                    eq("book_id", bookId)
                    eq("requester_id", viewerId)
                    isIn("status", ACTIVE_REQUEST_STATUSES)
                }
                limit(1)
            }.decodeSingleOrNull<RequestStatusRow>()
        }
        val saved = async {
            client.from("bookmarks").select(Columns.list("id")) {
                filter {
                    eq("user_id", viewerId)
                    eq("book_id", bookId)
                }
                limit(1)
            }.decodeList<IdRow>().isNotEmpty()
        }
        // Shown only where the book itself is visible (web migration 20260925164004).
        val history = async {
            client.from("book_ownership_history").select(
                Columns.raw(
                    "owner_id, acquired_via, acquired_at, relinquished_at, " +
                        "profiles!book_ownership_history_owner_id_fkey(display_name)",
                ),
            ) {
                filter { eq("book_id", bookId) }
                order("acquired_at", Order.ASCENDING)
            }.decodeList<OwnershipRow>().map { it.toEntry() }
        }

        BookDetail(
            book = book,
            owner = owner.await(),
            readingProgressPct = progress.await(),
            isOwnBook = book.ownerId == viewerId,
            myRequestStatus = activeRequest.await()?.status,
            isSaved = saved.await(),
            ownershipHistory = history.await(),
        )
    }

    override suspend fun requestBook(bookId: String, requesterId: String): RequestOutcome =
        try {
            val row = client.from("book_requests").insert(
                buildJsonObject {
                    put("book_id", bookId)
                    put("requester_id", requesterId)
                    put("status", "pending")
                },
            ) { select(Columns.list("id")) }.decodeSingle<IdRow>()
            RequestOutcome.Created(row.id)
        } catch (e: PostgrestRestException) {
            when {
                e.code == UNIQUE_VIOLATION -> RequestOutcome.AlreadyRequested
                e.error.startsWith(RATE_LIMIT_PREFIX) || e.message.orEmpty().contains(RATE_LIMIT_PREFIX) ->
                    RequestOutcome.DailyLimitReached
                e.code == RLS_VIOLATION ->
                    if (client.refusedBecauseSuspended()) RequestOutcome.Suspended else RequestOutcome.NoLongerAvailable
                else -> throw e
            }
        }

    override suspend fun setSaved(bookId: String, userId: String, saved: Boolean) {
        if (saved) {
            try {
                client.from("bookmarks").insert(
                    buildJsonObject {
                        put("user_id", userId)
                        put("book_id", bookId)
                    },
                )
            } catch (e: PostgrestRestException) {
                // Already saved (a double tap, or saved on the website meanwhile)
                // is the state the user asked for, not an error.
                if (e.code != UNIQUE_VIOLATION) throw e
            }
        } else {
            client.from("bookmarks").delete {
                filter {
                    eq("user_id", userId)
                    eq("book_id", bookId)
                }
            }
        }
    }

    override suspend fun report(
        bookId: String,
        reporterId: String,
        reason: ReportReason,
        details: String?,
    ): ReportOutcome =
        try {
            // Only these columns are writable; the database fills in whom the
            // report is about (the book's owner), its status and its date.
            client.from("reports").insert(
                buildJsonObject {
                    put("reporter_id", reporterId)
                    put("reported_book_id", bookId)
                    put("reason", reason.label)
                    put("details", details?.trim()?.takeIf { it.isNotEmpty() }?.take(ReportReason.DETAILS_MAX))
                },
            )
            ReportOutcome.Sent
        } catch (e: PostgrestRestException) {
            when {
                e.code == UNIQUE_VIOLATION -> ReportOutcome.AlreadyReported
                e.error.startsWith(RATE_LIMIT_PREFIX) || e.message.orEmpty().contains(RATE_LIMIT_PREFIX) ->
                    ReportOutcome.DailyLimitReached
                e.code == RLS_VIOLATION && client.refusedBecauseSuspended() -> ReportOutcome.Suspended
                else -> throw e
            }
        }

    private companion object {
        val ACTIVE_REQUEST_STATUSES = listOf(
            RequestStatus.PENDING,
            RequestStatus.ACCEPTED,
            RequestStatus.HANDED_OVER,
        ).map { it.name.lowercase() }

        const val UNIQUE_VIOLATION = "23505"
        const val RLS_VIOLATION = "42501"
        const val RATE_LIMIT_PREFIX = "RATE_LIMIT_EXCEEDED"
    }
}
