package com.openlibrarykashmir.olk.core.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** What a scanned or typed ISBN fills into the Add Book form. */
data class IsbnBook(
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val genre: String?,
    val publicationYear: Int?,
    val description: String?,
)

interface IsbnLookupRepository {
    /** Null when Open Library has no record, or the lookup fails. */
    suspend fun lookup(isbn: String): IsbnBook?
}

/**
 * Open Library, the only call this app makes outside Supabase. No key needed.
 *
 * Uses `search.json`, not the older `/api/books` the web scanner was written
 * against — that endpoint now answers 404. One search gives title, author names,
 * subjects and a cover id; the edition record then supplies this printing's year
 * (search only reports the work's *first* publication, which for a classic can be
 * centuries off), and the work record the description. Those two are optional
 * extras, fetched together, and a failure in either still leaves a usable result.
 */
internal class OpenLibraryIsbnRepository(
    private val client: HttpClient,
    private val baseUrl: String = "https://openlibrary.org",
    private val coversUrl: String = "https://covers.openlibrary.org",
) : IsbnLookupRepository {

    override suspend fun lookup(isbn: String): IsbnBook? = coroutineScope {
        val clean = isbn.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase()
        if (clean.length != ISBN_10 && clean.length != ISBN_13) return@coroutineScope null

        val doc = getJson("$baseUrl/search.json?q=isbn:$clean&limit=1&fields=$SEARCH_FIELDS")
            ?.get("docs")?.jsonArray?.firstOrNull()?.jsonObject
            ?: return@coroutineScope null

        val title = doc["title"]?.text().orEmpty()
        if (title.isBlank()) return@coroutineScope null

        val edition = async { getJson("$baseUrl/isbn/$clean.json") }
        val work = async { doc["key"]?.text()?.let { getJson("$baseUrl$it.json") } }

        val editionYear = edition.await()?.get("publish_date")?.text()?.let { YEAR.find(it)?.value?.toIntOrNull() }
        val subjects = doc["subject"]?.jsonArray.orEmpty().mapNotNull { it.text() }

        IsbnBook(
            title = title,
            author = doc["author_name"]?.jsonArray?.firstOrNull()?.text(),
            coverUrl = doc["cover_i"]?.let { "$coversUrl/b/id/${it.numberOrNull() ?: return@let null}-L.jpg" },
            genre = guessGenre(subjects),
            publicationYear = editionYear ?: doc["first_publish_year"]?.numberOrNull(),
            description = work.await()?.get("description")?.describedText(),
        )
    }

    private suspend fun getJson(url: String): JsonObject? = runCatching {
        client.get(url).takeIf(HttpResponse::isOk)?.body<JsonObject>()
    }.getOrNull()

    private companion object {
        const val ISBN_10 = 10
        const val ISBN_13 = 13
        const val SEARCH_FIELDS = "title,author_name,cover_i,subject,key,first_publish_year"
        val YEAR = Regex("\\d{4}")
    }
}

private fun HttpResponse.isOk() = status.value in 200..299

private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.numberOrNull(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()

/** Open Library gives a description as either a plain string or `{ value: "..." }`. */
private fun JsonElement.describedText(): String? =
    text() ?: (this as? JsonObject)?.get("value")?.text()

private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())

/**
 * Keyword → app category, ported from the web scanner. Open Library subjects are
 * noisy folksonomy tags (a historical romance can carry a bare "History" tag next
 * to dozens of fiction ones), so one hit is not enough: every category is scored
 * by how many distinct subjects match, and the winner must both clear a minimum
 * and beat the runner-up outright. Otherwise the category is left unset rather
 * than guessed wrong.
 */
private val GENRE_KEYWORDS: List<Pair<String, List<String>>> = listOf(
    "Physics" to listOf("physics"),
    "Chemistry" to listOf("chemistry"),
    "Biology" to listOf("biology", "botany", "zoology"),
    "Mathematics" to listOf("mathematics", "algebra", "calculus", "geometry", "trigonometry"),
    "Civil Engineering" to listOf("civil engineering"),
    "Mechanical Engineering" to listOf("mechanical engineering"),
    "Electrical Engineering" to listOf("electrical engineering", "electronics"),
    "IT/Computer Science" to listOf("computer science", "programming", "software", "computing", "algorithms"),
    "Anatomy" to listOf("anatomy"),
    "Physiology" to listOf("physiology"),
    "Clinical Medicine" to listOf("medicine", "medical", "clinical"),
    "Psychology" to listOf("psychology"),
    "Philosophy" to listOf("philosophy"),
    "Geography" to listOf("geography"),
    "Civics" to listOf("civics", "political science", "government"),
    "History" to listOf("history"),
    "Urdu Literature" to listOf("urdu"),
    "Hindi Literature" to listOf("hindi"),
    "Persian Literature" to listOf("persian", "farsi"),
    "Arabic Literature" to listOf("arabic"),
    "Kashmiri Literature" to listOf("kashmiri"),
    "English Literature" to listOf("fiction", "literature", "novel", "poetry", "drama"),
)

private const val MIN_SUBJECT_MATCHES = 2

internal fun guessGenre(subjects: List<String>): String? {
    val scores = GENRE_KEYWORDS.map { (genre, keywords) ->
        genre to subjects.count { subject ->
            val lower = subject.lowercase()
            keywords.any { lower.contains(it) }
        }
    }.sortedByDescending { it.second }

    val top = scores.firstOrNull() ?: return null
    val runnerUp = scores.getOrNull(1)?.second ?: 0
    return if (top.second < MIN_SUBJECT_MATCHES || top.second <= runnerUp) null else top.first
}
