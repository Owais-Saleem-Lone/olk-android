package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.repository.AddWishOutcome
import com.openlibrarykashmir.olk.core.data.repository.wishErrorOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class WishErrorOutcomeTest {

    @Test
    fun `the unique title index means it is already on the list`() {
        assertEquals(AddWishOutcome.AlreadyOnList, wishErrorOutcome("23505", "duplicate key value"))
    }

    @Test
    fun `the database's limit trigger means the list is full`() {
        assertEquals(
            AddWishOutcome.ListFull,
            wishErrorOutcome("23514", "WISHLIST_LIMIT_REACHED: at most 100 books on a wishlist"),
        )
    }

    @Test
    fun `any other refusal stays an error`() {
        assertEquals(null, wishErrorOutcome("23514", "violates check constraint \"wishlists_title_length\""))
        assertEquals(null, wishErrorOutcome("42501", "permission denied"))
    }
}
