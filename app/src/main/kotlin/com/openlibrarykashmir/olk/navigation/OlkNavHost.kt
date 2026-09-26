package com.openlibrarykashmir.olk.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.openlibrarykashmir.olk.core.data.repository.FeatureFlags
import com.openlibrarykashmir.olk.feature.auth.AuthScreen
import com.openlibrarykashmir.olk.feature.bookdetail.BookDetailScreen
import com.openlibrarykashmir.olk.feature.browse.BrowseScreen
import com.openlibrarykashmir.olk.feature.clubs.ClubDetailScreen
import com.openlibrarykashmir.olk.feature.clubs.ClubsScreen
import com.openlibrarykashmir.olk.feature.clubs.RequestClubScreen
import com.openlibrarykashmir.olk.feature.events.CreateEventScreen
import com.openlibrarykashmir.olk.feature.events.EventDetailScreen
import com.openlibrarykashmir.olk.feature.events.EventsScreen
import com.openlibrarykashmir.olk.feature.home.HomeScreen
import com.openlibrarykashmir.olk.feature.messages.ChatScreen
import com.openlibrarykashmir.olk.feature.messages.MessagesScreen
import com.openlibrarykashmir.olk.feature.mybooks.AddBookScreen
import com.openlibrarykashmir.olk.feature.mybooks.EditBookScreen
import com.openlibrarykashmir.olk.feature.mybooks.MyBooksScreen
import com.openlibrarykashmir.olk.feature.mybooks.ScanIsbnScreen
import com.openlibrarykashmir.olk.feature.notifications.NotificationBell
import com.openlibrarykashmir.olk.feature.notifications.NotificationTarget
import com.openlibrarykashmir.olk.feature.notifications.NotificationsScreen
import com.openlibrarykashmir.olk.feature.notifications.NotificationsViewModel
import com.openlibrarykashmir.olk.feature.people.BlockedMembersScreen
import com.openlibrarykashmir.olk.feature.people.UserProfileScreen
import com.openlibrarykashmir.olk.feature.profile.ProfileScreen
import com.openlibrarykashmir.olk.feature.requests.RequestsScreen
import com.openlibrarykashmir.olk.feature.support.SupportScreen
import com.openlibrarykashmir.olk.feature.team.JoinTeamScreen
import com.openlibrarykashmir.olk.feature.logo.LogoScreen
import com.openlibrarykashmir.olk.ui.Suspension
import com.openlibrarykashmir.olk.ui.SuspensionBanner
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import kotlin.reflect.KClass

/**
 * Type-safe routes. Navigation Compose resolves these from the `@Serializable` type
 * itself, so a typo is a compile error rather than a crash at runtime.
 */
@Serializable
data object AuthRoute

@Serializable
data object HomeRoute

/** Search results; [query] is what was typed into Home's search box, if anything. */
@Serializable
data class BrowseRoute(val query: String = "")

@Serializable
data class BookDetailRoute(val bookId: String)

@Serializable
data object RequestsRoute

@Serializable
data object MessagesRoute

@Serializable
data class ChatRoute(val requestId: String)

@Serializable
data object MyBooksRoute

@Serializable
data class EditBookRoute(val bookId: String)

@Serializable
data object AddBookRoute

@Serializable
data object ScanIsbnRoute

@Serializable
data object NotificationsRoute

@Serializable
data object ProfileRoute

/** Another reader's public profile. */
@Serializable
data class UserProfileRoute(val userId: String)

@Serializable
data object BlockedMembersRoute

@Serializable
data object ClubsRoute

@Serializable
data class ClubDetailRoute(val clubId: String)

@Serializable
data object RequestClubRoute

@Serializable
data class CreateEventRoute(val clubId: String, val clubName: String)

@Serializable
data object EventsRoute

/** "Contact Admin": the member's private thread with the admin team. */
@Serializable
data object SupportRoute

@Serializable
data object JoinTeamRoute

@Serializable
data object LogoRoute

@Serializable
data class EventDetailRoute(val eventId: String)

private enum class TopLevelTab(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
) {
    HOME(HomeRoute, HomeRoute::class, "Home", Icons.Default.Home),
    REQUESTS(RequestsRoute, RequestsRoute::class, "Requests", Icons.Default.SwapHoriz),
    MESSAGES(MessagesRoute, MessagesRoute::class, "Messages", Icons.AutoMirrored.Filled.Chat),
    MY_BOOKS(MyBooksRoute, MyBooksRoute::class, "My Books", Icons.AutoMirrored.Filled.LibraryBooks),
    ;

    /** Whether an admin has this feature switched on; see `platform_settings`. */
    fun isEnabled(flags: FeatureFlags) = this != MESSAGES || flags.messages
}

