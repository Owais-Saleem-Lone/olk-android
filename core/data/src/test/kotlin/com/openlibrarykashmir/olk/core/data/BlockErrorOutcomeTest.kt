package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.repository.BlockOutcome
import com.openlibrarykashmir.olk.core.data.repository.blockErrorOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockErrorOutcomeTest {
    @Test
    fun `a repeat block is already done, the cap is its own outcome`() {
        assertEquals(BlockOutcome.Blocked, blockErrorOutcome("23505", "duplicate key"))
        assertEquals(BlockOutcome.LimitReached, blockErrorOutcome("P0001", "BLOCK_LIMIT_REACHED: max 500 blocked members"))
        assertNull(blockErrorOutcome("42501", "new row violates row-level security policy"))
    }
}
