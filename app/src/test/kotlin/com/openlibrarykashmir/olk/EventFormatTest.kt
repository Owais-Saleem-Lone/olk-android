package com.openlibrarykashmir.olk

import com.openlibrarykashmir.olk.feature.events.eventHasEnded
import com.openlibrarykashmir.olk.feature.events.eventMillis
import com.openlibrarykashmir.olk.feature.events.eventPlace
import com.openlibrarykashmir.olk.feature.events.eventWhenLong
import com.openlibrarykashmir.olk.feature.events.eventWhenShort
import com.openlibrarykashmir.olk.feature.events.safeMeetingUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class EventFormatTest {

    private val kashmir = ZoneId.of("Asia/Kolkata")

    @Test
    fun `times are shown in the reader's own zone`() {
        // 12:30 UTC is 18:00 in Srinagar.
        assertEquals(
            "Sat 3 Oct, 6:00 PM",
            eventWhenShort("2026-10-03T12:30:00+00:00", kashmir, Locale.US).replace(' ', ' '),
        )
    }

    @Test
    fun `an end on the same day shows only its time`() {
        assertEquals(
            "Saturday 3 October, 6:00 PM – 8:00 PM",
            eventWhenLong("2026-10-03T12:30:00+00:00", "2026-10-03T14:30:00+00:00", kashmir, Locale.US)
                .replace(' ', ' '),
        )
    }

    @Test
    fun `an end on another day repeats its date`() {
        assertEquals(
            "Saturday 3 October, 10:00 PM – Sunday 4 October, 1:00 AM",
            eventWhenLong("2026-10-03T16:30:00+00:00", "2026-10-03T19:30:00+00:00", kashmir, Locale.US)
                .replace(' ', ' '),
        )
    }

    @Test
    fun `no end, no dash`() {
        assertEquals(
            "Saturday 3 October, 6:00 PM",
            eventWhenLong("2026-10-03T12:30:00+00:00", null, kashmir, Locale.US).replace(' ', ' '),
        )
    }

    @Test
    fun `the place uses the website's wording for the gaps`() {
        assertEquals("Online", eventPlace(isOnline = true, locationName = "ignored"))
        assertEquals("Cafe Fiction", eventPlace(isOnline = false, locationName = " Cafe Fiction "))
        assertEquals("Location TBA", eventPlace(isOnline = false, locationName = "  "))
    }

    @Test
    fun `an event has ended once its end, or else its start, is past`() {
        val now = Instant.parse("2026-10-03T13:00:00Z")
        assertTrue(eventHasEnded("2026-10-03T12:30:00+00:00", null, now))
        assertFalse(eventHasEnded("2026-10-03T12:30:00+00:00", "2026-10-03T14:30:00+00:00", now))
        assertFalse(eventHasEnded("2026-10-04T12:30:00+00:00", null, now))
        // Postgres' text form, as Realtime would carry it.
        assertTrue(eventHasEnded("2026-10-03 12:30:00+00", null, now))
    }

    @Test
    fun `only web links are ever opened`() {
        assertEquals("https://meet.google.com/abc", safeMeetingUrl(" https://meet.google.com/abc "))
        assertEquals("HTTP://example.com", safeMeetingUrl("HTTP://example.com"))
        assertNull(safeMeetingUrl("intent://scan#Intent;scheme=zxing;end"))
        assertNull(safeMeetingUrl("javascript:alert(1)"))
        assertNull(safeMeetingUrl("file:///sdcard/secret"))
        assertNull(safeMeetingUrl("meet.google.com/abc"))
        assertNull(safeMeetingUrl(null))
    }

    @Test
    fun `calendar times are epoch milliseconds`() {
        assertEquals(1_790_000_000_000L, eventMillis("2026-09-21T14:13:20+00:00"))
        assertNull(eventMillis(null))
        assertNull(eventMillis("not a date"))
    }
}
