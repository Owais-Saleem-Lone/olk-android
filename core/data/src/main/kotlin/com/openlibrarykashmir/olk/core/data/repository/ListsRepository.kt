package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.ListingType
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A book the user bookmarked. */
data class SavedBook(
    val bookmarkId: String,
    val bookId: String,
    val title: String,
    val author: String?,
    val listingType: ListingType,
    val status: BookStatus,
    val coverUrl: String?,
)

/** A book the user is looking for. [matchedBookId] is set by the database when someone lists it. */
@Serializable
data class WishlistItem(
    val id: String,
    val title: String,
    val author: String? = null,
    @SerialName("matched_book_id") val matchedBookId: String? = null,
)

sealed interface AddWishOutcome {
    data object Added : AddWishOutcome
    data object AlreadyOnList : AddWishOutcome
    data object ListFull : AddWishOutcome
}

/** The user's own Saved books and Wishlist; RLS limits both tables to their own rows. */
interface ListsRepository {
    suspend fun savedBooks(userId: String): List<SavedBook>

    suspend fun removeSaved(bookmarkId: String)

    suspend fun wishlist(userId: String): List<WishlistItem>

    suspend fun addWish(userId: String, title: String, author: String?): AddWishOutcome

    suspend fun removeWish(id: String)

    companion object {
        /** Same limit as the database's `wishlists_title_length` / `wishlists_author_length`. */
        const val MAX_WISH_LENGTH = 500
    }
}

internal class SupabaseListsRepository(
    private val client: SupabaseClient,
) : ListsRepository {

    override suspend fun savedBooks(userId: String): List<SavedBook> =
        client.from("bookmarks").select(
            Columns.raw("id, book_id, books(title, author, listing_type, status, cover_url)"),
        ) {
            filter { eq("user_id", userId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<BookmarkRow>().mapNotNull { row ->
            // A book an admin has since hidden comes back as null (RLS): skip it,
            // as the website does.
            val book = row.book ?: return@mapNotNull null
            SavedBook(
                bookmarkId = row.id,
                bookId = row.bookId,
                title = book.title,
                author = book.author,
                listingType = book.listingType,
                status = book.status,
                coverUrl = book.coverUrl,
            )
        }

    override suspend fun removeSaved(bookmarkId: String) {
        client.from("bookmarks").delete { filter { eq("id", bookmarkId) } }
    }

    override suspend fun wishlist(userId: String): List<WishlistItem> =
        client.from("wishlists").select(Columns.list("id", "title", "author", "matched_book_id")) {
            filter { eq("user_id", userId) }
            order("created_at", Order.DESCENDING)
        }.decodeList()

    override suspend fun addWish(userId: String, title: String, author: String?): AddWishOutcome =
        try {
            client.from("wishlists").insert(
                buildJsonObject {
                    put("user_id", userId)
                    put("title", title.trim())
                    author?.trim()?.takeIf { it.isNotEmpty() }?.let { put("author", it) }
                },
            )
            AddWishOutcome.Added
        } catch (e: PostgrestRestException) {
            wishErrorOutcome(e.code, "${e.error} ${e.message}") ?: throw e
        }

    override suspend fun removeWish(id: String) {
        client.from("wishlists").delete { filter { eq("id", id) } }
    }
}

/** Maps the two refusals a user can cause by adding a wish; anything else is a real error. */
internal fun wishErrorOutcome(code: String?, message: String?): AddWishOutcome? = when {
    code == "23505" -> AddWishOutcome.AlreadyOnList
    message.orEmpty().contains("WISHLIST_LIMIT_REACHED") -> AddWishOutcome.ListFull
    else -> null
}

@Serializable
private data class BookmarkRow(
    val id: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("books") val book: BookmarkedBook? = null,
)

@Serializable
private data class BookmarkedBook(
    val title: String,
    val author: String? = null,
    @SerialName("listing_type") val listingType: ListingType,
    val status: BookStatus = BookStatus.AVAILABLE,
    @SerialName("cover_url") val coverUrl: String? = null,
)
