package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.BrowseClub
import com.openlibrarykashmir.olk.core.data.model.Club
import com.openlibrarykashmir.olk.core.data.model.ClubMember
import com.openlibrarykashmir.olk.core.data.model.ClubPost
import com.openlibrarykashmir.olk.core.data.model.ClubRating
import com.openlibrarykashmir.olk.core.data.model.MembershipStatus
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

/** What sending a club chat message did. */
sealed interface PostOutcome {
    data object Sent : PostOutcome

    /** The hourly per-club limit; owners and members have different allowances. */
    data object RateLimited : PostOutcome
}

/** What an owner's edit of the club's name and description did. */
sealed interface ClubEditOutcome {
    data object Saved : ClubEditOutcome

    /** A blank name, or text over the database's limits. */
    data object Invalid : ClubEditOutcome

    /** Nothing changed: the club was closed meanwhile, or is not the caller's. */
    data object Gone : ClubEditOutcome
}

/** What closing a club did (web migration `20260925140934_close_clubs`). */
sealed interface ClubCloseOutcome {
    /** Closed; its approved members were notified by the database. */
    data object Closed : ClubCloseOutcome

    data object AlreadyClosed : ClubCloseOutcome
}

/**
 * Clubs, their rosters and their chat.
 *
 * Two things the database enforces that this repository deliberately does not
 * second-guess, because the app must not be the only thing holding the line:
 * the chat is readable by members, the owner and admins only, and a membership
 * row can never be moved to another club or user. See the web migration
 * `20260920103949_harden_clubs_and_move_notifications.sql`.
 */
interface ClubsRepository {
    suspend fun browse(
        query: String = "",
        interest: String? = null,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): List<BrowseClub>

    suspend fun club(id: String): Club?

    /** Approved members. Empty for a non-member: RLS hides the roster. */
    suspend fun members(clubId: String): List<ClubMember>

    /** People waiting to be let in. Only ever non-empty for the club's owner. */
    suspend fun pendingApplicants(clubId: String): List<ClubMember>

    suspend fun membership(clubId: String, userId: String): MembershipStatus

    /**
     * Every club the user belongs to or has applied to, in one round trip — the
     * clubs list needs this for a whole page of cards at once.
     */
    suspend fun myMemberships(userId: String): Map<String, MembershipStatus>

    suspend fun requestToJoin(clubId: String, userId: String)

    suspend fun leave(clubId: String, userId: String)

    suspend fun approve(clubId: String, userId: String)

    suspend fun reject(clubId: String, userId: String)

    suspend fun posts(clubId: String, limit: Int = CHAT_PAGE_SIZE): List<ClubPost>

    suspend fun sendPost(clubId: String, authorId: String, content: String): PostOutcome

    suspend fun myRating(clubId: String, userId: String): ClubRating?

    suspend fun rate(clubId: String, userId: String, score: Int, comment: String?)

    /** Owner only (RLS). Only name and description: the rest stays with moderation. */
    suspend fun updateDetails(clubId: String, name: String, description: String?): ClubEditOutcome

    /**
     * Owner only. Hides the club, its chat and events from everyone; only an admin can
     * reopen it. Must go through `close_my_club`: a plain UPDATE of `active` is refused.
     */
    suspend fun closeClub(clubId: String): ClubCloseOutcome

    companion object {
        const val PAGE_SIZE = 20
        const val CHAT_PAGE_SIZE = 20

        /** `clubs.name` is varchar(200); the description check is 2000, like the website's form. */
        const val MAX_NAME_LENGTH = 200
        const val MAX_DESCRIPTION_LENGTH = 2000

        /** Same cap as the website's rating textarea and the `ratings_comment_length` check. */
        const val MAX_COMMENT_LENGTH = 1000
    }
}

