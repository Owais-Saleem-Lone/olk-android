package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.ListingType
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CommunityStats(
    @SerialName("total_books") val totalBooks: Long = 0,
    @SerialName("total_users") val totalUsers: Long = 0,
    @SerialName("completed_exchanges") val completedExchanges: Long = 0,
)

@Serializable
data class BookOfMonth(
    val title: String,
    val author: String? = null,
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("month_label") val monthLabel: String? = null,
)

@Serializable
data class HomeBook(
    val id: String,
    val title: String,
    val author: String? = null,
    @SerialName("listing_type") val listingType: ListingType,
    val status: BookStatus = BookStatus.AVAILABLE,
    @SerialName("cover_url") val coverUrl: String? = null,
)

/** "Someone listed X in Y, 5m ago", from the newest books. */
data class ActivityItem(
    val bookId: String,
    val title: String,
    val listingType: ListingType,
    val createdAt: String,
    val ownerName: String?,
    val ownerArea: String?,
)

@Serializable
data class Announcement(
    val id: String,
    val title: String,
    val body: String? = null,
    val type: String = "info",
)

/** Everything on Home. A section whose query failed is empty rather than failing the page. */
data class HomeFeed(
    val announcements: List<Announcement> = emptyList(),
    val stats: CommunityStats? = null,
    val bookOfMonth: BookOfMonth? = null,
    val recentBooks: List<HomeBook> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
)

interface HomeRepository {
    /**
     * The website homepage's data (`src/app/page.tsx`), fetched in parallel.
     * Throws only when every section failed, which means there is no connection.
     */
    suspend fun feed(): HomeFeed
}

internal class SupabaseHomeRepository(
    private val client: SupabaseClient,
) : HomeRepository {

    override suspend fun feed(): HomeFeed = coroutineScope {
        val announcements = async { runCatching { announcements() } }
        val stats = async { runCatching { stats() } }
        val bookOfMonth = async { runCatching { bookOfMonth() } }
        val recent = async { runCatching { recentBooks() } }
        val activity = async { runCatching { activity() } }

        val results = listOf(announcements, stats, bookOfMonth, recent, activity).map { it.await() }
        results.firstOrNull { it.isSuccess } ?: throw results.first().exceptionOrNull()!!

        HomeFeed(
            announcements = announcements.await().getOrDefault(emptyList()),
            stats = stats.await().getOrNull(),
            bookOfMonth = bookOfMonth.await().getOrNull(),
            recentBooks = recent.await().getOrDefault(emptyList()),
            activity = activity.await().getOrDefault(emptyList()),
        )
    }

    // RLS already limits these to active ones inside their date window.
    private suspend fun announcements(): List<Announcement> =
        client.from("announcements").select(Columns.list("id", "title", "body", "type")) {
            filter {
                eq("active", true)
                eq("is_banner", true)
            }
            order("created_at", Order.DESCENDING)
            limit(3)
        }.decodeList()

    private suspend fun stats(): CommunityStats? =
        client.postgrest.rpc("get_community_stats").decodeList<CommunityStats>().firstOrNull()

    private suspend fun bookOfMonth(): BookOfMonth? =
        client.from("book_of_month").select(Columns.list("title", "author", "description", "cover_url", "month_label")) {
            filter { eq("active", true) }
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeSingleOrNull()

    private suspend fun recentBooks(): List<HomeBook> =
        client.from("books").select(Columns.list("id", "title", "author", "listing_type", "status", "cover_url")) {
            filter { isIn("status", listOf("available", "given")) }
            order("created_at", Order.DESCENDING)
            limit(RECENT_BOOKS)
        }.decodeList()

    // books.owner_id references auth.users, not profiles, so PostgREST cannot embed
    // the owner; fetch the handful of owners' public columns in a second query.
    private suspend fun activity(): List<ActivityItem> {
        val books = client.from("books").select(Columns.list("id", "title", "listing_type", "owner_id", "created_at")) {
            order("created_at", Order.DESCENDING)
            limit(ACTIVITY_ITEMS)
        }.decodeList<ActivityRow>()
        val ownerIds = books.map { it.ownerId }.distinct()
        val owners = if (ownerIds.isEmpty()) {
            emptyMap()
        } else {
            client.from("profiles").select(Columns.list("id", "display_name", "area_name")) {
                filter { isIn("id", ownerIds) }
            }.decodeList<ActivityOwner>().associateBy { it.id }
        }
        return books.map { row ->
            val owner = owners[row.ownerId]
            ActivityItem(
                bookId = row.id,
                title = row.title,
                listingType = row.listingType,
                createdAt = row.createdAt,
                ownerName = owner?.displayName,
                ownerArea = owner?.areaName,
            )
        }
    }

    private companion object {
        const val RECENT_BOOKS = 4L
        const val ACTIVITY_ITEMS = 5L
    }
}

@Serializable
private data class ActivityRow(
    val id: String,
    val title: String,
    @SerialName("listing_type") val listingType: ListingType,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class ActivityOwner(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
)
