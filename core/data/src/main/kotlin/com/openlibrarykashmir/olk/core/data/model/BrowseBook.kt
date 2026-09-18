package com.openlibrarykashmir.olk.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A row of the `browse_books()` RPC: a book plus what Browse shows about its owner.
 *
 * [distanceKm] is measured in the database from the signed-in user's own saved
 * location to the owner's; both are stored snapped to a ~1 km grid, and no
 * coordinates ever reach the app. It is null when either side has no saved location.
 */
@Serializable
data class BrowseBook(
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
    @SerialName("acquired_via_donation") val acquiredViaDonation: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("owner_name") val ownerName: String? = null,
    @SerialName("owner_area") val ownerArea: String? = null,
)

/** What Browse is narrowed to. Blank strings and nulls mean "no filter". */
data class BrowseFilters(
    val query: String = "",
    val genre: String? = null,
    val listingType: ListingType? = null,
    val condition: BookCondition? = null,
    val area: String = "",
    val radiusKm: Int? = null,
) {
    /** Filters set outside the search box, for the badge on the Filters chip. */
    val activeCount: Int
        get() = listOfNotNull(genre, listingType, condition, area.takeIf { it.isNotBlank() }, radiusKm).size

    companion object {
        /** The web's slider steps; null is "Any distance". */
        val RADIUS_STEPS_KM = listOf(2, 5, 10)
    }
}
