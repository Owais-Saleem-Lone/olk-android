package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.OwnerProfileRow
import com.openlibrarykashmir.olk.core.data.model.OwnerSummary
import com.openlibrarykashmir.olk.core.data.model.ScoreRow
import com.openlibrarykashmir.olk.core.data.model.StatusRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.roundToInt

/** Another reader's public page, as on the website's `/user/<id>`. */
data class PublicProfile(
    val person: OwnerSummary,
    val availableBooks: List<Book>,
)

interface PeopleRepository {
    /** Null when there is no such profile. */
    suspend fun profile(userId: String): PublicProfile?
}

internal class SupabasePeopleRepository(
    private val client: SupabaseClient,
) : PeopleRepository {

    override suspend fun profile(userId: String): PublicProfile? = coroutineScope {
        val person = async { client.ownerSummary(userId) }
        val available = async {
            client.from(SupabaseBookRepository.TABLE).select(SupabaseBookRepository.COLUMNS) {
                filter {
                    eq("owner_id", userId)
                    eq("status", "available")
                }
                order("created_at", Order.DESCENDING)
                limit(MAX_BOOKS)
            }.decodeList<Book>()
        }
        person.await()?.let { PublicProfile(it, available.await()) }
    }

    private companion object {
        const val MAX_BOOKS = 50L
    }
}

/**
 * Name, area, bio, join date, book counts and rating for one person, using only
 * columns any signed-in user may read. Shared by the book page's owner card and
 * the public profile. Null when the profile does not exist.
 */
internal suspend fun SupabaseClient.ownerSummary(userId: String): OwnerSummary? = coroutineScope {
    val profile = async {
        from("profiles").select(Columns.list("id", "display_name", "area_name", "bio", "created_at")) {
            filter { eq("id", userId) }
        }.decodeSingleOrNull<OwnerProfileRow>()
    }
    val statuses = async {
        from("books").select(Columns.list("status")) {
            filter { eq("owner_id", userId) }
        }.decodeList<StatusRow>()
    }
    val scores = async {
        from("ratings").select(Columns.list("score")) {
            filter { eq("rated_user_id", userId) }
        }.decodeList<ScoreRow>().map { it.score }
    }

    val row = profile.await() ?: return@coroutineScope null
    val ownerStatuses = statuses.await()
    val ratingScores = scores.await()
    OwnerSummary(
        id = row.id,
        displayName = row.displayName,
        areaName = row.areaName,
        bio = row.bio,
        joinedAt = row.createdAt,
        booksListed = ownerStatuses.size,
        booksAvailable = ownerStatuses.count { it.status == BookStatus.AVAILABLE },
        booksShared = ownerStatuses.count { it.status == BookStatus.GIVEN },
        // One decimal place, matching the web page's rounding.
        ratingAverage = ratingScores.takeIf { it.isNotEmpty() }?.let { (it.average() * 10).roundToInt() / 10.0 },
        ratingCount = ratingScores.size,
    )
}
