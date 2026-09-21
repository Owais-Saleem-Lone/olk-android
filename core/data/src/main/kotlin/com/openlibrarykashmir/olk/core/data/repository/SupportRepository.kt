package com.openlibrarykashmir.olk.core.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A message in the private thread between a member and the admin team. */
@Serializable
data class SupportMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    /** Set by the database's rule, never by the sender: only staff can post with it. */
    @SerialName("sender_is_admin") val senderIsAdmin: Boolean,
    val content: String,
    @SerialName("created_at") val createdAt: String,
)

sealed interface SupportSendOutcome {
    data class Sent(val message: SupportMessage) : SupportSendOutcome

    /** `enforce_admin_message_rate_limit` (`max_messages_per_hour`, default 30). */
    data object RateLimited : SupportSendOutcome

    /** Blank or over `admin_messages_content_length`. */
    data object Invalid : SupportSendOutcome
}

/**
 * "Contact Admin": one thread per member, readable by them and the admin team.
 *
 * The timestamps are the database's (web migration `20260921172859`), the
 * admin badge is checked against the sender's real role, and replies are
 * announced by a trigger — this repository only reads and writes messages.
 */
interface SupportRepository {
    /** The member's thread, started on first use. */
    suspend fun conversationId(userId: String): String

    /** Whether the signed-in user is on the admin team; their messages carry the badge. */
    suspend fun isAdminTeam(): Boolean

    /** Oldest first. */
    suspend fun history(conversationId: String): List<SupportMessage>

    suspend fun send(conversationId: String, senderId: String, senderIsAdmin: Boolean, content: String): SupportSendOutcome

    /** Messages added to the thread from now on, for as long as the flow is collected. */
    fun newMessages(conversationId: String): Flow<SupportMessage>

    companion object {
        /** Mirrors `admin_messages_content_length`. */
        const val MAX_LENGTH = 2000
    }
}

internal class SupabaseSupportRepository(
    private val client: SupabaseClient,
) : SupportRepository {

    override suspend fun conversationId(userId: String): String {
        existingConversation(userId)?.let { return it }
        return try {
            // Only user_id: the timestamps belong to the database.
            client.from(CONVERSATIONS).insert(buildJsonObject { put("user_id", userId) }) {
                select(Columns.list("id"))
            }.decodeSingle<ConversationIdRow>().id
        } catch (e: PostgrestRestException) {
            // Another device or tab started it a moment ago.
            if (e.code == UNIQUE_VIOLATION) existingConversation(userId) ?: throw e else throw e
        }
    }

    private suspend fun existingConversation(userId: String): String? =
        client.from(CONVERSATIONS).select(Columns.list("id")) {
            filter { eq("user_id", userId) }
            limit(1)
        }.decodeSingleOrNull<ConversationIdRow>()?.id

    override suspend fun isAdminTeam(): Boolean =
        client.postgrest.rpc("is_admin_or_mod").decodeAs<Boolean?>() == true

    override suspend fun history(conversationId: String): List<SupportMessage> =
        client.from(MESSAGES).select(Columns.raw(MESSAGE_COLUMNS)) {
            filter { eq("conversation_id", conversationId) }
            order("created_at", Order.ASCENDING)
        }.decodeList()

    override suspend fun send(
        conversationId: String,
        senderId: String,
        senderIsAdmin: Boolean,
        content: String,
    ): SupportSendOutcome = try {
        val message = client.from(MESSAGES).insert(
            buildJsonObject {
                put("conversation_id", conversationId)
                put("sender_id", senderId)
                put("sender_is_admin", senderIsAdmin)
                put("content", content.trim())
            },
        ) { select(Columns.raw(MESSAGE_COLUMNS)) }.decodeSingle<SupportMessage>()
        SupportSendOutcome.Sent(message)
    } catch (e: PostgrestRestException) {
        supportErrorOutcome(e.code, "${e.error} ${e.message}") ?: throw e
    }

    override fun newMessages(conversationId: String): Flow<SupportMessage> = flow {
        val channel = client.channel("support:$conversationId")
        // Declared before subscribing: the subscription tells the server which
        // changes this channel wants.
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = MESSAGES
            filter("conversation_id", FilterOperator.EQ, conversationId)
        }
        try {
            channel.subscribe()
            emitAll(inserts.map { it.decodeRecord<SupportMessage>() })
        } finally {
            withContext(NonCancellable) { client.realtime.removeChannel(channel) }
        }
    }

    private companion object {
        const val CONVERSATIONS = "admin_conversations"
        const val MESSAGES = "admin_messages"
        const val MESSAGE_COLUMNS = "id, conversation_id, sender_id, sender_is_admin, content, created_at"
        const val UNIQUE_VIOLATION = "23505"
    }
}

/** The refusals a member can cause by sending; anything else is a real error. */
internal fun supportErrorOutcome(code: String?, message: String?): SupportSendOutcome? = when {
    message.orEmpty().contains("RATE_LIMIT_EXCEEDED") -> SupportSendOutcome.RateLimited
    code == "23514" -> SupportSendOutcome.Invalid
    else -> null
}

@Serializable
private data class ConversationIdRow(val id: String)
