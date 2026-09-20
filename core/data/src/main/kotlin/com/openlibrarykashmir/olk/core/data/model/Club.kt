package com.openlibrarykashmir.olk.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A row of the `browse_clubs()` RPC.
 *
 * [distanceKm] is measured in the database from the signed-in user's own saved
 * location to the club's; both are stored snapped to a ~1 km grid and no
 * coordinates ever reach the app. It is null when either side has no location,
 * and so is [creatorName] when nobody is signed in — signed out there is no
 * profiles read at all.
 */
@Serializable
data class BrowseClub(
    val id: String,
    val name: String,
    val description: String? = null,
    val interests: List<String> = emptyList(),
    @SerialName("area_name") val areaName: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("creator_id") val creatorId: String,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("creator_name") val creatorName: String? = null,
    @SerialName("rating_avg") val ratingAvg: Double? = null,
    @SerialName("rating_count") val ratingCount: Int = 0,
)

/** The club row itself. Coordinates are not readable, so there are none here. */
@Serializable
data class Club(
    val id: String,
    val name: String,
    val description: String? = null,
    val interests: List<String> = emptyList(),
    @SerialName("area_name") val areaName: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("creator_id") val creatorId: String,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("rating_avg") val ratingAvg: Double? = null,
    @SerialName("rating_count") val ratingCount: Int = 0,
)

/** Where the signed-in user stands with a club. */
enum class MembershipStatus {
    /** Never asked, or left again. */
    NONE,

    /** Asked to join; the club's owner has not decided yet. */
    PENDING,

    /** In the club: can read and write the chat, see the roster and rate it. */
    APPROVED,
}

/** Someone in a club's roster, or waiting to be let in. */
data class ClubMember(
    val userId: String,
    val displayName: String?,
    val areaName: String?,
    val joinedAt: String?,
)

/** A message in a club's chat. Only members, the owner and admins can read these. */
data class ClubPost(
    val id: String,
    val authorId: String,
    val authorName: String?,
    val content: String,
    val createdAt: String?,
)

/** The signed-in user's own rating of a club. */
@Serializable
data class ClubRating(
    val score: Int,
    val comment: String? = null,
)

/**
 * The club categories, mirroring the website's `src/lib/club-interests.ts`.
 * Fixed rather than queried: the website has them as a constant too, and the
 * list is the same for everyone.
 */
val CLUB_INTERESTS: List<String> = listOf(
    "Art & Painting", "Biography", "Business & Finance", "Cinema",
    "English Literature", "Fiction", "General", "Hindi Literature",
    "History", "Philosophy", "Poetry", "Psychology",
    "Science", "Self-Help", "Technology", "Travel",
    "Urdu Literature", "Writing",
)
