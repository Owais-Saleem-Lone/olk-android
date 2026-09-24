package com.openlibrarykashmir.olk.di

import com.openlibrarykashmir.olk.core.data.model.CoverBucket
import com.openlibrarykashmir.olk.feature.clubs.RequestClubViewModel
import com.openlibrarykashmir.olk.feature.events.CreateEventViewModel
import com.openlibrarykashmir.olk.feature.mybooks.DeviceOrganiserCoverUploader
import com.openlibrarykashmir.olk.MainViewModel
import com.openlibrarykashmir.olk.feature.auth.AuthViewModel
import com.openlibrarykashmir.olk.feature.bookdetail.BookDetailViewModel
import com.openlibrarykashmir.olk.feature.bookdetail.BookNotesViewModel
import com.openlibrarykashmir.olk.feature.browse.BrowseViewModel
import com.openlibrarykashmir.olk.feature.clubs.ClubDetailViewModel
import com.openlibrarykashmir.olk.feature.clubs.ClubsViewModel
import com.openlibrarykashmir.olk.feature.events.EventDetailViewModel
import com.openlibrarykashmir.olk.feature.events.EventsViewModel
import com.openlibrarykashmir.olk.feature.home.HomeViewModel
import com.openlibrarykashmir.olk.feature.lists.SavedViewModel
import com.openlibrarykashmir.olk.feature.lists.WishlistViewModel
import com.openlibrarykashmir.olk.feature.messages.ChatViewModel
import com.openlibrarykashmir.olk.feature.messages.MessagesViewModel
import com.openlibrarykashmir.olk.feature.mybooks.AddBookViewModel
import com.openlibrarykashmir.olk.feature.mybooks.CoverUploader
import com.openlibrarykashmir.olk.feature.mybooks.DeviceCoverUploader
import com.openlibrarykashmir.olk.feature.mybooks.EditBookViewModel
import com.openlibrarykashmir.olk.feature.mybooks.MyBooksViewModel
import com.openlibrarykashmir.olk.feature.notifications.NotificationsViewModel
import com.openlibrarykashmir.olk.feature.people.UserProfileViewModel
import com.openlibrarykashmir.olk.feature.profile.DeviceLocator
import com.openlibrarykashmir.olk.feature.profile.Locator
import com.openlibrarykashmir.olk.feature.profile.ProfileViewModel
import com.openlibrarykashmir.olk.feature.requests.RequestsViewModel
import com.openlibrarykashmir.olk.feature.support.SupportViewModel
import com.openlibrarykashmir.olk.feature.team.CvReader
import com.openlibrarykashmir.olk.feature.team.DeviceCvReader
import com.openlibrarykashmir.olk.feature.team.JoinTeamViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    single<CoverUploader> { DeviceCoverUploader(androidContext(), get()) }
    single<CoverUploader>(named(CoverBucket.CLUBS.id)) { DeviceOrganiserCoverUploader(androidContext(), get(), CoverBucket.CLUBS) }
    single<CoverUploader>(named(CoverBucket.EVENTS.id)) { DeviceOrganiserCoverUploader(androidContext(), get(), CoverBucket.EVENTS) }
    single<Locator> { DeviceLocator(androidContext()) }
    single<CvReader> { DeviceCvReader(androidContext()) }
    viewModel { MainViewModel(get(), get(), get()) }
    viewModel { AuthViewModel(get()) }
    viewModel { HomeViewModel(get()) }
    viewModel { (query: String) -> BrowseViewModel(get(), query) }
    viewModel { (bookId: String) -> BookDetailViewModel(bookId, get(), get()) }
    viewModel { (bookId: String) -> BookNotesViewModel(bookId, get(), get()) }
    viewModel { MyBooksViewModel(get(), get(), get()) }
    viewModel { RequestsViewModel(get(), get()) }
    viewModel { MessagesViewModel(get(), get()) }
    viewModel { NotificationsViewModel(get(), get()) }
    viewModel { ProfileViewModel(get(), get(), get(), get()) }
    viewModel { (requestId: String) -> ChatViewModel(requestId, get(), get()) }
    viewModel { (bookId: String) -> EditBookViewModel(bookId, get(), get(), get(), get()) }
    viewModel { AddBookViewModel(get(), get(), get(), get()) }
    viewModel { SavedViewModel(get(), get()) }
    viewModel { WishlistViewModel(get(), get()) }
    viewModel { (userId: String) -> UserProfileViewModel(userId, get()) }
    viewModel { ClubsViewModel(get(), get()) }
    viewModel { RequestClubViewModel(get(), get(), get(), get(named(CoverBucket.CLUBS.id))) }
    viewModel { (clubId: String, clubName: String) ->
        CreateEventViewModel(clubId, clubName, get(), get(), get(named(CoverBucket.EVENTS.id)))
    }
    viewModel { (clubId: String) -> ClubDetailViewModel(clubId, get(), get(), get()) }
    viewModel { EventsViewModel(get()) }
    viewModel { SupportViewModel(get(), get()) }
    viewModel { JoinTeamViewModel(get(), get(), get(), get()) }
    viewModel { (eventId: String) -> EventDetailViewModel(eventId, get(), get(), get()) }
}
