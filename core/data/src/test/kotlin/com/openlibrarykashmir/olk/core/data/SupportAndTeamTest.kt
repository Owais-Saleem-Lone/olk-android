package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.repository.SupportSendOutcome
import com.openlibrarykashmir.olk.core.data.repository.TeamApplicationOutcome
import com.openlibrarykashmir.olk.core.data.repository.TeamApplicationRules
import com.openlibrarykashmir.olk.core.data.repository.safeFileName
import com.openlibrarykashmir.olk.core.data.repository.supportErrorOutcome
import com.openlibrarykashmir.olk.core.data.repository.teamApplicationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportAndTeamTest {

    // ── Support chat ──

    @Test
    fun `the hourly limit and the length check are outcomes, anything else is an error`() {
        assertEquals(
            SupportSendOutcome.RateLimited,
            supportErrorOutcome("P0001", "null RATE_LIMIT_EXCEEDED: max 30 messages per hour reached"),
        )
        assertEquals(SupportSendOutcome.Invalid, supportErrorOutcome("23514", "admin_messages_content_length"))
        // Posting into someone else's thread, or claiming the admin badge.
        assertNull(supportErrorOutcome("42501", "new row violates row-level security policy"))
    }

    // ── Team applications ──

    @Test
    fun `only a JSON success counts as submitted`() {
        assertEquals(TeamApplicationOutcome.Submitted, teamApplicationOutcome(200, """{"success":true}"""))
        // A maintenance redirect answers with a web page, not JSON.
        assertEquals(
            TeamApplicationOutcome.Refused("The website could not take your application just now. Please try again later."),
            teamApplicationOutcome(307, null),
        )
        assertEquals(
            TeamApplicationOutcome.Refused("The website could not take your application just now. Please try again later."),
            teamApplicationOutcome(200, "not json"),
        )
    }

    @Test
    fun `the website's own reason is shown when it refuses`() {
        assertEquals(
            TeamApplicationOutcome.Refused("You have already applied recently. We will be in touch at the email you gave."),
            teamApplicationOutcome(
                429,
                """{"error":"You have already applied recently. We will be in touch at the email you gave."}""",
            ),
        )
        assertEquals(
            TeamApplicationOutcome.Refused("Please log in or create an account to apply."),
            teamApplicationOutcome(401, """{"error":"Please log in or create an account to apply."}"""),
        )
    }

    @Test
    fun `words are counted exactly as the website counts them`() {
        assertEquals(0, TeamApplicationRules.wordCount("   "))
        assertEquals(3, TeamApplicationRules.wordCount("  one\ttwo\n\nthree "))
    }

    @Test
    fun `a file name cannot break out of its header`() {
        assertEquals("my_cv_.pdf", safeFileName("my\"cv\n.pdf"))
        assertEquals("cv", safeFileName(""))
        assertEquals("Résumé 2026.docx", safeFileName("Résumé 2026.docx"))
    }
}
