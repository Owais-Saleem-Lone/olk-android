package com.openlibrarykashmir.olk.di

import com.openlibrarykashmir.olk.MainViewModel
import com.openlibrarykashmir.olk.feature.auth.AuthViewModel
import com.openlibrarykashmir.olk.feature.bookdetail.BookDetailViewModel
import com.openlibrarykashmir.olk.feature.browse.BrowseViewModel
import com.openlibrarykashmir.olk.feature.mybooks.EditBookViewModel
import com.openlibrarykashmir.olk.feature.mybooks.MyBooksViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    viewModel { MainViewModel(get()) }
    viewModel { AuthViewModel(get()) }
    viewModel { BrowseViewModel(get()) }
    viewModel { (bookId: String) -> BookDetailViewModel(bookId, get(), get()) }
    viewModel { MyBooksViewModel(get(), get()) }
    viewModel { (bookId: String) -> EditBookViewModel(bookId, get(), get(), get()) }
}
