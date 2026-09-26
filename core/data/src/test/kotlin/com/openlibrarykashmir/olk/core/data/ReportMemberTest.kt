package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.model.ReportOutcome
import com.openlibrarykashmir.olk.core.data.repository.reportErrorOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportMemberTest {

    @Test
    fun `an open report and the daily limit are outcomes, anything else is an error`() {
        assertEquals(ReportOutcome.AlreadyReported, reportErrorOutcome("23505", "duplicate key value"))
        assertEquals(
            ReportOutcome.DailyLimitReached,
            reportErrorOutcome("P0001", "RATE_LIMIT_EXCEEDED: too many reports today"),
        )
        assertNull(reportErrorOutcome("42501", "You cannot report yourself"))
    }
}
