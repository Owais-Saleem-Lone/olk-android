package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.model.RequestStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestStatusTest {

    // An unknown value fails the whole list's decode, so every status the
    // database's book_requests_status_check allows has to be here.
    @Test
    fun `decodes every status the database allows`() {
        val all = listOf("pending", "accepted", "declined", "handed_over", "returned", "cancelled")
        val decoded = all.map { Json.decodeFromString<RequestStatus>("\"$it\"") }
        assertEquals(RequestStatus.entries.toList(), decoded)
    }
}
