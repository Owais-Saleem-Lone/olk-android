package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.BrowseEvent
import com.openlibrarykashmir.olk.core.data.model.ClubEvent
import com.openlibrarykashmir.olk.core.data.model.Event
import com.openlibrarykashmir.olk.core.data.model.EventAttendee
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** What pressing "I'm going" did. */
sealed interface RsvpOutcome {
    data object Going : RsvpOutcome

    /** Someone took the last place first; the database counts the real RSVPs. */
    data object Full : RsvpOutcome

    /** There is already an RSVP for this person — a double tap, or another device. */
    data object AlreadyGoing : RsvpOutcome
}

/**
 * Club events and who is going to them.
 *
 * What the database holds the line on, so the app does not have to: a cancelled
 * event, or one whose club was taken down, is unreadable to everyone but its
 * organiser; capacity is settled against the real RSVPs with the event row
 * locked; members-only events accept RSVPs from approved members only; and the
 * joining link comes from `get_event_meeting_url()`, never from the row. See the
 * web migrations `20260920103949` and `20260920115611`.
 */
interface EventsRepository {
    suspend fun browse(query: String = "", limit: Int = PAGE_SIZE, offset: Int = 0): List<BrowseEvent>

    suspend fun event(id: String): Event?

    /** A club's next events, soonest first. Cancelled ones are left out even for their organiser. */
    suspend fun clubEvents(clubId: String): List<ClubEvent>

    /** The organiser's display name; profiles expose it to every signed-in reader. */
    suspend fun displayName(userId: String): String?

    suspend fun isGoing(eventId: String, userId: String): Boolean

    /** Empty unless the reader is a club member, going themselves, or the organiser. */
    suspend fun attendees(eventId: String): List<EventAttendee>

    /** Null unless the reader is the organiser, or going and allowed to be. */
    suspend fun meetingUrl(eventId: String): String?

    suspend fun rsvp(eventId: String, userId: String): RsvpOutcome

    suspend fun cancelRsvp(eventId: String, userId: String)

    /** The organiser hides their event. Everyone else stops seeing it at once. */
    suspend fun cancelEvent(eventId: String)

    companion object {
        const val PAGE_SIZE = 20
        const val CLUB_EVENTS_LIMIT = 10
    }
}

