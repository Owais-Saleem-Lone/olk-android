package com.openlibrarykashmir.olk.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A row of `public.books`.
 *
 * Only the columns the app reads are mapped — Postgrest returns exactly the columns
 * a query selects, and [BookRepository] selects this set explicitly. Admin-only
 * columns (`hidden_by_admin`, `admin_hide_reason`, `hidden_at`) are intentionally
 * absent: RLS hides admin-hidden books from ordinary users, so the app never needs
 * to reason about them.
 */
@Serializable
data class Book(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val title: String,
    val author: String? = null,
    val condition: BookCondition? = null,
    @SerialName("listing_type") val listingType: ListingType,
    val status: BookStatus = BookStatus.AVAILABLE,
    val genre: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val description: String? = null,
    @SerialName("publication_year") val publicationYear: Int? = null,
    @SerialName("lending_duration_months") val lendingDurationMonths: Int? = null,
    @SerialName("read_count") val readCount: Int = 0,
    val featured: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Mirrors the `books_condition_check` constraint. */
@Serializable
enum class BookCondition {
    @SerialName("excellent") EXCELLENT,
    @SerialName("good") GOOD,
    @SerialName("fair") FAIR,
    @SerialName("poor") POOR,
}

/** Mirrors the `books_listing_type_check` constraint. */
@Serializable
enum class ListingType {
    @SerialName("donate") DONATE,
    @SerialName("lend") LEND,
}

/** Mirrors the `books_status_check` constraint. */
@Serializable
enum class BookStatus {
    @SerialName("available") AVAILABLE,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("given") GIVEN,
}
