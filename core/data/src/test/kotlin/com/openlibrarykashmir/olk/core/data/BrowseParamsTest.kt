package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.model.BookCondition
import com.openlibrarykashmir.olk.core.data.model.BrowseFilters
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.repository.browseParams
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseParamsTest {

    @Test
    fun `no filters sends only paging, so the RPC defaults apply`() {
        assertEquals(
            """{"p_limit":20,"p_offset":40}""",
            browseParams(BrowseFilters(query = "   ", area = " "), limit = 20, offset = 40).toString(),
        )
    }

    @Test
    fun `every filter uses the database spelling of its value`() {
        val params = browseParams(
            BrowseFilters(
                query = "  rumi ",
                genre = "Poetry",
                listingType = ListingType.LEND,
                condition = BookCondition.GOOD,
                area = " Anantnag ",
                radiusKm = 5,
            ),
            limit = 20,
            offset = 0,
        )

        assertEquals(
            Json.parseToJsonElement(
                """{"p_query":"rumi","p_genre":"Poetry","p_listing_type":"lend","p_condition":"good",
                   "p_area":"Anantnag","p_radius_km":5,"p_limit":20,"p_offset":0}""",
            ),
            params,
        )
    }

    @Test
    fun `never sends coordinates -- distance is measured from the saved location in the database`() {
        val keys = browseParams(BrowseFilters(radiusKm = 2), 20, 0).keys
        assertEquals(setOf("p_radius_km", "p_limit", "p_offset"), keys)
    }

    @Test
    fun `active filter count ignores the search box`() {
        assertEquals(0, BrowseFilters(query = "rumi").activeCount)
        assertEquals(
            3,
            BrowseFilters(genre = "Poetry", listingType = ListingType.DONATE, radiusKm = 10, area = " ").activeCount,
        )
    }
}
