package com.openlibrarykashmir.olk.core.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

/**
 * Builds the single [SupabaseClient] the app talks to.
 *
 * The anon key carries no privilege of its own. Every table this client can reach is
 * gated by the same RLS policies and SECURITY DEFINER helpers the web app runs
 * against — `is_club_member()`, the rate-limit triggers, the membership-approval
 * `WITH CHECK`. That is the whole reason this port is a front end and not a rewrite:
 * authorisation lives in Postgres, so the phone cannot bypass a rule by asking nicely.
 */
object SupabaseClientFactory {

    fun create(
        supabaseUrl: String,
        supabaseAnonKey: String,
        deepLinkScheme: String = DEEP_LINK_SCHEME,
        deepLinkHost: String = DEEP_LINK_HOST,
    ): SupabaseClient {
        require(supabaseUrl.isNotBlank()) {
            "SUPABASE_URL is empty. Copy secrets.defaults.properties to secrets.properties and fill it in."
        }
        require(supabaseAnonKey.isNotBlank()) {
            "SUPABASE_ANON_KEY is empty. Copy secrets.defaults.properties to secrets.properties and fill it in."
        }

        return createSupabaseClient(supabaseUrl, supabaseAnonKey) {
            install(Auth) {
                // Email confirmation and password-reset links must reopen the app
                // rather than bouncing the user into Chrome. These values pair with
                // the intent filter in the app module's AndroidManifest.
                scheme = deepLinkScheme
                host = deepLinkHost
            }
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }

    const val DEEP_LINK_SCHEME = "olk"
    const val DEEP_LINK_HOST = "auth-callback"
}
