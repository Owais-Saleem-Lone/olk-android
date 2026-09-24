package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.model.ClubRequestOutcome
import com.openlibrarykashmir.olk.core.data.model.CreateEventOutcome
import com.openlibrarykashmir.olk.core.data.model.OrganiserRules
import com.openlibrarykashmir.olk.core.data.repository.clubRequestErrorOutcome
import com.openlibrarykashmir.olk.core.data.repository.eventErrorOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors web migration 20260924134533 (F6) and the website's club-limits.ts. */
class OrganiserRulesTest {

    @Test
    fun `cover links must be https, meeting links http or https`() {
        assertTrue(OrganiserRules.isHttpsUrl("https://example.com/a.jpg"))
        assertFalse(OrganiserRules.isHttpsUrl("http://example.com/a.jpg"))
        assertFalse(OrganiserRules.isHttpsUrl("javascript:alert(1)"))
        assertTrue(OrganiserRules.isHttpUrl("http://example.com"))
        assertTrue(OrganiserRules.isHttpUrl("https://meet.google.com/abc"))
        assertFalse(OrganiserRules.isHttpUrl("javascript:alert(1)"))
        assertFalse(OrganiserRules.isHttpUrl("meet.google.com/abc"))
    }

    @Test
    fun `club request refusals map to what the member can do`() {
        assertEquals(
            ClubRequestOutcome.AlreadyPending,
            clubRequestErrorOutcome("23505", "duplicate key value violates unique constraint \"club_requests_one_pending_per_member\""),
        )
        assertEquals(
            ClubRequestOutcome.NotEligible("Not eligible to request a club: requires 5 completed exchanges (you have 2)"),
            clubRequestErrorOutcome("P0001", "Not eligible to request a club: requires 5 completed exchanges (you have 2)"),
        )
        assertEquals(ClubRequestOutcome.Invalid, clubRequestErrorOutcome("P0001", "CLUB_INTEREST_INVALID: pick at least one interest"))
        assertEquals(ClubRequestOutcome.Invalid, clubRequestErrorOutcome("23514", "violates check constraint \"club_requests_description_length\""))
        assertEquals(null, clubRequestErrorOutcome("XX000", "something else"))
    }

    @Test
    fun `event refusals map to what the organiser can do`() {
        assertEquals(
            CreateEventOutcome.MonthlyLimitReached,
            eventErrorOutcome("P0001", "RATE_LIMIT_EXCEEDED: max 1 events per 30 days reached for this club"),
        )
        assertEquals(
            CreateEventOutcome.CapacityTooHigh,
            eventErrorOutcome("P0001", "RATE_LIMIT_EXCEEDED: event capacity cannot exceed 10 participants on the current plan"),
        )
        assertEquals(CreateEventOutcome.StartsInPast, eventErrorOutcome("P0001", "EVENT_IN_PAST: an event cannot start in the past"))
        assertEquals(CreateEventOutcome.NotAllowed, eventErrorOutcome("42501", "new row violates row-level security policy"))
        assertEquals(CreateEventOutcome.Invalid, eventErrorOutcome("23514", "violates check constraint \"club_events_meeting_url_http\""))
        assertEquals(null, eventErrorOutcome("XX000", "something else"))
    }
}
