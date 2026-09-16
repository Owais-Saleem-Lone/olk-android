package com.openlibrarykashmir.olk.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.openlibrarykashmir.olk.feature.auth.AuthScreen
import com.openlibrarykashmir.olk.feature.bookdetail.BookDetailScreen
import com.openlibrarykashmir.olk.feature.browse.BrowseScreen
import kotlinx.serialization.Serializable

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

@Composable
fun OlkNavHost(
    isSignedIn: Boolean,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = if (isSignedIn) BrowseRoute else AuthRoute,
        modifier = modifier,
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
    }
}
