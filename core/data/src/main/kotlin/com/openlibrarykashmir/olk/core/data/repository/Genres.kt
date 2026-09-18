package com.openlibrarykashmir.olk.core.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.Serializable

/** Active genres in the admin-set display order; shared by the book form and Browse. */
internal suspend fun SupabaseClient.activeGenres(): List<String> =
    from("genres").select(Columns.list("name")) {
        filter { eq("active", true) }
        order("display_order", Order.ASCENDING)
    }.decodeList<GenreRow>().map { it.name }

@Serializable
private data class GenreRow(val name: String)
