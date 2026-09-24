package com.openlibrarykashmir.olk

import com.openlibrarykashmir.olk.core.data.repository.NotificationsRepository
import com.openlibrarykashmir.olk.core.data.repository.UserNotification
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.feature.notifications.NotificationTarget
import com.openlibrarykashmir.olk.feature.notifications.NotificationsViewModel
import com.openlibrarykashmir.olk.feature.notifications.badgeLabel
import com.openlibrarykashmir.olk.feature.notifications.notificationTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun notification(
        id: String,
        createdAt: String = "2026-09-17T12:00:00+00:00",
        read: Boolean? = false,
        type: String = "book_requested",
        link: String? = "/requests",
    ) = UserNotification(
        id = id,
        type = type,
        title = "Notification $id",
        body = null,
        link = link,
        read = read,
        createdAt = createdAt,
    )

    private class FakeAuth(var userId: String? = "me") : AuthRepository {
        override val authState: Flow<AuthState> = emptyFlow()
        override fun currentUserId() = userId
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
    }

    private class FakeNotifications(
        var rows: Map<String, List<UserNotification>> = emptyMap(),
        var failMarkRead: Boolean = false,
    ) : NotificationsRepository {
        val live = MutableSharedFlow<UserNotification>()
        val markedRead = mutableListOf<List<String>>()
        var loads = 0

        override suspend fun recent(userId: String, limit: Int): List<UserNotification> {
            loads++
            return rows[userId].orEmpty()
        }

        override suspend fun markRead(ids: List<String>) {
            markedRead += ids
            if (failMarkRead) error("nope")
        }

        override fun changes(userId: String): Flow<UserNotification> = live
    }

    private fun viewModel(
        repository: FakeNotifications,
        auth: FakeAuth = FakeAuth(),
    ) = NotificationsViewModel(repository, auth)

    @Test
    fun `loads notifications and counts the unread ones`() = runTest {
        val repository = FakeNotifications(
            rows = mapOf("me" to listOf(notification("a"), notification("b", read = true), notification("c", read = null))),
        )
        val vm = viewModel(repository)

        vm.start()
        advanceUntilIdle()

        assertEquals(3, vm.uiState.value.notifications.size)
        // A null `read` counts as unread: the column is nullable.
        assertEquals(2, vm.uiState.value.unreadCount)
    }

    @Test
    fun `a notification arriving live is added without waiting for a reload`() = runTest {
        val repository = FakeNotifications(rows = mapOf("me" to listOf(notification("old", createdAt = "2026-09-17T10:00:00+00:00"))))
        val vm = viewModel(repository)
        vm.start()
        advanceUntilIdle()

        repository.live.emit(notification("new", createdAt = "2026-09-17T13:00:00+00:00"))
        advanceUntilIdle()

        assertEquals(listOf("new", "old"), vm.uiState.value.notifications.map { it.id })
        assertEquals(2, vm.uiState.value.unreadCount)
    }

    // The same account may be reading notifications on the website; that arrives
    // as a Realtime UPDATE and has to clear the badge here too.
    @Test
    fun `a notification read elsewhere stops counting as unread`() = runTest {
        val repository = FakeNotifications(rows = mapOf("me" to listOf(notification("a"))))
        val vm = viewModel(repository)
        vm.start()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.unreadCount)

        repository.live.emit(notification("a", read = true))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.notifications.size)
        assertEquals(0, vm.uiState.value.unreadCount)
    }

    @Test
    fun `marking one read updates the badge straight away`() = runTest {
        val repository = FakeNotifications(rows = mapOf("me" to listOf(notification("a"), notification("b"))))
        val vm = viewModel(repository)
        vm.start()
        advanceUntilIdle()

        vm.markRead(vm.uiState.value.notifications.first { it.id == "a" })
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.unreadCount)
        assertEquals(listOf(listOf("a")), repository.markedRead)
    }

    @Test
    fun `a notification that is already read is not written again`() = runTest {
        val repository = FakeNotifications(rows = mapOf("me" to listOf(notification("a", read = true))))
        val vm = viewModel(repository)
        vm.start()
        advanceUntilIdle()

        vm.markRead(vm.uiState.value.notifications.first())
        advanceUntilIdle()

        assertTrue(repository.markedRead.isEmpty())
    }

    @Test
    fun `a failed mark-read puts the unread mark back`() = runTest {
        val repository = FakeNotifications(
            rows = mapOf("me" to listOf(notification("a"), notification("b"))),
            failMarkRead = true,
        )
        val vm = viewModel(repository)
        vm.start()
        advanceUntilIdle()

        vm.markAllRead()
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.unreadCount)
    }

    @Test
    fun `mark all read covers every unread notification`() = runTest {
        val repository = FakeNotifications(
            rows = mapOf("me" to listOf(notification("a"), notification("b", read = true), notification("c"))),
        )
        val vm = viewModel(repository)
        vm.start()
        advanceUntilIdle()

        vm.markAllRead()
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.unreadCount)
        assertEquals(listOf(listOf("a", "c")), repository.markedRead)
    }

    // The view model is shared by the whole app and outlives a sign-out, so a
    // second account must never see the first one's notifications.
    @Test
    fun `signing in as someone else starts from an empty list`() = runTest {
        val repository = FakeNotifications(
            rows = mapOf("me" to listOf(notification("mine")), "you" to emptyList()),
        )
        val auth = FakeAuth()
        val vm = viewModel(repository, auth)
        vm.start()
        advanceUntilIdle()
        assertEquals(listOf("mine"), vm.uiState.value.notifications.map { it.id })

        auth.userId = "you"
        vm.start()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.notifications.isEmpty())
    }

    @Test
    fun `a signed-out start shows nothing and does not load`() = runTest {
        val repository = FakeNotifications()
        val vm = viewModel(repository, FakeAuth(userId = null))

        vm.start()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.notifications.isEmpty())
        assertEquals(false, vm.uiState.value.isLoading)
        assertEquals(0, repository.loads)
    }

    @Test
    fun `a failed load reports an error rather than an endless spinner`() = runTest {
        val repository = object : NotificationsRepository {
            override suspend fun recent(userId: String, limit: Int): List<UserNotification> =
                throw RuntimeException("Unable to resolve host")
            override suspend fun markRead(ids: List<String>) = Unit
            override fun changes(userId: String): Flow<UserNotification> = emptyFlow()
        }
        val vm = NotificationsViewModel(repository, FakeAuth())

        vm.start()
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.isLoading)
        assertEquals("No connection. Check your network and try again.", vm.uiState.value.error)
    }

    @Test
    fun `links map onto the screens the app has`() {
        assertEquals(NotificationTarget.Requests, notificationTarget("/requests"))
        assertEquals(NotificationTarget.MyBooks, notificationTarget("/my-books"))
        assertEquals(NotificationTarget.Messages, notificationTarget("/messages"))
        assertEquals(NotificationTarget.Chat("r1"), notificationTarget("/messages/r1"))
        assertEquals(NotificationTarget.Book("b1"), notificationTarget("/books/b1"))
        // A trailing slash or a query string is still the same page.
        assertEquals(NotificationTarget.Requests, notificationTarget("/requests/"))
        assertEquals(NotificationTarget.Book("b1"), notificationTarget("/books/b1?from=email"))
    }

    @Test
    fun `links the app has no screen for say so instead of doing nothing`() {
        assertEquals(NotificationTarget.WebsiteOnly, notificationTarget("/profile"))
        // The website's club-request and event-creation forms have no screen here either.
        assertEquals(NotificationTarget.RequestClub, notificationTarget("/clubs/create"))
        assertEquals(NotificationTarget.WebsiteOnly, notificationTarget("/clubs/c1/events/create"))
        // The list itself, or no link at all: already there, nothing to open.
        assertEquals(NotificationTarget.None, notificationTarget("/notifications"))
        assertEquals(NotificationTarget.None, notificationTarget(null))
        assertEquals(NotificationTarget.None, notificationTarget("  "))
    }

    @Test
    fun `club links open the club in the app`() {
        assertEquals(NotificationTarget.Club("c1"), notificationTarget("/clubs/c1"))
        assertEquals(NotificationTarget.Clubs, notificationTarget("/clubs"))
    }

    @Test
    fun `club links do not open while an admin has clubs switched off`() {
        assertEquals(NotificationTarget.ClubsOff, notificationTarget("/clubs/c1", clubsEnabled = false))
        assertEquals(NotificationTarget.ClubsOff, notificationTarget("/clubs", clubsEnabled = false))
        // Everything else is unaffected.
        assertEquals(NotificationTarget.Book("b1"), notificationTarget("/books/b1", clubsEnabled = false))
    }

    @Test
    fun `an admin team reply opens Contact Admin in the app`() {
        // What trg_notify_support_reply writes.
        assertEquals(NotificationTarget.Support, notificationTarget("/support"))
        assertEquals(NotificationTarget.JoinTeam, notificationTarget("/join-team"))
    }

    @Test
    fun `event links open the event in the app`() {
        // What trg_notify_club_event_created writes.
        assertEquals(NotificationTarget.Event("e1"), notificationTarget("/events/e1"))
        assertEquals(NotificationTarget.Events, notificationTarget("/events"))
    }

    @Test
    fun `event links do not open while events, or the clubs they belong to, are switched off`() {
        assertEquals(NotificationTarget.EventsOff, notificationTarget("/events/e1", eventsEnabled = false))
        assertEquals(NotificationTarget.EventsOff, notificationTarget("/events", eventsEnabled = false))
        assertEquals(NotificationTarget.EventsOff, notificationTarget("/events/e1", clubsEnabled = false))
        // Everything else is unaffected.
        assertEquals(NotificationTarget.Club("c1"), notificationTarget("/clubs/c1", eventsEnabled = false))
    }

    @Test
    fun `message links do not open while an admin has messaging switched off`() {
        assertEquals(NotificationTarget.MessagingOff, notificationTarget("/messages/r1", messagingEnabled = false))
        assertEquals(NotificationTarget.MessagingOff, notificationTarget("/messages", messagingEnabled = false))
        // Everything else is unaffected.
        assertEquals(NotificationTarget.Requests, notificationTarget("/requests", messagingEnabled = false))
    }

    @Test
    fun `the badge caps the way the web bell does`() {
        assertEquals("1", badgeLabel(1))
        assertEquals("9", badgeLabel(9))
        assertEquals("9+", badgeLabel(10))
        assertEquals("9+", badgeLabel(250))
    }
}
