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
    val isOwnBook: Boolean,
    val myRequestStatus: RequestStatus?,
    val isSaved: Boolean,
)

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
