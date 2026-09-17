package com.openlibrarykashmir.olk.core.data

import com.openlibrarykashmir.olk.core.data.remote.SupabaseClientFactory
import com.openlibrarykashmir.olk.core.data.repository.BookDetailRepository
import com.openlibrarykashmir.olk.core.data.repository.BookRepository
import com.openlibrarykashmir.olk.core.data.repository.MessagesRepository
import com.openlibrarykashmir.olk.core.data.repository.MyBooksRepository
import com.openlibrarykashmir.olk.core.data.repository.RequestsRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseBookDetailRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseBookRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseMessagesRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseMyBooksRepository
import com.openlibrarykashmir.olk.core.data.repository.SupabaseRequestsRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.SupabaseAuthRepository
import io.github.jan.supabase.SupabaseClient
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
}
