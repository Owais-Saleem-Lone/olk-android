package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.IdRow
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.model.RequestStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One request as either side sees it: the book, and the person on the other end. */
data class BookRequestItem(
    val id: String,
    val status: RequestStatus,
    val createdAt: String?,
    val handedOverAt: String?,
    val book: RequestBook,
    /** The requester on an incoming request; the owner on an outgoing one. */
    val otherParty: PersonSummary?,
)

data class RequestBook(
    val id: String,
    val title: String,
    val ownerId: String,
    val listingType: ListingType,
    val lendingDurationMonths: Int?,
    val coverUrl: String?,
)

data class PersonSummary(val id: String, val displayName: String?, val areaName: String?)

/** What happened when the user moved a request along. */
sealed interface RequestActionOutcome {
    data object Done : RequestActionOutcome

    /**
     * The request was no longer in the state the action needs — answered, handed
     * over or returned from the website or by the other person meanwhile. The
     * database refuses these outright (guard trigger / RPC precondition), so the
     * right response is to reload, not to retry.
     */
    data object OutOfDate : RequestActionOutcome
}

interface RequestsRepository {
    /** Requests for books [ownerId] owns, newest first. */
    suspend fun incoming(ownerId: String): List<BookRequestItem>

    /** Requests [requesterId] has made, newest first. */
    suspend fun outgoing(requesterId: String): List<BookRequestItem>

    /** Owner only, pending requests only. */
    suspend fun accept(requestId: String): RequestActionOutcome
    suspend fun decline(requestId: String): RequestActionOutcome

    /** Either party, accepted requests only. */
    suspend fun confirmHandover(requestId: String): RequestActionOutcome

    /** Either party, handed-over lends only. */
    suspend fun confirmReturn(requestId: String): RequestActionOutcome

    /**
     * Requester only, handed-over donations only: finishes reading and takes
     * ownership, putting the book into permanent circulation.
     */
    suspend fun completeDonatedReading(requestId: String): RequestActionOutcome

    /** Requests [raterId] has already rated; each exchange can be rated once per side. */
    suspend fun ratedRequestIds(raterId: String): Set<String>

    /**
     * Rates the other party of a handed-over or returned exchange (the insert
     * policy on `ratings` enforces both). [comment] is at most [MAX_RATING_COMMENT].
     */
    suspend fun rate(requestId: String, raterId: String, ratedUserId: String, score: Int, comment: String?): RateOutcome

    /** The reader's shared progress for these requests, by request id. */
    suspend fun progress(requestIds: List<String>): Map<String, Int>

    /**
     * Requester only, while the book is handed over (`set_reading_progress`);
     * anything else is [RequestActionOutcome.OutOfDate].
     */
    suspend fun setProgress(requestId: String, percent: Int): RequestActionOutcome

    companion object {
        /** `ratings_comment_length` in the database. */
        const val MAX_RATING_COMMENT = 1000
    }
}

sealed interface RateOutcome {
    data object Rated : RateOutcome

    /** Already rated from this device, the website or another phone. */
    data object AlreadyRated : RateOutcome

    /** The exchange is not (or no longer) one this person can rate. */
    data object NotAllowed : RateOutcome
}

