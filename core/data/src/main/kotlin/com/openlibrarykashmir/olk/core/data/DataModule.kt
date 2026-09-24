package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.remote.SupabaseClientFactory
import com.openlibrarykashmir.olk.core.data.repository.BlocksRepository
import com.openlibrarykashmir.olk.core.data.repository.BookDetailRepository
import com.openlibrarykashmir.olk.core.data.repository.BookNotesRepository
import com.openlibrarykashmir.olk.core.data.repository.ClubOrganiserRepository
import com.openlibrarykashmir.olk.core.data.repository.BookRepository
import com.openlibrarykashmir.olk.core.data.repository.IsbnLookupRepository
import com.openlibrarykashmir.olk.core.data.repository.MessagesRepository
import com.openlibrarykashmir.olk.core.data.repository.HomeRepository
import com.openlibrarykashmir.olk.core.data.repository.ClubsRepository
import com.openlibrarykashmir.olk.core.data.repository.EventsRepository
import com.openlibrarykashmir.olk.core.data.repository.ListsRepository
import com.openlibrarykashmir.olk.core.data.repository.MyBooksRepository
import com.openlibrarykashmir.olk.core.data.repository.PeopleRepository
import com.openlibrarykashmir.olk.core.data.repository.NotificationsRepository
import com.openlibrarykashmir.olk.core.data.repository.PlatformSettingsRepository
import com.openlibrarykashmir.olk.core.data.repository.ProfileRepository
import com.openlibrarykashmir.olk.core.data.repository.RequestsRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseBlocksRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseBookDetailRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseBookNotesRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseClubOrganiserRepository
import com.openlibrarykashmir.olk.core.data.repository.OpenLibraryIsbnRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseBookRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseMessagesRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseHomeRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseClubsRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseEventsRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseSupportRepository
import com.openlibrarykashmir.olk.core.data.repository.SupportRepository
import com.openlibrarykashmir.olk.core.data.repository.AccountDeletionRepository
import com.openlibrarykashmir.olk.core.data.repository.WebsiteAccountDeletionRepository
import com.openlibrarykashmir.olk.core.data.repository.TeamApplicationRepository
import com.openlibrarykashmir.olk.core.data.repository.WebsiteTeamApplicationRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseListsRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseMyBooksRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabasePeopleRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseNotificationsRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabasePlatformSettingsRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseProfileRepository
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
 * The URLs and key are passed in rather than read from `BuildConfig` here, because
 * `BuildConfig` belongs to the app module — keeping this module ignorant of it is
 * what lets tests point the same graph at a local Supabase stack.
 */
fun dataModule(supabaseUrl: String, supabaseAnonKey: String, websiteUrl: String) = module {
    single<SupabaseClient> {
        SupabaseClientFactory.create(
            supabaseUrl = supabaseUrl,
            supabaseAnonKey = supabaseAnonKey,
        )
    }
    single<AuthRepository> { SupabaseAuthRepository(get()) }
    single<BookRepository> { SupabaseBookRepository(get()) }
    single<BookDetailRepository> { SupabaseBookDetailRepository(get(), get()) }
    single<BookNotesRepository> { SupabaseBookNotesRepository(get()) }
    single<MyBooksRepository> { SupabaseMyBooksRepository(get()) }
    single<RequestsRepository> { SupabaseRequestsRepository(get()) }
    single<MessagesRepository> { SupabaseMessagesRepository(get(), get()) }
    single<NotificationsRepository> { SupabaseNotificationsRepository(get()) }
    single<PlatformSettingsRepository> { SupabasePlatformSettingsRepository(get()) }
    single<ProfileRepository> { SupabaseProfileRepository(get()) }
    single<ListsRepository> { SupabaseListsRepository(get()) }
    single<HomeRepository> { SupabaseHomeRepository(get()) }
    single<PeopleRepository> { SupabasePeopleRepository(get()) }
    single<ClubsRepository> { SupabaseClubsRepository(get()) }
    single<ClubOrganiserRepository> { SupabaseClubOrganiserRepository(get()) }
    single<EventsRepository> { SupabaseEventsRepository(get()) }
    single<SupportRepository> { SupabaseSupportRepository(get()) }
    single<BlocksRepository> { SupabaseBlocksRepository(get()) }

    // The two calls that do not go to Supabase: Open Library (ISBN lookup), and
    // the website's team-application endpoint, which takes the CV upload.
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
    single<TeamApplicationRepository> { WebsiteTeamApplicationRepository(get(), get(), websiteUrl) }
    single<AccountDeletionRepository> { WebsiteAccountDeletionRepository(get(), get(), websiteUrl) }
}
