package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.ClubEligibility
import com.openlibrarykashmir.olk.core.data.model.ClubRequestDraft
import com.openlibrarykashmir.olk.core.data.model.ClubRequestOutcome
import com.openlibrarykashmir.olk.core.data.model.ClubRequestStatus
import com.openlibrarykashmir.olk.core.data.model.ClubRequestSummary
import com.openlibrarykashmir.olk.core.data.model.CoverBucket
import com.openlibrarykashmir.olk.core.data.model.CreateEventOutcome
import com.openlibrarykashmir.olk.core.data.model.EventDraft
import com.openlibrarykashmir.olk.core.data.model.IdRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

/**
 * Starting things: asking for a club (reviewed by the OLK team, who then ask for
 * an ID and CV by email) and scheduling a club's event. The rules live in the
 * database (web migration `20260924134533`); this maps its refusals to outcomes.
 */
interface ClubOrganiserRepository {
    suspend fun eligibility(): ClubEligibility

    suspend fun latestRequest(userId: String): ClubRequestSummary?

    suspend fun submitRequest(userId: String, draft: ClubRequestDraft): ClubRequestOutcome

    /** Withdraws a PENDING request. False when nothing was withdrawn. */
    suspend fun withdrawRequest(requestId: String): Boolean

    suspend fun createEvent(userId: String, draft: EventDraft): CreateEventOutcome

    /** Uploads an already-compressed WebP to `<bucket>/<ownerId>/…` and returns its public URL. */
    suspend fun uploadCover(bucket: CoverBucket, ownerId: String, webpBytes: ByteArray): String

    /** Deletes a cover uploaded for a request or event the database then refused. Best effort. */
    suspend fun removeCover(bucket: CoverBucket, publicUrl: String)
}

internal class SupabaseClubOrganiserRepository(private val client: SupabaseClient) : ClubOrganiserRepository {

    override suspend fun eligibility(): ClubEligibility =
        client.postgrest.rpc("my_club_eligibility").decodeList<ClubEligibility>().first()