internal class SupabaseRequestsRepository(
    private val client: SupabaseClient,
) : RequestsRepository {

    override suspend fun incoming(ownerId: String): List<BookRequestItem> =
        client.from(TABLE).select(Columns.raw("$REQUEST_COLUMNS, books!inner($BOOK_COLUMNS), profiles($PROFILE_COLUMNS)")) {
            // One round trip: filter on the embedded book instead of first
            // fetching the owner's book ids the way the web hook does.
            filter { eq("books.owner_id", ownerId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<RequestRow>().map { row -> row.toItem(otherParty = row.requester?.toSummary()) }

    override suspend fun outgoing(requesterId: String): List<BookRequestItem> {
        val rows = client.from(TABLE).select(Columns.raw("$REQUEST_COLUMNS, books!inner($BOOK_COLUMNS)")) {
            filter { eq("requester_id", requesterId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<RequestRow>()

        // books.owner_id references auth.users, not profiles, so PostgREST cannot
        // embed the owner; fetch those profiles in one batch instead.
        val ownerIds = rows.map { it.book.ownerId }.distinct()
        val owners = if (ownerIds.isEmpty()) {
            emptyMap()
        } else {
            client.from("profiles").select(Columns.raw(PROFILE_COLUMNS)) {
                filter { isIn("id", ownerIds) }
            }.decodeList<ProfileRow>().associateBy { it.id }
        }
        return rows.map { row -> row.toItem(otherParty = owners[row.book.ownerId]?.toSummary()) }
    }

    override suspend fun accept(requestId: String) = answer(requestId, RequestStatus.ACCEPTED)

    override suspend fun decline(requestId: String) = answer(requestId, RequestStatus.DECLINED)

    override suspend fun confirmHandover(requestId: String) = rpc("confirm_book_handover", requestId)

    override suspend fun confirmReturn(requestId: String) = rpc("confirm_book_return", requestId)

    override suspend fun completeDonatedReading(requestId: String) =
        rpc("complete_donated_book_reading", requestId)

    override suspend fun ratedRequestIds(raterId: String): Set<String> =
        client.from("ratings").select(Columns.list("request_id")) {
            filter { eq("rater_id", raterId) }
        }.decodeList<RatedRow>().mapNotNullTo(HashSet()) { it.requestId }

    override suspend fun rate(
        requestId: String,
        raterId: String,
        ratedUserId: String,
        score: Int,
        comment: String?,
    ): RateOutcome =
        try {
            client.from("ratings").insert(
                buildJsonObject {
                    put("request_id", requestId)
                    put("rater_id", raterId)
                    put("rated_user_id", ratedUserId)
                    put("score", score)
                    comment?.trim()?.takeIf { it.isNotEmpty() }?.let { put("comment", it) }
                },
            )
            RateOutcome.Rated
        } catch (e: PostgrestRestException) {
            when (e.code) {
                UNIQUE_VIOLATION -> RateOutcome.AlreadyRated
                INSUFFICIENT_PRIVILEGE -> RateOutcome.NotAllowed
                else -> throw e
            }
        }

    override suspend fun progress(requestIds: List<String>): Map<String, Int> =
        if (requestIds.isEmpty()) {
            emptyMap()
        } else {
            client.from("book_progress").select(Columns.list("request_id", "progress_pct")) {
                filter { isIn("request_id", requestIds) }
            }.decodeList<ProgressByRequestRow>().associate { it.requestId to it.progressPct }
        }

    override suspend fun setProgress(requestId: String, percent: Int): RequestActionOutcome =
        try {
            client.postgrest.rpc(
                "set_reading_progress",
                buildJsonObject {
                    put("p_request_id", requestId)
                    put("p_progress_pct", percent.coerceIn(0, 100))
                },
            )
            RequestActionOutcome.Done
        } catch (e: PostgrestRestException) {
            // PROGRESS_NOT_ALLOWED: returned, or no longer this person's book.
            if (e.code == CHECK_VIOLATION) RequestActionOutcome.OutOfDate else throw e
        }

    private suspend fun answer(requestId: String, status: RequestStatus): RequestActionOutcome =
        try {
            val updated = client.from(TABLE).update(
                buildJsonObject { put("status", status.name.lowercase()) },
            ) {
                filter {
                    eq("id", requestId)
                    // Only a pending request can be answered. Filtering here as well
                    // as in the database turns "already answered" into zero rows
                    // rather than an error the user cannot act on.
                    eq("status", "pending")
                }
                select(Columns.list("id"))
            }.decodeList<IdRow>()
            if (updated.isEmpty()) RequestActionOutcome.OutOfDate else RequestActionOutcome.Done
        } catch (e: PostgrestRestException) {
            if (e.code == INSUFFICIENT_PRIVILEGE) RequestActionOutcome.OutOfDate else throw e
        }

    private suspend fun rpc(function: String, requestId: String): RequestActionOutcome =
        try {
            client.postgrest.rpc(function, buildJsonObject { put("p_request_id", requestId) })
            RequestActionOutcome.Done
        } catch (e: PostgrestRestException) {
            // The RPCs raise plain exceptions (SQLSTATE P0001) when the request is
            // not in the state they need, or the caller is not a party to it.
            if (e.code == RAISED_EXCEPTION) RequestActionOutcome.OutOfDate else throw e
        }

    private companion object {
        const val TABLE = "book_requests"
        const val REQUEST_COLUMNS = "id, status, created_at, handed_over_at, requester_id"
        const val BOOK_COLUMNS = "id, title, owner_id, listing_type, lending_duration_months, cover_url"
        const val PROFILE_COLUMNS = "id, display_name, area_name"
        const val INSUFFICIENT_PRIVILEGE = "42501"
        const val RAISED_EXCEPTION = "P0001"
        const val UNIQUE_VIOLATION = "23505"
        const val CHECK_VIOLATION = "23514"
    }
}

@Serializable
private data class RequestRow(
    val id: String,
    val status: RequestStatus,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("handed_over_at") val handedOverAt: String? = null,
    @SerialName("books") val book: BookRow,
    @SerialName("profiles") val requester: ProfileRow? = null,
) {
    fun toItem(otherParty: PersonSummary?) = BookRequestItem(
        id = id,
        status = status,
        createdAt = createdAt,
        handedOverAt = handedOverAt,
        book = RequestBook(
            id = book.id,
            title = book.title,
            ownerId = book.ownerId,
            listingType = book.listingType,
            lendingDurationMonths = book.lendingDurationMonths,
            coverUrl = book.coverUrl,
        ),
        otherParty = otherParty,
    )
}

@Serializable
private data class BookRow(
    val id: String,
    val title: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("listing_type") val listingType: ListingType,
    @SerialName("lending_duration_months") val lendingDurationMonths: Int? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
)

@Serializable
private data class ProfileRow(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
) {
    fun toSummary() = PersonSummary(id = id, displayName = displayName, areaName = areaName)
}

@Serializable
private data class RatedRow(@SerialName("request_id") val requestId: String? = null)

@Serializable
private data class ProgressByRequestRow(
    @SerialName("request_id") val requestId: String,
    @SerialName("progress_pct") val progressPct: Int,
)
