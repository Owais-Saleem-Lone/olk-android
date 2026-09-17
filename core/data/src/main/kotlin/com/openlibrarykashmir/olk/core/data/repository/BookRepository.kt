package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.Book
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order

interface BookRepository {
    /**
     * Books currently on offer, newest first.
     *
     * @param limit page size.
     * @param offset rows to skip; pair with [limit] for pagination.
     * @param query optional case-insensitive match against title or author.
     */
    suspend fun browse(limit: Int = PAGE_SIZE, offset: Int = 0, query: String? = null): List<Book>

    suspend fun byId(id: String): Book?

    companion object {
        const val PAGE_SIZE = 20
    }
}

internal class SupabaseBookRepository(
    private val client: SupabaseClient,
) : BookRepository {

    override suspend fun browse(limit: Int, offset: Int, query: String?): List<Book> =
        client.from(TABLE).select(COLUMNS) {
            filter {
                eq("status", "available")
                if (!query.isNullOrBlank()) {
                    val term = "%${query.trim()}%"
                    or {
                        ilike("title", term)
                        ilike("author", term)
                    }
                }
            }
            order("created_at", Order.DESCENDING)
            range(offset.toLong(), (offset + limit - 1).toLong())
        }.decodeList()

    override suspend fun byId(id: String): Book? =
        client.from(TABLE).select(COLUMNS) {
            filter { eq("id", id) }
            limit(1)
        }.decodeSingleOrNull()

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
