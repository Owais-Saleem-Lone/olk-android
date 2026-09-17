package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.remote.SupabaseClientFactory
import com.openlibrarykashmir.olk.core.data.repository.BookDetailRepository
import com.openlibrarykashmir.olk.core.data.repository.BookRepository
import com.openlibrarykashmir.olk.core.data.repository.IsbnLookupRepository
import com.openlibrarykashmir.olk.core.data.repository.MessagesRepository
import com.openlibrarykashmir.olk.core.data.repository.MyBooksRepository
import com.openlibrarykashmir.olk.core.data.repository.RequestsRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseBookDetailRepository
import com.openlibrarykashmir.olk.core.data.repository.OpenLibraryIsbnRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseBookRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseMessagesRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseMyBooksRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseRequestsRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.SupabaseAuthRepository
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.dsl.module

/**
 * Wiring for the data layer.
 *
 * The URL and key are passed in rather than read from `BuildConfig` here, because
 * `BuildConfig` belongs to the app module — keeping this module ignorant of it is
 * what lets tests point the same graph at a local Supabase stack.
 */
fun dataModule(supabaseUrl: String, supabaseAnonKey: String) = module {
    single<SupabaseClient> {
        SupabaseClientFactory.create(
            supabaseUrl = supabaseUrl,
            supabaseAnonKey = supabaseAnonKey,
        )
    }
    single<AuthRepository> { SupabaseAuthRepository(get()) }
    single<BookRepository> { SupabaseBookRepository(get()) }
    single<BookDetailRepository> { SupabaseBookDetailRepository(get(), get()) }
    single<MyBooksRepository> { SupabaseMyBooksRepository(get()) }
    single<RequestsRepository> { SupabaseRequestsRepository(get()) }
    single<MessagesRepository> { SupabaseMessagesRepository(get(), get()) }

    // Open Library (ISBN lookup) is the only call that does not go to Supabase.
    single {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
            }
        }
    }
    single<IsbnLookupRepository> { OpenLibraryIsbnRepository(get()) }
}