internal class SupabaseClubsRepository(
    private val client: SupabaseClient,
) : ClubsRepository {

    override suspend fun browse(query: String, interest: String?, limit: Int, offset: Int): List<BrowseClub> =
        client.postgrest.rpc("browse_clubs", browseClubParams(query, interest, limit, offset)).decodeList()

    override suspend fun club(id: String): Club? =
        client.from("clubs").select(CLUB_COLUMNS) {
            filter { eq("id", id) }
            limit(1)
        }.decodeSingleOrNull()

    override suspend fun members(clubId: String): List<ClubMember> = roster(clubId, "approved")

    override suspend fun pendingApplicants(clubId: String): List<ClubMember> = roster(clubId, "pending")

    private suspend fun roster(clubId: String, status: String): List<ClubMember> =
        client.from("club_members").select(
            Columns.raw("user_id, joined_at, profiles(display_name, area_name)"),
        ) {
            filter {
                eq("club_id", clubId)
                eq("status", status)
            }
            order("joined_at", Order.ASCENDING)
        }.decodeList<MemberRow>().map { row ->
            ClubMember(
                userId = row.userId,
                displayName = row.profile?.displayName,
                areaName = row.profile?.areaName,
                joinedAt = row.joinedAt,
            )
        }

    override suspend fun membership(clubId: String, userId: String): MembershipStatus {
        val row = client.from("club_members").select(Columns.list("status")) {
            filter {
                eq("club_id", clubId)
                eq("user_id", userId)
            }
            limit(1)
        }.decodeSingleOrNull<StatusRow>()

        return membershipStatusOf(row?.status)
    }

    override suspend fun myMemberships(userId: String): Map<String, MembershipStatus> =
        client.from("club_members").select(Columns.list("club_id", "status")) {
            filter { eq("user_id", userId) }
        }.decodeList<MembershipRow>().associate { row ->
            row.clubId to membershipStatusOf(row.status)
        }

    override suspend fun requestToJoin(clubId: String, userId: String) {
        // `status` is left to its default: the INSERT policy only accepts
        // 'pending', and the owner's notification is written by a trigger.
        client.from("club_members").insert(
            buildJsonObject {
                put("club_id", clubId)
                put("user_id", userId)
            },
        )
    }

    override suspend fun leave(clubId: String, userId: String) {
        client.from("club_members").delete {
            filter {
                eq("club_id", clubId)
                eq("user_id", userId)
            }
        }
    }

    override suspend fun approve(clubId: String, userId: String) {
        // Only `status` is sent. club_id and user_id are immutable in the
        // database anyway, and the new member's notification is a trigger.
        client.from("club_members").update(buildJsonObject { put("status", "approved") }) {
            filter {
                eq("club_id", clubId)
                eq("user_id", userId)
            }
        }
    }

    override suspend fun reject(clubId: String, userId: String) = leave(clubId, userId)

    override suspend fun posts(clubId: String, limit: Int): List<ClubPost> =
        client.from("club_posts").select(
            Columns.raw("id, author_id, content, created_at, profiles(display_name)"),
        ) {
            filter { eq("club_id", clubId) }
            order("created_at", Order.DESCENDING)
            limit(limit.toLong())
        }.decodeList<PostRow>().map { row ->
            ClubPost(
                id = row.id,
                authorId = row.authorId,
                authorName = row.profile?.displayName,
                content = row.content,
                createdAt = row.createdAt,
            )
        }

    override suspend fun sendPost(clubId: String, authorId: String, content: String): PostOutcome =
        try {
            client.from("club_posts").insert(
                buildJsonObject {
                    put("club_id", clubId)
                    put("author_id", authorId)
                    put("content", content.trim())
                },
            )
            PostOutcome.Sent
        } catch (e: PostgrestRestException) {
            postErrorOutcome("${e.error} ${e.message}") ?: throw e
        }

    override suspend fun myRating(clubId: String, userId: String): ClubRating? =
        client.from("club_ratings").select(Columns.list("score", "comment")) {
            filter {
                eq("club_id", clubId)
                eq("rater_id", userId)
            }
            limit(1)
        }.decodeSingleOrNull()

    override suspend fun rate(clubId: String, userId: String, score: Int, comment: String?) {
        val trimmed = comment?.trim()?.take(ClubsRepository.MAX_COMMENT_LENGTH)?.takeIf { it.isNotEmpty() }
        val existing = myRating(clubId, userId)

        val payload = buildJsonObject {
            put("score", score)
            if (trimmed != null) put("comment", trimmed)
        }

        // Updated rather than upserted: an upsert needs SELECT on every column
        // it writes, which is how the website's "Save progress" quietly broke
        // for two months (web migration 20260918172119).
        if (existing == null) {
            client.from("club_ratings").insert(
                buildJsonObject {
                    put("club_id", clubId)
                    put("rater_id", userId)
                    put("score", score)
                    if (trimmed != null) put("comment", trimmed)
                },
            )
        } else {
            client.from("club_ratings").update(payload) {
                filter {
                    eq("club_id", clubId)
                    eq("rater_id", userId)
                }
            }
        }
    }

    override suspend fun updateDetails(clubId: String, name: String, description: String?): ClubEditOutcome =
        try {
            // The row is asked back: an UPDATE that RLS filtered out (closed meanwhile,
            // or not the caller's club) is not an error, it just changes nothing.
            val changed = client.from("clubs").update(
                buildJsonObject {
                    put("name", name.trim())
                    put("description", description?.trim()?.takeIf { it.isNotEmpty() })
                },
            ) {
                select(Columns.list("id"))
                filter { eq("id", clubId) }
            }.decodeList<ClubIdRow>()
            if (changed.isEmpty()) ClubEditOutcome.Gone else ClubEditOutcome.Saved
        } catch (e: PostgrestRestException) {
            clubEditErrorOutcome(e.code) ?: throw e
        }

    override suspend fun closeClub(clubId: String): ClubCloseOutcome =
        try {
            client.postgrest.rpc("close_my_club", buildJsonObject { put("p_club_id", clubId) })
            ClubCloseOutcome.Closed
        } catch (e: PostgrestRestException) {
            clubCloseErrorOutcome("${e.error} ${e.message}") ?: throw e
        }

    internal companion object {
        /**
         * Listed explicitly rather than `*`: latitude and longitude are not
         * readable by anyone but the database, and naming the columns keeps it
         * that way if the table ever grows.
         */
        val CLUB_COLUMNS = Columns.list(
            "id",
            "name",
            "description",
            "interests",
            "area_name",
            "cover_url",
            "creator_id",
            "member_count",
            "created_at",
            "rating_avg",
            "rating_count",
        )
    }
}

