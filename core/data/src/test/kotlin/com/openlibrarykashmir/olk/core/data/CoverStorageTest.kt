package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.model.ClubRequestOutcome
import com.openlibrarykashmir.olk.core.data.model.CoverBucket
import com.openlibrarykashmir.olk.core.data.repository.AddBookOutcome
import com.openlibrarykashmir.olk.core.data.repository.addBookErrorOutcome
import com.openlibrarykashmir.olk.core.data.repository.clubRequestErrorOutcome
import com.openlibrarykashmir.olk.core.data.repository.coverStoragePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverStorageTest {
    private val base = "https://abc.supabase.co/storage/v1/object/public"

    @Test
    fun `a public URL maps back to its path inside the bucket`() {
        assertEquals("u1/123.webp", coverStoragePath("$base/book-covers/u1/123.webp", CoverBucket.BOOKS))
        assertEquals("u1/9.webp", coverStoragePath("http://10.0.2.2:54321/storage/v1/object/public/club-covers/u1/9.webp?t=1", CoverBucket.CLUBS))
    }

    @Test
    fun `pasted links, other buckets and empty values are never ours`() {
        assertNull(coverStoragePath("https://covers.openlibrary.org/b/id/1-L.jpg", CoverBucket.BOOKS))
        assertNull(coverStoragePath("$base/event-covers/u1/1.webp", CoverBucket.BOOKS))
        assertNull(coverStoragePath(null, CoverBucket.BOOKS))
        assertNull(coverStoragePath("$base/book-covers/", CoverBucket.BOOKS))
    }

    @Test
    fun `the listing limits map to their outcomes`() {
        assertEquals(AddBookOutcome.BookLimitReached(25), addBookErrorOutcome("BOOK_LIMIT_REACHED: max 25 books per member"))
        assertEquals(AddBookOutcome.DailyLimitReached, addBookErrorOutcome("RATE_LIMIT_EXCEEDED: max 20 books per day reached"))
        assertNull(addBookErrorOutcome("something else"))
    }

    @Test
    fun `a second club is refused as already running one`() {
        assertEquals(
            ClubRequestOutcome.AlreadyRunsClub,
            clubRequestErrorOutcome("P0001", "CLUB_LIMIT_REACHED: a member can run one club"),
        )
    }
}
