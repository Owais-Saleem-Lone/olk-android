package com.openlibrarykashmir.olk.core.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * The signed-in user's own profile, including the columns only they (and
 * admins) may read. It comes from `get_my_profile()`: since the web's
 * `20260918072315` those columns cannot be selected from `profiles` directly.
 */
@Serializable
data class OwnProfile(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
    val bio: String? = null,
    @SerialName("email_digest") val emailDigest: Boolean? = true,
    @SerialName("is_banned") val isBanned: Boolean? = false,
    @SerialName("ban_reason") val banReason: String? = null,
    @SerialName("ban_expires_at") val banExpiresAt: String? = null,
)

/** A point shared for "books near me". Only ever readable by its owner. */
data class SharedLocation(val latitude: Double, val longitude: Double)

/** What the profile form saves; mirrors the website's profile page. */
data class ProfileUpdate(
    val displayName: String,
    val areaName: String,
    val bio: String,
    val emailDigest: Boolean,
    /** Null removes a shared location. */
    val location: SharedLocation?,
)

interface ProfileRepository {
    suspend fun myProfile(): OwnProfile?

    suspend fun myLocation(userId: String): SharedLocation?

    /** Admin-curated localities, "Name, District", to suggest while typing — never a restriction. */
    suspend fun areaSuggestions(): List<String>

    suspend fun save(userId: String, update: ProfileUpdate)

    companion object {
        /** `profiles.display_name` is varchar(50). */
        const val MAX_DISPLAY_NAME = 50

        /** `profiles.area_name` is varchar(100). */
        const val MAX_AREA = 100

        /** `profiles_bio_check`. */
        const val MAX_BIO = 300
    }
}

internal class SupabaseProfileRepository(
    private val client: SupabaseClient,
) : ProfileRepository {

    override suspend fun myProfile(): OwnProfile? =
        client.postgrest.rpc("get_my_profile").decodeList<OwnProfile>().firstOrNull()

    override suspend fun myLocation(userId: String): SharedLocation? =
        client.from("profile_locations").select(Columns.list("latitude", "longitude")) {
            filter { eq("user_id", userId) }
        }.decodeSingleOrNull<LocationRow>()?.let { row ->
            if (row.latitude != null && row.longitude != null) SharedLocation(row.latitude, row.longitude) else null
        }

    override suspend fun areaSuggestions(): List<String> =
        client.from("areas").select(Columns.list("name", "district")) {
            filter { eq("active", true) }
            order("district", Order.ASCENDING)
            order("name", Order.ASCENDING)
        }.decodeList<AreaRow>().map { if (it.district.isNullOrBlank()) it.name else "${it.name}, ${it.district}" }

    override suspend fun save(userId: String, update: ProfileUpdate) {
        // update(), never upsert(): every profile row exists from signup
        // (handle_new_user), and ON CONFLICT DO UPDATE would need SELECT on
        // email_digest, which is private.
        client.from("profiles").update(
            buildJsonObject {
                put("display_name", update.displayName.trim())
                put("area_name", update.areaName.trim())
                val bio = update.bio.trim()
                if (bio.isEmpty()) put("bio", JsonNull) else put("bio", bio)
                put("email_digest", update.emailDigest)
                put("updated_at", Instant.now().toString())
            },
        ) {
            filter { eq("id", userId) }
        }

        val location = update.location
        if (location == null) {
            client.from("profile_locations").delete { filter { eq("user_id", userId) } }
        } else {
            client.from("profile_locations").upsert(
                buildJsonObject {
                    put("user_id", userId)
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("updated_at", Instant.now().toString())
                },
            )
        }
    }
}

@Serializable
private data class LocationRow(val latitude: Double? = null, val longitude: Double? = null)

@Serializable
private data class AreaRow(val name: String, val district: String? = null)
