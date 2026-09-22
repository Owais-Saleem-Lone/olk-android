package com.openlibrarykashmir.olk.core.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Why an account cannot be deleted yet, as `my_account_deletion_blockers()` names them. */
enum class DeletionBlocker(val key: String) {
    UNFINISHED_EXCHANGE("unfinished_exchange"),
    OWNS_CLUB("owns_club"),
    IS_ADMIN("is_admin"),
    ;

    companion object {
        fun of(key: String): DeletionBlocker? = entries.firstOrNull { it.key == key }
    }
}

sealed interface DeleteAccountOutcome {
    data object Deleted : DeleteAccountOutcome

    /** Something has to be settled first; the same list the website shows. */
    data class Blocked(val blockers: List<DeletionBlocker>) : DeleteAccountOutcome

    data class Refused(val message: String) : DeleteAccountOutcome
}

/**
 * Deleting an account needs the service role, which no app may hold, so this
 * goes through the website's `/api/account/delete` — the same route the
 * website's own profile page uses, with the Supabase access token in place of
 * a session cookie.
 */
interface AccountDeletionRepository {
    /** What stands in the way right now, so the screen can say so before asking. */
    suspend fun blockers(): List<DeletionBlocker>

    suspend fun delete(): DeleteAccountOutcome
}

internal class WebsiteAccountDeletionRepository(
    private val http: HttpClient,
    private val supabase: SupabaseClient,
    private val websiteUrl: String,
) : AccountDeletionRepository {

    override suspend fun blockers(): List<DeletionBlocker> =
        supabase.postgrest.rpc("my_account_deletion_blockers")
            .decodeAs<List<String>>()
            .mapNotNull(DeletionBlocker::of)

    override suspend fun delete(): DeleteAccountOutcome {
        val token = supabase.auth.currentAccessTokenOrNull()
            ?: return DeleteAccountOutcome.Refused("Please sign in again to delete your account.")

        val response = http.post("${websiteUrl.trimEnd('/')}/api/account/delete") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        return deleteAccountOutcome(response.status.value, if (isJson) response.bodyAsText() else null)
    }
}

/**
 * What the website's answer means. Only a JSON `{"deleted": true}` counts:
 * anything else, an HTML maintenance page included, leaves the account alone.
 */
internal fun deleteAccountOutcome(status: Int, jsonBody: String?): DeleteAccountOutcome {
    val body = jsonBody?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    if (status in 200..299 && body?.get("deleted")?.jsonPrimitive?.booleanOrNull == true) {
        return DeleteAccountOutcome.Deleted
    }
    val blockers = runCatching {
        body?.get("blockers")?.jsonArray?.mapNotNull { DeletionBlocker.of(it.jsonPrimitive.content) }
    }.getOrNull().orEmpty()
    if (blockers.isNotEmpty()) return DeleteAccountOutcome.Blocked(blockers)

    val said = body?.get("error")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "blocked" }
    return DeleteAccountOutcome.Refused(
        said ?: "Your account could not be deleted just now. Please try again later.",
    )
}
