package com.openlibrarykashmir.olk.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A row of the `browse_events()` RPC: upcoming, active events of active clubs.
 *
 * [distanceKm] is measured in the database from the signed-in user's own saved
 * location, exactly as for books and clubs; no coordinates reach the app.
 */
@Serializable
data class BrowseEvent(
    val id: String,
    @SerialName("club_id") val clubId: String,
    @SerialName("club_name") val clubName: String? = null,
    val title: String,
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("location_name") val locationName: String? = null,
    val visibility: String = VISIBILITY_PUBLIC,
    @SerialName("attendee_count") val attendeeCount: Int = 0,
    @SerialName("distance_km") val distanceKm: Double? = null,
) {
    val isMembersOnly: Boolean get() = visibility == VISIBILITY_MEMBERS_ONLY
}

/**
 * The event row itself. `meeting_url` is deliberately absent: it is not
 * readable from the row, only through `get_event_meeting_url()`, which decides
 * who may have it (web migration `20260920103949`).
 *
 * [active] is false only when the organiser is looking at an event they
 * cancelled — nobody else can read a cancelled event at all.
 */
data class Event(
    val id: String,
    val clubId: String,
    val clubName: String?,
    val creatorId: String,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val startsAt: String,
    val endsAt: String?,
    val isOnline: Boolean,
    val locationName: String?,
    val visibility: String,
    val capacity: Int?,
    val attendeeCount: Int,
    val active: Boolean,
) {
    val isMembersOnly: Boolean get() = visibility == VISIBILITY_MEMBERS_ONLY
}

/** An upcoming event as a club's page lists it. */
@Serializable
data class ClubEvent(
    val id: String,
    val title: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("location_name") val locationName: String? = null,
    val visibility: String = VISIBILITY_PUBLIC,
    @SerialName("attendee_count") val attendeeCount: Int = 0,
) {
    val isMembersOnly: Boolean get() = visibility == VISIBILITY_MEMBERS_ONLY
}

/** Someone who has said they are going. */
data class EventAttendee(
    val userId: String,
    val displayName: String?,
)

const val VISIBILITY_PUBLIC = "public"
const val VISIBILITY_MEMBERS_ONLY = "members_only"
