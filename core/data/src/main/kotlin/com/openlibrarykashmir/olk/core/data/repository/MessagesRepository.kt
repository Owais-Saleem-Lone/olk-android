package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.RequestStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ChatMessage(
    val id: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("sender_id") val senderId: String,
    val content: String,
    @SerialName("created_at") val createdAt: String,
)

/** A request chat as the inbox lists it. */
data class Conversation(
    val request: BookRequestItem,
    val lastMessage: ChatMessage?,
)

/** The header of a chat: which book, and who is on the other end. */
data class ChatInfo(
    val requestId: String,
    val bookTitle: String,
    val status: RequestStatus,
    val otherParty: PersonSummary?,
)

sealed interface SendOutcome {
    data class Sent(val message: ChatMessage) : SendOutcome

    /** `enforce_message_rate_limit` (`max_messages_per_hour`, default 30). */
    data object RateLimited : SendOutcome

    /**
     * The insert policy refused it: the request is not (or no longer) in a
     * messaging state, or the content broke `messages_content_length_check`.
     */
    data object NotAllowed : SendOutcome

    /** The sender's account is suspended: no messages until it ends. */
    data object Suspended : SendOutcome
}

interface MessagesRepository {
    /** Chats on requests that reached a messaging state, most recent activity first. */
    suspend fun conversations(userId: String): List<Conversation>

    /** Null when the request does not exist or is not the viewer's. */
    suspend fun chatInfo(requestId: String, viewerId: String): ChatInfo?

    /** Oldest first. */
    suspend fun history(requestId: String): List<ChatMessage>

    suspend fun send(requestId: String, senderId: String, content: String): SendOutcome

    /**
     * Messages inserted into this chat from now on, by either party, for as long
     * as the flow is collected. RLS applies to Realtime too, so only a participant
     * receives anything. Collection subscribes; cancelling removes the channel.
     */
    fun newMessages(requestId: String): Flow<ChatMessage>

    companion object {
        /** Mirrors `messages_content_length_check`. */
        const val MAX_LENGTH = 2000

        /** The statuses the insert policy and the web inbox treat as open for chat. */
        val MESSAGING_STATUSES = setOf(RequestStatus.ACCEPTED, RequestStatus.HANDED_OVER, RequestStatus.RETURNED)
    }
}

internal class SupabaseMessagesRepository(
    private val client: SupabaseClient,
    private val requests: RequestsRepository,
) : MessagesRepository {

    override suspend fun conversations(userId: String): List<Conversation> = coroutineScope {
        val incoming = async { requests.incoming(userId) }
        val outgoing = async { requests.outgoing(userId) }
        val chats = (incoming.await() + outgoing.await())
            .filter { it.status in MessagesRepository.MESSAGING_STATUSES }
        if (chats.isEmpty()) return@coroutineScope emptyList()

        // Newest first across all chats; the first row seen per request is its last
        // message. Same single batch query the web inbox makes.
        val latest = client.from(TABLE).select(Columns.raw(MESSAGE_COLUMNS)) {
            filter { isIn("request_id", chats.map { it.id }) }
            order("created_at", Order.DESCENDING)
        }.decodeList<ChatMessage>().distinctBy { it.requestId }.associateBy { it.requestId }

        chats.map { Conversation(it, latest[it.id]) }
            .sortedByDescending { it.lastMessage?.createdAt ?: it.request.createdAt.orEmpty() }
    }

    override suspend fun chatInfo(requestId: String, viewerId: String): ChatInfo? {
        val row = client.from("book_requests")
            .select(Columns.raw("id, status, requester_id, books!inner(title, owner_id)")) {
                filter { eq("id", requestId) }
            }.decodeSingleOrNull<ChatInfoRow>() ?: return null

        val otherId = if (row.requesterId == viewerId) row.book.ownerId else row.requesterId
        val other = client.from("profiles").select(Columns.raw("id, display_name, area_name")) {
            filter { eq("id", otherId) }
        }.decodeSingleOrNull<ChatProfileRow>()

        return ChatInfo(
            requestId = row.id,
            bookTitle = row.book.title,
            status = row.status,
            otherParty = other?.let { PersonSummary(it.id, it.displayName, it.areaName) },
        )
    }

    override suspend fun history(requestId: String): List<ChatMessage> =
        client.from(TABLE).select(Columns.raw(MESSAGE_COLUMNS)) {
            filter { eq("request_id", requestId) }
            order("created_at", Order.ASCENDING)
        }.decodeList()

    override suspend fun send(requestId: String, senderId: String, content: String): SendOutcome =
        try {
            val message = client.from(TABLE).insert(
                buildJsonObject {
                    put("request_id", requestId)
                    put("sender_id", senderId)
                    put("content", content)
                },
            ) { select(Columns.raw(MESSAGE_COLUMNS)) }.decodeSingle<ChatMessage>()
            SendOutcome.Sent(message)
        } catch (e: PostgrestRestException) {
            when {
                e.error.startsWith(RATE_LIMIT_PREFIX) || e.message.orEmpty().contains(RATE_LIMIT_PREFIX) ->
                    SendOutcome.RateLimited
                e.code == RLS_VIOLATION && client.refusedBecauseSuspended() -> SendOutcome.Suspended
                e.code == RLS_VIOLATION || e.code == CHECK_VIOLATION -> SendOutcome.NotAllowed
                else -> throw e
            }
        }

    override fun newMessages(requestId: String): Flow<ChatMessage> = flow {
        val channel = client.channel("chat:$requestId")
        // The change flow must be declared before subscribing: the subscription
        // tells the server which changes this channel wants.
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = TABLE
            filter("request_id", FilterOperator.EQ, requestId)
        }
        try {
            channel.subscribe()
            emitAll(inserts.map { it.decodeRecord<ChatMessage>() })
        } finally {
            withContext(NonCancellable) { client.realtime.removeChannel(channel) }
        }
    }

    private companion object {
        const val TABLE = "messages"
        const val MESSAGE_COLUMNS = "id, request_id, sender_id, content, created_at"
        const val RATE_LIMIT_PREFIX = "RATE_LIMIT_EXCEEDED"
        const val RLS_VIOLATION = "42501"
        const val CHECK_VIOLATION = "23514"
    }
}

@Serializable
private data class ChatInfoRow(
    val id: String,
    val status: RequestStatus,
    @SerialName("requester_id") val requesterId: String,
    @SerialName("books") val book: ChatBookRow,
)

@Serializable
private data class ChatBookRow(
    val title: String,
    @SerialName("owner_id") val ownerId: String,
)

@Serializable
private data class ChatProfileRow(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
)