/**
 * Opens what a notification points at. A tab target goes through the same
 * single-top/restore-state navigation as tapping the tab itself, so following a
 * notification never stacks a second copy of Browse or Requests.
 *
 * The targets that go nowhere ([NotificationTarget.None], `WebsiteOnly`,
 * `MessagingOff`) are handled on the screen, where there is a snackbar to say so.
 */
private fun NavHostController.openNotificationTarget(target: NotificationTarget) {
    when (target) {
        is NotificationTarget.Book -> navigate(BookDetailRoute(target.bookId))
        is NotificationTarget.Club -> navigate(ClubDetailRoute(target.clubId))
        NotificationTarget.Clubs -> navigate(ClubsRoute)
        NotificationTarget.RequestClub -> navigate(RequestClubRoute)
        is NotificationTarget.Event -> navigate(EventDetailRoute(target.eventId))
        NotificationTarget.Events -> navigate(EventsRoute)
        NotificationTarget.Support -> navigate(SupportRoute)
        NotificationTarget.JoinTeam -> navigate(JoinTeamRoute)
        is NotificationTarget.Chat -> navigate(ChatRoute(target.requestId))
        NotificationTarget.Requests -> navigateToTab(RequestsRoute)
        NotificationTarget.Messages -> navigateToTab(MessagesRoute)
        NotificationTarget.MyBooks -> navigateToTab(MyBooksRoute)
        NotificationTarget.None,
        NotificationTarget.WebsiteOnly,
        NotificationTarget.MessagingOff,
        NotificationTarget.ClubsOff,
        NotificationTarget.EventsOff,
        -> Unit
    }
}

private fun NavHostController.navigateToTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Key under which the edit screen hands its result message back to My Books. */
private const val RESULT_MESSAGE_KEY = "result_message"

/** Key under which the scanner hands an ISBN back to the Add book form. */
private const val SCANNED_ISBN_KEY = "scanned_isbn"

