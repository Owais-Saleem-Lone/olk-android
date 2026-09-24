package com.openlibrarykashmir.olk.core.data.repository

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

    private companion object {
        const val TABLE = "user_blocks"
    }
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
