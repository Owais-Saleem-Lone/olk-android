package com.openlibrarykashmir.olk.di

import com.openlibrarykashmir.olk.MainViewModel
import com.openlibrarykashmir.olk.feature.auth.AuthViewModel
import com.openlibrarykashmir.olk.feature.bookdetail.BookDetailViewModel
import com.openlibrarykashmir.olk.feature.browse.BrowseViewModel
import com.openlibrarykashmir.olk.feature.messages.ChatViewModel
import com.openlibrarykashmir.olk.feature.messages.MessagesViewModel
import com.openlibrarykashmir.olk.feature.mybooks.AddBookViewModel
import com.openlibrarykashmir.olk.feature.mybooks.CoverUploader
import com.openlibrarykashmir.olk.feature.mybooks.DeviceCoverUploader
import com.openlibrarykashmir.olk.feature.mybooks.EditBookViewModel
import com.openlibrarykashmir.olk.feature.mybooks.MyBooksViewModel
import com.openlibrarykashmir.olk.feature.requests.RequestsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single<CoverUploader> { DeviceCoverUploader(androidContext(), get()) }
    viewModel { MainViewModel(get()) }
    viewModel { AuthViewModel(get()) }
    viewModel { BrowseViewModel(get()) }
    viewModel { (bookId: String) -> BookDetailViewModel(bookId, get(), get()) }
    viewModel { MyBooksViewModel(get(), get()) }
    viewModel { RequestsViewModel(get(), get()) }
    viewModel { MessagesViewModel(get(), get()) }
    viewModel { (requestId: String) -> ChatViewModel(requestId, get(), get()) }
    viewModel { (bookId: String) -> EditBookViewModel(bookId, get(), get(), get(), get()) }
    viewModel { AddBookViewModel(get(), get(), get()) }
}
