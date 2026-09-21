package com.openlibrarykashmir.olk.core.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

/**
 * Asked only after the database has refused a write with SQLSTATE 42501: was it
 * because the caller is suspended (web migration `20260921182823`)? Postgres
 * reports every row-level refusal the same way, and without this a suspended
 * member would be told the book is gone or the chat is closed.
 *
 * `caller_is_suspended()` only ever answers about the caller. If the question
 * itself fails, the refusal is treated as the ordinary kind.
 */
internal suspend fun SupabaseClient.refusedBecauseSuspended(): Boolean =
    runCatching { postgrest.rpc("caller_is_suspended").decodeAs<Boolean?>() == true }.getOrDefault(false)
