package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.ReportOutcome
import com.openlibrarykashmir.olk.core.data.model.ReportReason
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Someone the signed-in member has blocked. */
data class BlockedMember(val userId: String, val displayName: String?)

sealed interface BlockOutcome {
    data object Blocked : BlockOutcome
    /** `enforce_block_limit`: 500 blocked members at most. */
    data object LimitReached : BlockOutcome
}

/**
 * Blocking a member (web migration 20260924202314). It cuts contact both ways:
 * no requests, messages or club joins between the two, each other's books leave
 * Browse, and their posts and notes are hidden from the blocker. Open exchanges
 * close (a book already handed over can still be returned). It is silent: only
 * the blocker can read the row, so the other member is never told.
 */
interface BlocksRepository {
    /** Whether the signed-in member has blocked [userId]. */
    suspend fun isBlocked(userId: String): Boolean

    suspend fun block(myId: String, userId: String): BlockOutcome

    /** False when there was no such block (already undone). */
    suspend fun unblock(userId: String): Boolean

    /** The members the signed-in member has blocked, most recent first. */
    suspend fun blocked(): List<BlockedMember>

    /**
     * Reports a member (not one of their books), from their profile or a chat.
     * [context] says where it came from, e.g. which chat, and is kept with the details.
     */
    suspend fun reportMember(
        myId: String,
        userId: String,
        reason: ReportReason,
        details: String?,
        context: String? = null,
    ): ReportOutcome
}

internal class SupabaseBlocksRepository(private val client: SupabaseClient) : BlocksRepository {

    override suspend fun isBlocked(userId: String): Boolean =
        // RLS returns only the caller's own blocks.
        client.from(TABLE).select(Columns.list("blocked_id")) {
            filter { eq("blocked_id", userId) }
        }.decodeList<BlockedIdRow>().isNotEmpty()

    override suspend fun block(myId: String, userId: String): BlockOutcome =
        try {
            client.from(TABLE).insert(
                buildJsonObject {
                    put("blocker_id", myId)
                    put("blocked_id", userId)
                },
            )
            BlockOutcome.Blocked
        } catch (e: PostgrestRestException) {
            blockErrorOutcome(e.code, "${e.error} ${e.message}") ?: throw e
        }

    override suspend fun unblock(userId: String): Boolean =
        client.from(TABLE).delete {
            select(Columns.list("blocked_id"))
            filter { eq("blocked_id", userId) }
        }.decodeList<BlockedIdRow>().isNotEmpty()

    override suspend fun blocked(): List<BlockedMember> =
        client.from(TABLE).select(
            Columns.raw("blocked_id, profiles!user_blocks_blocked_id_fkey(display_name)"),
        ) {
            order("created_at", Order.DESCENDING)
        }.decodeList<BlockRow>().map { BlockedMember(it.blockedId, it.profile?.displayName) }

    override suspend fun reportMember(
        myId: String,
        userId: String,
        reason: ReportReason,
        details: String?,
        context: String?,
    ): ReportOutcome =
        try {
            client.from("reports").insert(
                buildJsonObject {
                    put("reporter_id", myId)
                    put("reported_user_id", userId)
                    put("reason", reason.label)
                    put(
                        "details",
                        listOfNotNull(context, details?.trim()?.takeIf { it.isNotEmpty() })
                            .joinToString("\n").take(ReportReason.DETAILS_MAX).ifEmpty { null },
                    )
                },
            )
            ReportOutcome.Sent
        } catch (e: PostgrestRestException) {
            reportErrorOutcome(e.code, e.message)
                ?: if (e.code == "42501" && client.refusedBecauseSuspended()) ReportOutcome.Suspended else throw e
        }

    private companion object {
        const val TABLE = "user_blocks"
    }
}

/** The report refusals a member can act on; null for anything else. */
internal fun reportErrorOutcome(code: String?, message: String?): ReportOutcome? = when {
    code == "23505" -> ReportOutcome.AlreadyReported
    message.orEmpty().contains("RATE_LIMIT_EXCEEDED") -> ReportOutcome.DailyLimitReached
    else -> null
}

/** A repeat block is already done; the cap is the only refusal a member can act on. */
internal fun blockErrorOutcome(code: String?, message: String?): BlockOutcome? = when {
    code == "23505" -> BlockOutcome.Blocked
    "BLOCK_LIMIT_REACHED" in message.orEmpty() -> BlockOutcome.LimitReached
    else -> null
}

@Serializable
private data class BlockedIdRow(@SerialName("blocked_id") val blockedId: String)

@Serializable
private data class BlockRow(
    @SerialName("blocked_id") val blockedId: String,
    @SerialName("profiles") val profile: BlockedProfile? = null,
)

@Serializable
private data class BlockedProfile(@SerialName("display_name") val displayName: String? = null)
