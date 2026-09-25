package com.openlibrarykashmir.olk.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the book detail screen shows, assembled from several tables.
 *
 * @property myRequestStatus the viewer's *active* request for this book, if any —
 *   only `pending`, `accepted` or `handed_over`, mirroring the partial unique index
 *   `idx_unique_active_request`. A declined or returned request does not block a
 *   new one, so it is reported as null.
 */
data class BookDetail(
    val book: Book,
    val owner: OwnerSummary?,
    /** How far the current borrower has read, when they have shared it. Public, as on the web. */
    val readingProgressPct: Int? = null,
    val isOwnBook: Boolean,
    val myRequestStatus: RequestStatus?,
    val isSaved: Boolean,
    /** Everyone who has owned this book, oldest first (`book_ownership_history`). */
    val ownershipHistory: List<OwnershipEntry> = emptyList(),
) {
    /** The web shows the journey only once the book has changed hands. */
    val hasChangedHands: Boolean get() = ownershipHistory.size > 1
}

/** One owner in a book's journey. */
data class OwnershipEntry(
    /** Null once that member has deleted their account; the entry itself is kept. */
    val ownerId: String?,
    val ownerName: String?,
    val acquiredVia: AcquiredVia,
    val acquiredAt: String,
    /** Null for the current owner. */
    val relinquishedAt: String?,
)

/** Mirrors the `acquired_via` CHECK on `book_ownership_history`. */
@Serializable
enum class AcquiredVia {
    @SerialName("listed") LISTED,
    @SerialName("donation_received") DONATION_RECEIVED,
}

@Serializable
internal data class OwnershipRow(
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("acquired_via") val acquiredVia: AcquiredVia,
    @SerialName("acquired_at") val acquiredAt: String,
    @SerialName("relinquished_at") val relinquishedAt: String? = null,
    @SerialName("profiles") val owner: OwnershipOwner? = null,
) {
    fun toEntry() = OwnershipEntry(ownerId, owner?.displayName, acquiredVia, acquiredAt, relinquishedAt)
}

@Serializable
internal data class OwnershipOwner(@SerialName("display_name") val displayName: String? = null)

data class OwnerSummary(
    val id: String,
    val displayName: String?,
    val areaName: String?,
    val bio: String?,
    val joinedAt: String?,
    val booksListed: Int,
    val booksAvailable: Int,
    val booksShared: Int,
    val ratingAverage: Double?,
    val ratingCount: Int,
) {
    /** Same threshold the web app uses for its "Trusted Sharer" badge. */
    val isTrustedSharer: Boolean get() = booksShared >= TRUSTED_SHARER_MIN_SHARED

    companion object {
        const val TRUSTED_SHARER_MIN_SHARED = 3
    }
}

/** Mirrors the `book_requests_status_check` constraint. */
@Serializable
enum class RequestStatus {
    @SerialName("pending") PENDING,
    @SerialName("accepted") ACCEPTED,
    @SerialName("declined") DECLINED,
    @SerialName("handed_over") HANDED_OVER,
    @SerialName("returned") RETURNED,

    /** Closed by the database when the requester was suspended (web migration `20260922171355`). */
    @SerialName("cancelled") CANCELLED,
}

/** Result of asking for a book, with the failures a user can act on made explicit. */
sealed interface RequestOutcome {
    data class Created(val requestId: String) : RequestOutcome

    /** Unique-index violation: the viewer already has an active request. */
    data object AlreadyRequested : RequestOutcome

    /** `enforce_request_rate_limit` fired (`max_requests_per_day`, default 10). */
    data object DailyLimitReached : RequestOutcome

    /**
     * The "Users can create requests" insert policy refused it (SQLSTATE 42501):
     * the book stopped being available, was hidden by an admin, or belongs to the
     * viewer. Postgres reports all three identically.
     *
     * The own-book check only exists in the database from the web repo's
     * `block_requesting_own_book` migration onward; before that, only the UI
     * prevented it (the detail screen never offers Request on your own book).
     */
    data object NoLongerAvailable : RequestOutcome

    /** The viewer's account is suspended: no new requests until it ends. */
    data object Suspended : RequestOutcome
}

/** Column subset of `public.profiles` safe for any signed-in user to read. */
@Serializable
internal data class OwnerProfileRow(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
    val bio: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
internal data class StatusRow(val status: BookStatus)

@Serializable
internal data class RequestStatusRow(val status: RequestStatus)

@Serializable
internal data class ScoreRow(val score: Int)

@Serializable
internal data class IdRow(val id: String)

@Serializable
internal data class ProgressRow(@SerialName("progress_pct") val progressPct: Int)
