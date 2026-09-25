package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.model.MembershipStatus
import com.openlibrarykashmir.olk.core.data.repository.ClubCloseOutcome
import com.openlibrarykashmir.olk.core.data.repository.ClubEditOutcome
import com.openlibrarykashmir.olk.core.data.repository.PostOutcome
import com.openlibrarykashmir.olk.core.data.repository.clubCloseErrorOutcome
import com.openlibrarykashmir.olk.core.data.repository.clubEditErrorOutcome
import com.openlibrarykashmir.olk.core.data.repository.browseClubParams
import com.openlibrarykashmir.olk.core.data.repository.membershipStatusOf
import com.openlibrarykashmir.olk.core.data.repository.postErrorOutcome
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClubParamsTest {

    @Test
    fun `no filters sends only paging, so the RPC defaults apply`() {
        assertEquals(
            """{"p_limit":20,"p_offset":40}""",
            browseClubParams(query = "   ", interest = " ", limit = 20, offset = 40).toString(),
        )
    }

    @Test
    fun `filters are trimmed and sent under the database's names`() {
        assertEquals(
            Json.parseToJsonElement("""{"p_query":"poetry","p_interest":"Poetry","p_limit":20,"p_offset":0}"""),
            browseClubParams(query = "  poetry ", interest = "Poetry", limit = 20, offset = 0),
        )
    }

    @Test
    fun `never sends coordinates -- distance is measured from the saved location in the database`() {
        assertEquals(
            setOf("p_query", "p_limit", "p_offset"),
            browseClubParams(query = "chess", interest = null, limit = 20, offset = 0).keys,
        )
    }

    @Test
    fun `membership status maps the two states the database knows`() {
        assertEquals(MembershipStatus.APPROVED, membershipStatusOf("approved"))
        assertEquals(MembershipStatus.PENDING, membershipStatusOf("pending"))
        // No row, or a status this version does not know, means "not in this club".
        assertEquals(MembershipStatus.NONE, membershipStatusOf(null))
        assertEquals(MembershipStatus.NONE, membershipStatusOf("banished"))
    }

    @Test
    fun `the hourly chat limit is recognised, and nothing else is swallowed`() {
        assertEquals(
            PostOutcome.RateLimited,
            postErrorOutcome("RATE_LIMIT_EXCEEDED: max 10 messages per hour reached"),
        )
        // Anything else has to surface as a real error rather than a friendly message.
        assertNull(postErrorOutcome("permission denied for table club_posts"))
        assertNull(postErrorOutcome(null))
    }

    @Test
    fun `an owner's refused edit is Invalid only for a blank name or text too long`() {
        assertEquals(ClubEditOutcome.Invalid, clubEditErrorOutcome("23514"))
        assertEquals(ClubEditOutcome.Invalid, clubEditErrorOutcome("22001"))
        // Anything else (a suspension's RLS refusal, a network error) is a real error.
        assertNull(clubEditErrorOutcome("42501"))
    }

    @Test
    fun `close_my_club's refusal of a club that is not open is recognised`() {
        assertEquals(
            ClubCloseOutcome.AlreadyClosed,
            clubCloseErrorOutcome("CLUB_NOT_OPEN: only the owner can close an open club"),
        )
        assertNull(clubCloseErrorOutcome("permission denied for function close_my_club"))
    }
}
