package com.openlibrarykashmir.olk.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A row of `public.profiles`.
 *
 * Note there is no email field — that is deliberate in the schema. Email lives only
 * in `auth.users` and is reachable exclusively through the service-role key, which
 * an app on someone's phone must never hold.
 */
@Serializable
data class Profile(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
    val bio: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("email_digest") val emailDigest: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
)
