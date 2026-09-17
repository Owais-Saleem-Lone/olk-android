package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.repository.OpenLibraryIsbnRepository
import com.openlibrarykashmir.olk.core.data.repository.guessGenre
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsbnLookupTest {

    private fun repository(handler: (String) -> String?): OpenLibraryIsbnRepository {
        val engine = MockEngine { request ->
            val body = handler(request.url.toString())
            if (body == null) {
                respondError(HttpStatusCode.NotFound)
            } else {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        return OpenLibraryIsbnRepository(client, baseUrl = "https://openlibrary.test", coversUrl = "https://covers.test")
    }

    private val searchJson = """
        {"numFound": 1, "docs": [{
          "title": "Pride and Prejudice",
          "author_name": ["Jane Austen"],
          "cover_i": 14348537,
          "first_publish_year": 1813,
          "key": "/works/OL66554W",
          "subject": ["Fiction", "English literature", "Domestic fiction"]
        }]}
    """.trimIndent()

    @Test
    fun `maps an Open Library search result onto the form fields`() = runTest {
        val repo = repository { url ->
            when {
                "search.json" in url -> searchJson
                url.endsWith("/isbn/9780141439518.json") -> """{"publish_date": "August 2002"}"""
                url.endsWith("/works/OL66554W.json") -> """{"description": {"value": "A classic novel."}}"""
                else -> null
            }
        }

        val book = repo.lookup("978-0-14-143951-8")!!

        assertEquals("Pride and Prejudice", book.title)
        assertEquals("Jane Austen", book.author)
        assertEquals("https://covers.test/b/id/14348537-L.jpg", book.coverUrl)
        // This printing's year, not the work's first publication in 1813.
        assertEquals(2002, book.publicationYear)
        assertEquals("A classic novel.", book.description)
        assertEquals("English Literature", book.genre)
    }

    @Test
    fun `falls back to the first publication year when the edition record is missing`() = runTest {
        val repo = repository { url -> if ("search.json" in url) searchJson else null }

        val book = repo.lookup("9780141439518")!!

        assertEquals(1813, book.publicationYear)
        assertNull(book.description)
        // A failed extra request must not lose the details the search already gave.
        assertEquals("Pride and Prejudice", book.title)
    }

    @Test
    fun `a plain-string work description is read too`() = runTest {
        val repo = repository { url ->
            when {
                "search.json" in url -> searchJson
                url.endsWith("/works/OL66554W.json") -> """{"description": "From the work record."}"""
                else -> null
            }
        }

        assertEquals("From the work record.", repo.lookup("9780141439518")?.description)
    }

    @Test
    fun `no match, a malformed number, and a failing search all come back empty`() = runTest {
        assertNull(repository { """{"numFound": 0, "docs": []}""" }.lookup("9780141439518"))
        assertNull(repository { searchJson }.lookup("12345"))
        assertNull(repository { null }.lookup("9780141439518"))
    }

    @Test
    fun `the category is only guessed when the subjects agree`() {
        // Two clear hits and no rival: confident enough.
        assertEquals("History", guessGenre(listOf("History", "Indian history", "Politics")))
        // A single tag is not evidence.
        assertNull(guessGenre(listOf("History")))
        // A tie is not a decision: two literature tags against two history ones.
        assertNull(guessGenre(listOf("Fiction", "Novel", "History", "Indian history")))
        // "Historical fiction" is a literature tag, so a novel with one history tag
        // still counts as literature — same as the web scanner scores it.
        assertEquals(
            "English Literature",
            guessGenre(listOf("Fiction", "Literature", "History", "Historical fiction")),
        )
        assertNull(guessGenre(emptyList()))
    }

    @Test
    fun `subject matching ignores case and works on longer tags`() {
        assertTrue(guessGenre(listOf("COMPUTER SCIENCE", "software engineering")) == "IT/Computer Science")
    }
}
