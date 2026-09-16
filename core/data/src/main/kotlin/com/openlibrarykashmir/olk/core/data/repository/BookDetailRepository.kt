package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.BookDetail
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.IdRow
import com.openlibrarykashmir.olk.core.data.model.OwnerProfileRow
import com.openlibrarykashmir.olk.core.data.model.OwnerSummary
import com.openlibrarykashmir.olk.core.data.model.RequestOutcome
import com.openlibrarykashmir.olk.core.data.model.RequestStatus
import com.openlibrarykashmir.olk.core.data.model.RequestStatusRow
import com.openlibrarykashmir.olk.core.data.model.ScoreRow
import com.openlibrarykashmir.olk.core.data.model.StatusRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

interface BookDetailRepository {
    /** Null when the book does not exist or RLS hides it from this viewer. */
    suspend fun load(bookId: String, viewerId: String): BookDetail?

    suspend fun requestBook(bookId: String, requesterId: String): RequestOutcome

    suspend fun setSaved(bookId: String, userId: String, saved: Boolean)
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
        val profile = async {
            client.from("profiles")
                .select(Columns.list("id", "display_name", "area_name", "bio", "created_at")) {
                    filter { eq("id", book.ownerId) }
                }.decodeSingleOrNull<OwnerProfileRow>()
        }
        val ownerBooks = async {
            client.from("books").select(Columns.list("status")) {
                filter { eq("owner_id", book.ownerId) }
            }.decodeList<StatusRow>()
        }
        val scores = async {
            client.from("ratings").select(Columns.list("score")) {
                filter { eq("rated_user_id", book.ownerId) }
            }.decodeList<ScoreRow>()
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

        val ownerStatuses = ownerBooks.await()
        val ratingScores = scores.await().map { it.score }

        BookDetail(
            book = book,
            owner = profile.await()?.let { row ->
                OwnerSummary(
                    id = row.id,
                    displayName = row.displayName,
                    areaName = row.areaName,
                    bio = row.bio,
                    joinedAt = row.createdAt,
                    booksListed = ownerStatuses.size,
                    booksAvailable = ownerStatuses.count { it.status == BookStatus.AVAILABLE },
                    booksShared = ownerStatuses.count { it.status == BookStatus.GIVEN },
                    // One decimal place, matching the web page's rounding.
                    ratingAverage = ratingScores.takeIf { it.isNotEmpty() }
                        ?.let { (it.average() * 10).roundToInt() / 10.0 },
                    ratingCount = ratingScores.size,
                )
            },
            isOwnBook = book.ownerId == viewerId,
            myRequestStatus = activeRequest.await()?.status,
            isSaved = saved.await(),
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
                e.code == RLS_VIOLATION -> RequestOutcome.NoLongerAvailable
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
