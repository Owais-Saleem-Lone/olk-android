package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.model.AcquiredVia
import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.BookDetail
import com.openlibrarykashmir.olk.core.data.model.OwnershipEntry
import com.openlibrarykashmir.olk.core.data.model.OwnershipRow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnershipHistoryTest {

    // Shaped like PostgREST's answer to the book page's history query.
    private val response = """
        [
          {"owner_id": null, "acquired_via": "listed", "acquired_at": "2026-08-01T10:00:00+00:00",
           "relinquished_at": "2026-09-01T10:00:00+00:00", "profiles": null},
          {"owner_id": "u2", "acquired_via": "donation_received", "acquired_at": "2026-09-01T10:00:00+00:00",
           "relinquished_at": null, "profiles": {"display_name": "Aman"}}
        ]
    """.trimIndent()

    @Test
    fun `decodes the history, including an owner who deleted their account`() {
        val entries = Json.decodeFromString<List<OwnershipRow>>(response).map { it.toEntry() }
        assertEquals(
            listOf(
                OwnershipEntry(null, null, AcquiredVia.LISTED, "2026-08-01T10:00:00+00:00", "2026-09-01T10:00:00+00:00"),
                OwnershipEntry("u2", "Aman", AcquiredVia.DONATION_RECEIVED, "2026-09-01T10:00:00+00:00", null),
            ),
            entries,
        )
    }

    @Test
    fun `the journey shows only once the book has changed hands`() {
        val entries = Json.decodeFromString<List<OwnershipRow>>(response).map { it.toEntry() }
        assertFalse(detail(entries.take(1)).hasChangedHands)
        assertTrue(detail(entries).hasChangedHands)
    }

    private fun detail(history: List<OwnershipEntry>) = BookDetail(
        book = Json.decodeFromString<Book>(
            """{"id": "b1", "owner_id": "u2", "title": "T", "listing_type": "donate", "status": "available"}""",
        ),
        owner = null,
        isOwnBook = false,
        myRequestStatus = null,
        isSaved = false,
        ownershipHistory = history,
    )
}
