package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.repository.DeleteAccountOutcome
import com.openlibrarykashmir.olk.core.data.repository.DeletionBlocker
import com.openlibrarykashmir.olk.core.data.repository.deleteAccountOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionTest {

    @Test
    fun `only a real deleted answer counts`() {
        assertEquals(DeleteAccountOutcome.Deleted, deleteAccountOutcome(200, """{"deleted":true}"""))
    }

    @Test
    fun `an HTML page is never a deletion`() {
        // What a maintenance redirect returns: 200, but no JSON body at all.
        val outcome = deleteAccountOutcome(200, null)
        assertTrue(outcome is DeleteAccountOutcome.Refused)
    }

    @Test
    fun `blockers come back as the reasons the screen explains`() {
        val outcome = deleteAccountOutcome(409, """{"error":"blocked","blockers":["unfinished_exchange","owns_club"]}""")
        assertEquals(
            DeleteAccountOutcome.Blocked(listOf(DeletionBlocker.UNFINISHED_EXCHANGE, DeletionBlocker.OWNS_CLUB)),
            outcome,
        )
    }

    @Test
    fun `a blocker the app does not know about is ignored, not shown raw`() {
        val outcome = deleteAccountOutcome(409, """{"error":"blocked","blockers":["something_new"]}""")
        assertTrue(outcome is DeleteAccountOutcome.Refused)
        assertEquals(
            "Your account could not be deleted just now. Please try again later.",
            (outcome as DeleteAccountOutcome.Refused).message,
        )
    }

    @Test
    fun `the website's own words are shown when it refuses`() {
        val outcome = deleteAccountOutcome(429, """{"error":"Too many attempts. Please try again later."}""")
        assertEquals("Too many attempts. Please try again later.", (outcome as DeleteAccountOutcome.Refused).message)
    }
}
