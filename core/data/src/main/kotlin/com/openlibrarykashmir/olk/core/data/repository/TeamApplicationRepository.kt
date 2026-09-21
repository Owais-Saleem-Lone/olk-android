package com.openlibrarykashmir.olk.core.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A "Join the OLK team" application, as the website's form collects it. */
class TeamApplication(
    val fullName: String,
    val age: Int,
    val email: String,
    val phone: String,
    val profession: String,
    val studies: String,
    val field: String,
    val motivation: String,
    val contribution: String,
    val cvFileName: String,
    val cvMimeType: String,
    val cv: ByteArray,
)

sealed interface TeamApplicationOutcome {
    data object Submitted : TeamApplicationOutcome

    /** The website refused it and said why, in words fit to show. */
    data class Refused(val message: String) : TeamApplicationOutcome
}

/**
 * Sends an application to the website's `/api/team-applications`, which checks
 * it, stores the CV privately and emails the team. It is not written to the
 * database from here on purpose: applications carry a CV and are rate-limited
 * per account, and both are the server's to enforce.
 */
interface TeamApplicationRepository {
    suspend fun submit(application: TeamApplication): TeamApplicationOutcome
}

internal class WebsiteTeamApplicationRepository(
    private val http: HttpClient,
    private val supabase: SupabaseClient,
    private val websiteUrl: String,
) : TeamApplicationRepository {

    override suspend fun submit(application: TeamApplication): TeamApplicationOutcome {
        // The website has no session cookie from the app; the route accepts the
        // Supabase access token instead and asks Supabase Auth whose it is.
        val token = supabase.auth.currentAccessTokenOrNull()
            ?: return TeamApplicationOutcome.Refused("Please sign in again to apply.")

        val response = http.submitFormWithBinaryData(
            url = "${websiteUrl.trimEnd('/')}/api/team-applications",
            formData = formData {
                append("full_name", application.fullName.trim())
                append("age", application.age.toString())
                append("email", application.email.trim())
                append("phone", application.phone.trim())
                append("profession", application.profession.trim())
                append("studies", application.studies.trim())
                append("field", application.field)
                append("motivation", application.motivation.trim())
                append("contribution", application.contribution.trim())
                append(
                    "cv",
                    application.cv,
                    Headers.build {
                        append(HttpHeaders.ContentType, application.cvMimeType)
                        append(HttpHeaders.ContentDisposition, "filename=\"${safeFileName(application.cvFileName)}\"")
                    },
                )
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer $token")
            // A 4 MB CV on a slow connection needs longer than the client's
            // default ten seconds.
            timeout { requestTimeoutMillis = UPLOAD_TIMEOUT_MS }
        }

        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        return teamApplicationOutcome(response.status.value, if (isJson) response.bodyAsText() else null)
    }
}

/**
 * What the website's answer means. Only a JSON `{"success": true}` counts as
 * submitted: anything else — including an HTML page, which is what a
 * maintenance redirect returns — is not.
 */
internal fun teamApplicationOutcome(status: Int, jsonBody: String?): TeamApplicationOutcome {
    val body = jsonBody?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    if (status in 200..299 && body?.get("success")?.jsonPrimitive?.booleanOrNull == true) {
        return TeamApplicationOutcome.Submitted
    }
    val said = body?.get("error")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    return TeamApplicationOutcome.Refused(
        said ?: "The website could not take your application just now. Please try again later.",
    )
}

private const val UPLOAD_TIMEOUT_MS = 60_000L

/** A file name safe inside a quoted header value; the server re-sanitises it anyway. */
internal fun safeFileName(name: String): String =
    name.replace(Regex("[\"\\\\\r\n]"), "_").ifBlank { "cv" }

/**
 * The website's field list and limits (`src/lib/team-application-fields.ts`).
 * The server checks all of them again; these only let the form say so first.
 */
object TeamApplicationRules {
    val FIELDS = listOf(
        "Computer Science / Software Engineering",
        "Information Technology",
        "Design (UI/UX, Graphic)",
        "Marketing, Social Media & Outreach",
        "Other",
    )
    const val MOTIVATION_WORD_LIMIT = 250
    const val CONTRIBUTION_WORD_LIMIT = 300
    const val MIN_AGE = 13
    const val MAX_AGE = 100
    const val MAX_TEXT_LENGTH = 200

    /** 4 MB, under Vercel's 4.5 MB request ceiling. */
    const val CV_MAX_BYTES = 4 * 1024 * 1024
    val CV_EXTENSIONS = listOf(".pdf", ".doc", ".docx")
    val CV_MIME_TYPES = listOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )

    /** The website's `wordCount`: whitespace-separated, ignoring blanks. */
    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
}