internal class SupabaseEventsRepository(
    private val client: SupabaseClient,
) : EventsRepository {

    override suspend fun browse(query: String, limit: Int, offset: Int): List<BrowseEvent> =
        client.postgrest.rpc("browse_events", browseEventParams(query, limit, offset)).decodeList()

    override suspend fun event(id: String): Event? =
        client.from("club_events").select(EVENT_COLUMNS) {
            filter { eq("id", id) }
            limit(1)
        }.decodeSingleOrNull<EventRow>()?.toEvent()

    override suspend fun clubEvents(clubId: String): List<ClubEvent> =
        client.from("club_events").select(
            Columns.list("id", "title", "starts_at", "is_online", "location_name", "visibility", "attendee_count"),
        ) {
            filter {
                eq("club_id", clubId)
                // Explicit rather than left to RLS: an organiser can still read
                // their own cancelled events, which is what lets them cancel.
                eq("active", true)
                gte("starts_at", Instant.now().toString())
            }
            order("starts_at", Order.ASCENDING)
            limit(EventsRepository.CLUB_EVENTS_LIMIT.toLong())
        }.decodeList()

    override suspend fun displayName(userId: String): String? =
        client.from("profiles").select(Columns.list("display_name")) {
            filter { eq("id", userId) }
            limit(1)
        }.decodeSingleOrNull<NameRow>()?.displayName

    override suspend fun isGoing(eventId: String, userId: String): Boolean =
        client.from("event_rsvps").select(Columns.list("id")) {
            filter {
                eq("event_id", eventId)
                eq("user_id", userId)
            }
            limit(1)
        }.decodeList<IdRow>().isNotEmpty()

    override suspend fun attendees(eventId: String): List<EventAttendee> =
        client.from("event_rsvps").select(Columns.raw("user_id, profiles(display_name)")) {
            filter { eq("event_id", eventId) }
            order("created_at", Order.ASCENDING)
        }.decodeList<AttendeeRow>().map { row ->
            EventAttendee(userId = row.userId, displayName = row.profile?.displayName)
        }

    override suspend fun meetingUrl(eventId: String): String? =
        client.postgrest.rpc("get_event_meeting_url", buildJsonObject { put("p_event_id", eventId) })
            .decodeAs<String?>()?.takeIf { it.isNotBlank() }

    override suspend fun rsvp(eventId: String, userId: String): RsvpOutcome =
        try {
            // Exactly the two columns `authenticated` may insert; id and
            // created_at are the database's.
            client.from("event_rsvps").insert(
                buildJsonObject {
                    put("event_id", eventId)
                    put("user_id", userId)
                },
            )
            RsvpOutcome.Going
        } catch (e: PostgrestRestException) {
            rsvpErrorOutcome("${e.code} ${e.error} ${e.message}") ?: throw e
        }

    override suspend fun cancelRsvp(eventId: String, userId: String) {
        client.from("event_rsvps").delete {
            filter {
                eq("event_id", eventId)
                eq("user_id", userId)
            }
        }
    }

    override suspend fun cancelEvent(eventId: String) {
        client.from("club_events").update(buildJsonObject { put("active", false) }) {
            filter { eq("id", eventId) }
        }
    }

    internal companion object {
        /**
         * Named, never `*`: meeting_url, latitude and longitude are not
         * readable, and a `*` would make PostgREST ask for them and fail.
         */
        val EVENT_COLUMNS = Columns.raw(
            "id, club_id, creator_id, title, description, cover_url, starts_at, ends_at, " +
                "is_online, location_name, visibility, capacity, attendee_count, active, clubs(name)",
        )
    }
}

/** Parameters for `browse_events()`; a blank query is left out, i.e. "no filter". */
internal fun browseEventParams(query: String, limit: Int, offset: Int): JsonObject =
    buildJsonObject {
        query.trim().takeIf { it.isNotEmpty() }?.let { put("p_query", it) }
        put("p_limit", limit)
        put("p_offset", offset)
    }

/**
 * The two refusals an RSVP can meet in normal use. `EVENT_FULL` is raised by the
 * capacity trigger; 23505 is the (event_id, user_id) unique key. Anything else —
 * including an RLS refusal — is a real error.
 */
internal fun rsvpErrorOutcome(message: String?): RsvpOutcome? {
    val text = message.orEmpty()
    return when {
        "EVENT_FULL" in text -> RsvpOutcome.Full
        "23505" in text || "duplicate key" in text -> RsvpOutcome.AlreadyGoing
        else -> null
    }
}

@Serializable
private data class EventRow(
    val id: String,
    @SerialName("club_id") val clubId: String,
    @SerialName("creator_id") val creatorId: String,
    val title: String,
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("location_name") val locationName: String? = null,
    val visibility: String = "public",
    val capacity: Int? = null,
    @SerialName("attendee_count") val attendeeCount: Int = 0,
    val active: Boolean = true,
    @SerialName("clubs") val club: ClubName? = null,
) {
    fun toEvent() = Event(
        id = id,
        clubId = clubId,
        clubName = club?.name,
        creatorId = creatorId,
        title = title,
        description = description,
        coverUrl = coverUrl,
        startsAt = startsAt,
        endsAt = endsAt,
        isOnline = isOnline,
        locationName = locationName,
        visibility = visibility,
        capacity = capacity,
        attendeeCount = attendeeCount,
        active = active,
    )
}

@Serializable
private data class ClubName(val name: String)

@Serializable
private data class NameRow(@SerialName("display_name") val displayName: String? = null)

@Serializable
private data class IdRow(val id: String)

@Serializable
private data class AttendeeRow(
    @SerialName("user_id") val userId: String,
    @SerialName("profiles") val profile: NameRow? = null,
)
