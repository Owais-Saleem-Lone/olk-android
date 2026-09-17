package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.repository.FeatureFlags
import com.openlibrarykashmir.olk.core.data.repository.parseFeatureFlags
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureFlagsTest {

    private fun rows(vararg pairs: Pair<String, JsonElement>) = pairs.toList()

    @Test
    fun `reads a real JSON boolean, as the migrations seed it`() {
        val flags = parseFeatureFlags(
            rows(
                "feature_messages" to JsonPrimitive(false),
                "feature_clubs" to JsonPrimitive(true),
            ),
        )

        assertEquals(false, flags.messages)
        assertEquals(true, flags.clubs)
    }

    @Test
    fun `reads the quoted string the admin panel writes`() {
        val flags = parseFeatureFlags(
            rows(
                "feature_messages" to JsonPrimitive("false"),
                "maintenance_mode" to JsonPrimitive("true"),
            ),
        )

        assertEquals(false, flags.messages)
        assertEquals(true, flags.maintenanceMode)
    }

    @Test
    fun `a missing key keeps the default rather than hiding the feature`() {
        assertEquals(FeatureFlags(), parseFeatureFlags(emptyList()))

        val flags = parseFeatureFlags(rows("feature_events" to JsonPrimitive(false)))
        assertEquals(false, flags.events)
        assertEquals(true, flags.messages)
        assertEquals(true, flags.wishlists)
        assertEquals(true, flags.ratings)
        assertEquals(false, flags.maintenanceMode)
    }

    @Test
    fun `anything that is not true counts as off`() {
        val flags = parseFeatureFlags(rows("feature_ratings" to JsonPrimitive("yes")))

        assertEquals(false, flags.ratings)
    }
}
