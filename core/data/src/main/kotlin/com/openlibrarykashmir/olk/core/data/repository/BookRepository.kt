package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.BookCondition
import com.openlibrarykashmir.olk.core.data.model.BrowseBook
import com.openlibrarykashmir.olk.core.data.model.BrowseFilters
import com.openlibrarykashmir.olk.core.data.model.ListingType
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface BookRepository {
    /**
     * Books on the Browse page, via the `browse_books()` RPC: nearest first when the
     * signed-in user has a saved location, newest first otherwise. Search, filters and
     * paging all happen in the database, and distance is measured there from the
     * user's own saved location -- the app never sends or receives coordinates.
     *
     * @param limit page size.
     * @param offset rows to skip; pair with [limit] for pagination.
     */
    suspend fun browse(filters: BrowseFilters, limit: Int = PAGE_SIZE, offset: Int = 0): List<BrowseBook>

    suspend fun byId(id: String): Book?

    /** Active genres, for the Browse genre filter. */
    suspend fun genres(): List<String>

    /** Whether the signed-in user has shared a location, i.e. whether the distance filter can work. */
    suspend fun hasSavedLocation(): Boolean

    companion object {
        const val PAGE_SIZE = 20
    }
}

internal class SupabaseBookRepository(
    private val client: SupabaseClient,
) : BookRepository {

    override suspend fun browse(filters: BrowseFilters, limit: Int, offset: Int): List<BrowseBook> =
        client.postgrest.rpc("browse_books", browseParams(filters, limit, offset)).decodeList()

    override suspend fun byId(id: String): Book? =
        client.from(TABLE).select(COLUMNS) {
            filter { eq("id", id) }
            limit(1)
        }.decodeSingleOrNull()

    override suspend fun genres(): List<String> = client.activeGenres()

    override suspend fun hasSavedLocation(): Boolean {
        val userId = client.auth.currentUserOrNull()?.id ?: return false
        // Filtered by id even though RLS allows only your own row: admins may read every row.
        return client.from("profile_locations").select(Columns.list("user_id")) {
            filter { eq("user_id", userId) }
            limit(1)
        }.decodeList<UserIdRow>().isNotEmpty()
    }

    internal companion object {
        const val TABLE = "books"

        /**
         * Selected explicitly rather than with `*`. Two reasons: the DTO stays honest
         * about what it maps, and adding a column to the table cannot silently widen
         * what the app pulls over a metered connection.
         */
        val COLUMNS = Columns.list(
            "id",
            "owner_id",
            "title",
            "author",
            "condition",
            "listing_type",
            "status",
            "genre",
            "cover_url",
            "description",
            "publication_year",
            "lending_duration_months",
            "read_count",
            "featured",
            "acquired_via_donation",
            "created_at",
        )
    }
}

/** Only set filters are sent; the RPC's defaults mean "no filter" for the rest. */
internal fun browseParams(filters: BrowseFilters, limit: Int, offset: Int): JsonObject = buildJsonObject {
    filters.query.trim().takeIf { it.isNotEmpty() }?.let { put("p_query", it) }
    filters.genre?.let { put("p_genre", it) }
    filters.listingType?.let { put("p_listing_type", Json.encodeToJsonElement(ListingType.serializer(), it)) }
    filters.condition?.let { put("p_condition", Json.encodeToJsonElement(BookCondition.serializer(), it)) }
    filters.area.trim().takeIf { it.isNotEmpty() }?.let { put("p_area", it) }
    filters.radiusKm?.let { put("p_radius_km", it) }
    put("p_limit", limit)
    put("p_offset", offset)
}

@Serializable
private data class UserIdRow(@SerialName("user_id") val userId: String)

