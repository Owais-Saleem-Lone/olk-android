package com.openlibrarykashmir.olk.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.openlibrarykashmir.olk.feature.auth.AuthScreen
import com.openlibrarykashmir.olk.feature.bookdetail.BookDetailScreen
import com.openlibrarykashmir.olk.feature.browse.BrowseScreen
import com.openlibrarykashmir.olk.feature.mybooks.EditBookScreen
import com.openlibrarykashmir.olk.feature.mybooks.MyBooksScreen
import com.openlibrarykashmir.olk.feature.requests.RequestsScreen
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/**
 * Type-safe routes. Navigation Compose resolves these from the `@Serializable` type
 * itself, so a typo is a compile error rather than a crash at runtime.
 */
@Serializable
data object AuthRoute

@Serializable
data object BrowseRoute

@Serializable
data class BookDetailRoute(val bookId: String)

@Serializable
data object RequestsRoute

@Serializable
data object MyBooksRoute

@Serializable
data class EditBookRoute(val bookId: String)

private enum class TopLevelTab(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
) {
    BROWSE(BrowseRoute, BrowseRoute::class, "Browse", Icons.Default.Search),
    REQUESTS(RequestsRoute, RequestsRoute::class, "Requests", Icons.Default.SwapHoriz),
    MY_BOOKS(MyBooksRoute, MyBooksRoute::class, "My Books", Icons.AutoMirrored.Filled.LibraryBooks),
}

/** Key under which the edit screen hands its result message back to My Books. */
private const val RESULT_MESSAGE_KEY = "result_message"

@Composable
fun OlkNavHost(
    isSignedIn: Boolean,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = isSignedIn &&
        TopLevelTab.entries.any { tab -> destination?.hasRoute(tab.routeClass) == true }

    Scaffold(
        modifier = modifier,
        // Each screen draws its own top bar and handles the status bar itself; this
        // outer scaffold only owns the bottom navigation.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelTab.entries.forEach { tab ->
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
        },
    ) { outerPadding ->
        // Only the bottom bar's height matters here. Consuming it stops the inner
        // screens' scaffolds from adding the navigation-bar inset a second time.
        val bottom = PaddingValues(bottom = outerPadding.calculateBottomPadding())

        NavHost(
            navController = navController,
            startDestination = if (isSignedIn) BrowseRoute else AuthRoute,
            modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
        ) {
            composable<AuthRoute> {
                AuthScreen()
            }
            composable<BrowseRoute> {
                BrowseScreen(
                    onBookClick = { bookId -> navController.navigate(BookDetailRoute(bookId)) },
                )
            }
            composable<BookDetailRoute> { entry ->
                BookDetailScreen(
                    bookId = entry.toRoute<BookDetailRoute>().bookId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<RequestsRoute> {
                RequestsScreen()
            }
            composable<MyBooksRoute> { entry ->
                val resultMessage by entry.savedStateHandle
                    .getStateFlow<String?>(RESULT_MESSAGE_KEY, null)
                    .collectAsStateWithLifecycle()
                MyBooksScreen(
                    onBookClick = { bookId -> navController.navigate(EditBookRoute(bookId)) },
                    resultMessage = resultMessage,
                    onResultMessageShown = { entry.savedStateHandle[RESULT_MESSAGE_KEY] = null },
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
