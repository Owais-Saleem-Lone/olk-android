package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.repository.RsvpOutcome
import com.openlibrarykashmir.olk.core.data.repository.browseEventParams
import com.openlibrarykashmir.olk.core.data.repository.rsvpErrorOutcome
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventParamsTest {

    @Test
    fun `no query sends only paging, so the RPC defaults apply`() {
        assertEquals(
            """{"p_limit":20,"p_offset":40}""",
            browseEventParams(query = "   ", limit = 20, offset = 40).toString(),
        )
    }

    @Test
    fun `the query is trimmed and never comes with coordinates`() {
        assertEquals(
            Json.parseToJsonElement("""{"p_query":"chess","p_limit":20,"p_offset":0}"""),
            browseEventParams(query = "  chess ", limit = 20, offset = 0),
        )
    }

    @Test
    fun `the capacity trigger's refusal means full`() {
        assertEquals(
            RsvpOutcome.Full,
            rsvpErrorOutcome("P0001 null EVENT_FULL: this event has no places left"),
        )
    }

    @Test
    fun `the unique key's refusal means already going`() {
        assertEquals(
            RsvpOutcome.AlreadyGoing,
            rsvpErrorOutcome("23505 null duplicate key value violates unique constraint \"event_rsvps_event_id_user_id_key\""),
        )
    }

    @Test
    fun `an RLS refusal is a real error, not an outcome`() {
        assertNull(rsvpErrorOutcome("42501 null new row violates row-level security policy for table \"event_rsvps\""))
        assertNull(rsvpErrorOutcome(null))
    }
}