@Composable
fun OlkNavHost(
    isSignedIn: Boolean,
    featureFlags: FeatureFlags,
    /** Null unless the signed-in account is suspended right now. */
    suspension: Suspension? = null,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    // Deliberately every tab, not only the enabled ones: switching Messages off
    // while someone is on that screen should not leave them with no navigation.
    val showBottomBar = isSignedIn &&
        TopLevelTab.entries.any { tab -> destination?.hasRoute(tab.routeClass) == true }

    // One shared instance for the whole app: the bell in each top bar and the
    // notifications screen itself must agree on what has been read. Resolved
    // here, outside any `composable {}`, so the owner is the activity rather
    // than a single back-stack entry.
    val notificationsViewModel: NotificationsViewModel = koinViewModel()
    val notificationsState by notificationsViewModel.uiState.collectAsStateWithLifecycle()

    LifecycleStartEffect(isSignedIn, notificationsViewModel) {
        if (isSignedIn) notificationsViewModel.start()
        onStopOrDispose { notificationsViewModel.stop() }
    }

    // Every top-level screen's top bar: notifications, then your profile.
    val bell: @Composable RowScope.() -> Unit = {
        NotificationBell(
            unreadCount = notificationsState.unreadCount,
            onClick = { navController.navigate(NotificationsRoute) },
        )
        IconButton(onClick = { navController.navigate(ProfileRoute) }) {
            Icon(Icons.Outlined.AccountCircle, contentDescription = "Your profile")
        }
    }

    Scaffold(
        modifier = modifier,
        // Each screen draws its own top bar and handles the status bar itself; this
        // outer scaffold only owns the bottom navigation.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // Stacked: the suspension notice sits above the tabs.
            Column {
                // On every screen while it lasts, as the website's notice is -- except
                // the two it points to, which already say it.
                val onSuspensionPages = destination?.hasRoute(SupportRoute::class) == true ||
                    destination?.hasRoute(ProfileRoute::class) == true
                if (isSignedIn && suspension != null && !onSuspensionPages) {
                    SuspensionBanner(
                        suspension = suspension,
                        onSeeWhy = { navController.navigate(ProfileRoute) },
                        onContactAdmin = { navController.navigate(SupportRoute) },
                        // With no navigation bar below it, it sits on the screen's
                        // bottom edge and has to clear the system bar itself.
                        modifier = if (showBottomBar) Modifier else Modifier.navigationBarsPadding(),
                    )
                }
                if (showBottomBar) {
                    NavigationBar {
                        TopLevelTab.entries.filter { it.isEnabled(featureFlags) }.forEach { tab ->
                            NavigationBarItem(
                                selected = destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        // Standard tab behaviour: one copy of each tab on the
                                        // stack, and each tab keeps its own scroll and state.
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { outerPadding ->
        // Only the bottom bar's height matters here. Consuming it stops the inner
        // screens' scaffolds from adding the navigation-bar inset a second time.
        val bottom = PaddingValues(bottom = outerPadding.calculateBottomPadding())

        NavHost(
            navController = navController,
            startDestination = if (isSignedIn) HomeRoute else AuthRoute,
            modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
        ) {
            composable<AuthRoute> {
                AuthScreen()
            }
            composable<HomeRoute> {
                HomeScreen(
                    onSearch = { query -> navController.navigate(BrowseRoute(query)) },
                    onBookClick = { bookId -> navController.navigate(BookDetailRoute(bookId)) },
                    // Same as the website's button, which goes to My Books.
                    onStartSharing = { navController.navigateToTab(MyBooksRoute) },
                    onOpenClubs = { navController.navigate(ClubsRoute) },
                    onOpenEvents = { navController.navigate(EventsRoute) },
                    onOpenLogo = { navController.navigate(LogoRoute) },
                    onOpenProfile = { userId -> navController.navigate(UserProfileRoute(userId)) },
                    onContactAdmin = { navController.navigate(SupportRoute) },
                    clubsEnabled = featureFlags.clubs,
                    eventsEnabled = featureFlags.events,
                    actions = bell,
                )
            }
            composable<ClubsRoute> {
                ClubsScreen(
                    onClubClick = { clubId -> navController.navigate(ClubDetailRoute(clubId)) },
                    onBack = { navController.popBackStack() },
                    onRequestClub = { navController.navigate(RequestClubRoute) },
                )
            }
            composable<RequestClubRoute> {
                RequestClubScreen(
                    onBack = { navController.popBackStack() },
                    onOpenClub = { clubId -> navController.navigate(ClubDetailRoute(clubId)) },
                )
            }
            composable<CreateEventRoute> { entry ->
                val route = entry.toRoute<CreateEventRoute>()
                CreateEventScreen(
                    clubId = route.clubId,
                    clubName = route.clubName,
                    onBack = { navController.popBackStack() },
                    // The form is done with: open the new event in its place.
                    onCreated = { eventId ->
                        navController.navigate(EventDetailRoute(eventId)) {
                            popUpTo<CreateEventRoute> { inclusive = true }
                        }
                    },
                )
            }
            composable<ClubDetailRoute> { entry ->
                ClubDetailScreen(
                    clubId = entry.toRoute<ClubDetailRoute>().clubId,
                    onBack = { navController.popBackStack() },
                    onMemberClick = { userId -> navController.navigate(UserProfileRoute(userId)) },
                    onEventClick = { eventId -> navController.navigate(EventDetailRoute(eventId)) },
                    onScheduleEvent = { clubId, clubName -> navController.navigate(CreateEventRoute(clubId, clubName)) },
                    eventsEnabled = featureFlags.events,
                )
            }
            composable<EventsRoute> {
                EventsScreen(
                    onEventClick = { eventId -> navController.navigate(EventDetailRoute(eventId)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable<EventDetailRoute> { entry ->
                EventDetailScreen(
                    eventId = entry.toRoute<EventDetailRoute>().eventId,
                    onBack = { navController.popBackStack() },
                    onClubClick = { clubId -> navController.navigate(ClubDetailRoute(clubId)) },
                    onPersonClick = { userId -> navController.navigate(UserProfileRoute(userId)) },
                )
            }
            composable<BrowseRoute> { entry ->
                BrowseScreen(
                    onBookClick = { bookId -> navController.navigate(BookDetailRoute(bookId)) },
                    onBack = { navController.popBackStack() },
                    initialQuery = entry.toRoute<BrowseRoute>().query,
                )
            }
            composable<BookDetailRoute> { entry ->
                BookDetailScreen(
                    bookId = entry.toRoute<BookDetailRoute>().bookId,
                    onBack = { navController.popBackStack() },
                    onOpenProfile = { userId -> navController.navigate(UserProfileRoute(userId)) },
                )
            }
            composable<UserProfileRoute> { entry ->
                UserProfileScreen(
                    userId = entry.toRoute<UserProfileRoute>().userId,
                    onBack = { navController.popBackStack() },
                    onBookClick = { bookId -> navController.navigate(BookDetailRoute(bookId)) },
                )
            }
            composable<RequestsRoute> {
                RequestsScreen(
                    onMessage = { requestId -> navController.navigate(ChatRoute(requestId)) },
                    onOpenProfile = { userId -> navController.navigate(UserProfileRoute(userId)) },
                    messagingEnabled = featureFlags.messages,
                    ratingsEnabled = featureFlags.ratings,
                    actions = bell,
                )
            }
            composable<MessagesRoute> {
                MessagesScreen(
                    onConversationClick = { requestId -> navController.navigate(ChatRoute(requestId)) },
                    actions = bell,
                )
            }
            composable<ProfileRoute> {
                ProfileScreen(
                    onBack = { navController.popBackStack() },
                    onContactAdmin = { navController.navigate(SupportRoute) },
                    onJoinTeam = { navController.navigate(JoinTeamRoute) },
                    onBlockedMembers = { navController.navigate(BlockedMembersRoute) },
                )
            }
            composable<BlockedMembersRoute> {
                BlockedMembersScreen(
                    onBack = { navController.popBackStack() },
                    onOpenProfile = { userId -> navController.navigate(UserProfileRoute(userId)) },
                )
            }
            composable<SupportRoute> {
                SupportScreen(onBack = { navController.popBackStack() })
            }
            composable<LogoRoute> {
                LogoScreen(onBack = { navController.popBackStack() })
            }
            composable<JoinTeamRoute> {
                JoinTeamScreen(onBack = { navController.popBackStack() })
            }
            composable<NotificationsRoute> {
                NotificationsScreen(
                    viewModel = notificationsViewModel,
                    messagingEnabled = featureFlags.messages,
                    clubsEnabled = featureFlags.clubs,
                    eventsEnabled = featureFlags.events,
                    onOpen = { target -> navController.openNotificationTarget(target) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable<ChatRoute> { entry ->
                ChatScreen(
                    requestId = entry.toRoute<ChatRoute>().requestId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<MyBooksRoute> { entry ->
                val resultMessage by entry.savedStateHandle
                    .getStateFlow<String?>(RESULT_MESSAGE_KEY, null)
                    .collectAsStateWithLifecycle()
                MyBooksScreen(
                    onBookClick = { bookId -> navController.navigate(EditBookRoute(bookId)) },
                    onOpenBook = { bookId -> navController.navigate(BookDetailRoute(bookId)) },
                    onAddBook = { navController.navigate(AddBookRoute) },
                    showWishlist = featureFlags.wishlists,
                    resultMessage = resultMessage,
                    onResultMessageShown = { entry.savedStateHandle[RESULT_MESSAGE_KEY] = null },
                    actions = bell,
                )
            }
            composable<AddBookRoute> { entry ->
                val scannedIsbn by entry.savedStateHandle
                    .getStateFlow<String?>(SCANNED_ISBN_KEY, null)
                    .collectAsStateWithLifecycle()
                AddBookScreen(
                    onBack = { navController.popBackStack() },
                    onScanIsbn = { navController.navigate(ScanIsbnRoute) },
                    scannedIsbn = scannedIsbn,
                    onScannedIsbnUsed = { entry.savedStateHandle[SCANNED_ISBN_KEY] = null },
                    onDone = { message ->
                        navController.previousBackStackEntry?.savedStateHandle?.set(RESULT_MESSAGE_KEY, message)
                        navController.popBackStack()
                    },
                )
            }
            composable<ScanIsbnRoute> {
                ScanIsbnScreen(
                    onBack = { navController.popBackStack() },
                    onIsbn = { isbn ->
                        navController.previousBackStackEntry?.savedStateHandle?.set(SCANNED_ISBN_KEY, isbn)
                        navController.popBackStack()
                    },
                )
            }
            composable<EditBookRoute> { entry ->
                EditBookScreen(
                    bookId = entry.toRoute<EditBookRoute>().bookId,
                    onBack = { navController.popBackStack() },
                    onDone = { message ->
                        navController.previousBackStackEntry?.savedStateHandle?.set(RESULT_MESSAGE_KEY, message)
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}