    override suspend fun latestRequest(userId: String): ClubRequestSummary? =
        client.from("club_requests").select(
            Columns.list("id", "name", "status", "review_note", "created_club_id", "created_at"),
        ) {
            filter { eq("requester_id", userId) }
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeList<ClubRequestRow>().firstOrNull()?.toSummary()

    override suspend fun submitRequest(userId: String, draft: ClubRequestDraft): ClubRequestOutcome {
        // Like the website: the club is placed at the requester's own saved
        // location (snapped to ~1 km by the database), never at a typed point.
        val location = client.from("profile_locations").select(Columns.list("latitude", "longitude")) {
            filter { eq("user_id", userId) }
        }.decodeSingleOrNull<OrganiserPointRow>()

        return try {
            client.from("club_requests").insert(
                buildJsonObject {
                    put("requester_id", userId)
                    put("name", draft.name.trim())
                    put("interests", JsonArray(draft.interests.map(::JsonPrimitive)))
                    put("description", draft.description.trim())
                    put("goal", draft.goal.trim().ifEmpty { null })
                    put("target_members", draft.targetMembers.trim().ifEmpty { null })
                    put("area_name", draft.areaName.trim().ifEmpty { null })
                    put("latitude", location?.latitude?.let(::JsonPrimitive) ?: JsonNull)
                    put("longitude", location?.longitude?.let(::JsonPrimitive) ?: JsonNull)
                    put("cover_url", draft.coverUrl)
                },
            )
            ClubRequestOutcome.Submitted
        } catch (e: PostgrestRestException) {
            val outcome = clubRequestErrorOutcome(e.code, "${e.error} ${e.message}")
            when {
                outcome == null -> throw e
                outcome == ClubRequestOutcome.Invalid && e.code == RLS_VIOLATION && client.refusedBecauseSuspended() ->
                    ClubRequestOutcome.Suspended
                else -> outcome
            }
        }
    }

    override suspend fun withdrawRequest(requestId: String): Boolean {
        // RLS allows deleting only one's own PENDING request, silently; ask for
        // the row back to know it went.
        val withdrawn = client.from("club_requests").delete {
            select(Columns.list("id", "requester_id", "cover_url"))
            filter { eq("id", requestId) }
        }.decodeList<WithdrawnRequestRow>().firstOrNull() ?: return false
        // A cover uploaded for a club that will never exist.
        client.removeOwnCover(CoverBucket.CLUBS, withdrawn.coverUrl, withdrawn.requesterId)
        return true
    }

    override suspend fun createEvent(userId: String, draft: EventDraft): CreateEventOutcome =
        try {
            val row = client.from("club_events").insert(
                buildJsonObject {
                    put("club_id", draft.clubId)
                    put("creator_id", userId)
                    put("title", draft.title.trim())
                    put("description", draft.description.trim().ifEmpty { null })
                    put("cover_url", draft.coverUrl)
                    put("starts_at", draft.startsAt.toString())
                    put("ends_at", draft.endsAt?.toString())
                    put("is_online", draft.isOnline)
                    put("location_name", if (draft.isOnline) null else draft.locationName.trim().ifEmpty { null })
                    put("meeting_url", if (draft.isOnline) draft.meetingUrl.trim().ifEmpty { null } else null)
                    put("visibility", if (draft.membersOnly) "members_only" else "public")
                    put("capacity", draft.capacity)
                },
            ) {
                // Only the id: meeting_url is not readable from the row, so a bare
                // select() (RETURNING *) is refused -- the website hit exactly that.
                select(Columns.list("id"))
            }.decodeSingle<IdRow>()
            CreateEventOutcome.Created(row.id)
        } catch (e: PostgrestRestException) {
            when (val outcome = eventErrorOutcome(e.code, "${e.error} ${e.message}")) {
                null -> throw e
                CreateEventOutcome.NotAllowed ->
                    if (client.refusedBecauseSuspended()) CreateEventOutcome.Suspended else CreateEventOutcome.NotAllowed
                else -> outcome
            }
        }

    override suspend fun uploadCover(bucket: CoverBucket, ownerId: String, webpBytes: ByteArray): String {
        // The `<userId>/<timestamp>.<ext>` layout the website uses.
        val path = "$ownerId/${Clock.System.now().toEpochMilliseconds()}.webp"
        val storage = client.storage.from(bucket.id)
        storage.upload(path, webpBytes) {
            upsert = false
            contentType = ContentType.parse("image/webp")
        }
        return storage.publicUrl(path)
    }

    override suspend fun removeCover(bucket: CoverBucket, publicUrl: String) {
        // Only a URL of that bucket; a pasted link is never ours to delete.
        val path = coverStoragePath(publicUrl, bucket) ?: return
        runCatching { client.storage.from(bucket.id).delete(path) }
    }

    private companion object {
        const val RLS_VIOLATION = "42501"
    }
}

/** The database's refusals of a club request, by what the member can do about them. */
internal fun clubRequestErrorOutcome(code: String?, message: String?): ClubRequestOutcome? {
    val text = message.orEmpty()
    return when {
        code == "23505" -> ClubRequestOutcome.AlreadyPending
        "CLUB_LIMIT_REACHED" in text -> ClubRequestOutcome.AlreadyRunsClub
        "Not eligible" in text -> ClubRequestOutcome.NotEligible(
            text.substringAfter("Not eligible").let { "Not eligible$it" }.substringBefore("\n").trim(),
        )
        // Constraints, the word/interest triggers, and a row-level refusal
        // (suspension is asked about separately) all mean the form was refused.
        code == "23514" || code == "42501" || "CLUB_INTEREST_INVALID" in text || "word limit" in text ->
            ClubRequestOutcome.Invalid
        else -> null
    }
}

/** The database's refusals of a new event. */
internal fun eventErrorOutcome(code: String?, message: String?): CreateEventOutcome? {
    val text = message.orEmpty()
    return when {
        "RATE_LIMIT_EXCEEDED" in text && "per 30 days" in text -> CreateEventOutcome.MonthlyLimitReached
        "RATE_LIMIT_EXCEEDED" in text -> CreateEventOutcome.CapacityTooHigh
        "EVENT_IN_PAST" in text -> CreateEventOutcome.StartsInPast
        code == "42501" -> CreateEventOutcome.NotAllowed
        code == "23514" -> CreateEventOutcome.Invalid
        else -> null
    }
}

@Serializable
private data class WithdrawnRequestRow(
    @SerialName("requester_id") val requesterId: String,
    @SerialName("cover_url") val coverUrl: String? = null,
)

@Serializable
private data class ClubRequestRow(
    val id: String,
    val name: String,
    val status: String,
    @SerialName("review_note") val reviewNote: String? = null,
    @SerialName("created_club_id") val createdClubId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toSummary() = ClubRequestSummary(
        id = id,
        name = name,
        status = when (status) {
            "approved" -> ClubRequestStatus.APPROVED
            "rejected" -> ClubRequestStatus.REJECTED
            else -> ClubRequestStatus.PENDING
        },
        reviewNote = reviewNote,
        createdClubId = createdClubId,
        createdAt = createdAt,
    )
}

@Serializable
private data class OrganiserPointRow(val latitude: Double? = null, val longitude: Double? = null)