/** Parameters for `browse_clubs()`; blank filters are sent as null, i.e. "no filter". */
internal fun browseClubParams(query: String, interest: String?, limit: Int, offset: Int): JsonObject =
    buildJsonObject {
        query.trim().takeIf { it.isNotEmpty() }?.let { put("p_query", it) }
        interest?.takeIf { it.isNotBlank() }?.let { put("p_interest", it) }
        put("p_limit", limit)
        put("p_offset", offset)
    }

/** The one refusal a member can cause by posting; anything else is a real error. */
internal fun postErrorOutcome(message: String?): PostOutcome? =
    if (message.orEmpty().contains("RATE_LIMIT_EXCEEDED")) PostOutcome.RateLimited else null

/** A blank name (check `clubs_name_not_blank`, 23514) or text over varchar(200) (22001). */
internal fun clubEditErrorOutcome(code: String?): ClubEditOutcome? =
    if (code == "23514" || code == "22001") ClubEditOutcome.Invalid else null

/** close_my_club refuses a club that is not open, or not the caller's. */
internal fun clubCloseErrorOutcome(message: String?): ClubCloseOutcome? =
    if (message.orEmpty().contains("CLUB_NOT_OPEN")) ClubCloseOutcome.AlreadyClosed else null

/** Anything that is not one of the two known states means "not in this club". */
internal fun membershipStatusOf(status: String?): MembershipStatus = when (status) {
    "approved" -> MembershipStatus.APPROVED
    "pending" -> MembershipStatus.PENDING
    else -> MembershipStatus.NONE
}

@Serializable
private data class StatusRow(val status: String)

@Serializable
private data class ClubIdRow(val id: String)

@Serializable
private data class MembershipRow(
    @SerialName("club_id") val clubId: String,
    val status: String,
)

@Serializable
private data class MemberRow(
    @SerialName("user_id") val userId: String,
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("profiles") val profile: MemberProfile? = null,
)

@Serializable
private data class MemberProfile(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
)

@Serializable
private data class PostRow(
    val id: String,
    @SerialName("author_id") val authorId: String,
    val content: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("profiles") val profile: MemberProfile? = null,
)
