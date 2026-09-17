package com.openlibrarykashmir.olk.core.data.repository

import io.github.jan.supabase.SupabaseClient
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One row of `public.notifications`, written by the database triggers rather
 * than by any client (see the web's `20260916142516` and friends).
 */
@Serializable
data class UserNotification(
    val id: String,
    val type: String,
    val title: String,
    val body: String? = null,
    /** A path on the website, e.g. `/requests` or `/books/<id>`; see `notificationDestination`. */
    val link: String? = null,
    // Nullable because the column is (`read boolean DEFAULT false`), so a row
    // could carry an explicit null; treat anything that is not true as unread.
    val read: Boolean? = false,
    @SerialName("created_at") val createdAt: String,
) {
    val isUnread: Boolean get() = read != true
}

interface NotificationsRepository {
    /** The user's notifications, newest first. */
    suspend fun recent(userId: String, limit: Int = DEFAULT_LIMIT): List<UserNotification>

    /** Marks [ids] read. RLS keeps this to the user's own rows. */
    suspend fun markRead(ids: List<String>)

    /**
     * Notifications inserted or changed for [userId] from now on, for as long as
     * the flow is collected. RLS applies to Realtime too, so only this user's
     * rows arrive. Collection subscribes; cancelling removes the channel.
     *
     * UPDATEs matter as well as INSERTs: the same account may be reading
     * notifications on the website at the same time, and that is how this list
     * hears about it.
     */
    fun changes(userId: String): Flow<UserNotification>

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}

internal class SupabaseNotificationsRepository(
    private val client: SupabaseClient,
) : NotificationsRepository {

    override suspend fun recent(userId: String, limit: Int): List<UserNotification> =
        client.from(TABLE).select(Columns.raw(COLUMNS)) {
            filter { eq("user_id", userId) }
            order("created_at", Order.DESCENDING)
            limit(limit.toLong())
        }.decodeList()

    override suspend fun markRead(ids: List<String>) {
        if (ids.isEmpty()) return
        client.from(TABLE).update(buildJsonObject { put("read", true) }) {
            filter { isIn("id", ids) }
        }
    }

    override fun changes(userId: String): Flow<UserNotification> = flow {
        val channel = client.channel("notifications:$userId")
        // The change flow must be declared before subscribing: the subscription
        // tells the server which changes this channel wants.
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = TABLE
            filter("user_id", FilterOperator.EQ, userId)
        }
        try {
            channel.subscribe()
            emitAll(
                changes.mapNotNull { action ->
                    when (action) {
                        is PostgresAction.Insert -> action.decodeRecord<UserNotification>()
                        is PostgresAction.Update -> action.decodeRecord<UserNotification>()
                        // Notifications are never deleted by the app, and a
                        // delete record carries only the primary key anyway.
                        else -> null
                    }
                },
            )
        } finally {
            withContext(NonCancellable) { client.realtime.removeChannel(channel) }
        }
    }

    private companion object {
        const val TABLE = "notifications"
        const val COLUMNS = "id, type, title, body, link, read, created_at"
    }
}
