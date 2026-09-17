package com.openlibrarykashmir.olk.core.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The website's admin switches, mirroring `src/lib/platform-settings.ts`.
 *
 * Defaults match the web's: on, so a failed or slow read never hides a feature
 * that is in fact enabled. Only [messages] is acted on so far — it is the only
 * one of these features the app has.
 */
data class FeatureFlags(
    val clubs: Boolean = true,
    val wishlists: Boolean = true,
    val ratings: Boolean = true,
    val messages: Boolean = true,
    val events: Boolean = true,
    val maintenanceMode: Boolean = false,
)

interface PlatformSettingsRepository {
    suspend fun featureFlags(): FeatureFlags
}

/**
 * `platform_settings.value` is jsonb and holds either a real JSON boolean (as
 * seeded by the migrations) or the JSON string "true"/"false" (as written by
 * the admin panel's updatePlatformSetting), so accept both — same as the web's
 * `parseFeatureFlags`.
 */
internal fun parseFeatureFlags(rows: List<Pair<String, JsonElement>>): FeatureFlags {
    val values = rows.associate { (key, value) ->
        key to ((value as? JsonPrimitive)?.content == "true")
    }
    val defaults = FeatureFlags()
    return FeatureFlags(
        clubs = values["feature_clubs"] ?: defaults.clubs,
        wishlists = values["feature_wishlists"] ?: defaults.wishlists,
        ratings = values["feature_ratings"] ?: defaults.ratings,
        messages = values["feature_messages"] ?: defaults.messages,
        events = values["feature_events"] ?: defaults.events,
        maintenanceMode = values["maintenance_mode"] ?: defaults.maintenanceMode,
    )
}

internal val FEATURE_FLAG_KEYS = listOf(
    "feature_clubs",
    "feature_wishlists",
    "feature_ratings",
    "feature_messages",
    "feature_events",
    "maintenance_mode",
)

internal class SupabasePlatformSettingsRepository(
    private val client: SupabaseClient,
) : PlatformSettingsRepository {

    override suspend fun featureFlags(): FeatureFlags {
        val rows = client.from(TABLE).select(Columns.raw("key, value")) {
            filter { isIn("key", FEATURE_FLAG_KEYS) }
        }.decodeList<SettingRow>()
        return parseFeatureFlags(rows.map { it.key to it.value })
    }

    private companion object {
        const val TABLE = "platform_settings"
    }
}

@Serializable
private data class SettingRow(val key: String, val value: JsonElement)
