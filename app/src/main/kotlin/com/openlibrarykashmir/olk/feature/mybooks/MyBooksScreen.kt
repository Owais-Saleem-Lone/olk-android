package com.openlibrarykashmir.olk.feature.mybooks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.feature.lists.SavedTab
import com.openlibrarykashmir.olk.feature.lists.WishlistTab
import com.openlibrarykashmir.olk.feature.requests.DueLine
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyBooksScreen(
    onBookClick: (String) -> Unit,
    onOpenBook: (String) -> Unit,
    onAddBook: () -> Unit,
    showWishlist: Boolean,
    resultMessage: String?,
    onResultMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    viewModel: MyBooksViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val tabs = remember(showWishlist) {
        listOfNotNull(MyBooksTab.MINE, MyBooksTab.SAVED, MyBooksTab.WISHLIST.takeIf { showWishlist })
    }
    var selectedTabName by rememberSaveable { mutableStateOf(MyBooksTab.MINE.name) }
    // Falls back to Mine if the Wishlist tab is switched off while it is open.
    val selectedTab = tabs.firstOrNull { it.name == selectedTabName } ?: MyBooksTab.MINE

    LifecycleStartEffect(viewModel) {
        viewModel.refresh()
        onStopOrDispose {}
    }

    val scope = rememberCoroutineScope()
    LaunchedEffect(resultMessage) {
        if (resultMessage != null) {
            // Shown from the screen's scope: clearing the message changes this
            // effect's key, which would cancel a snackbar launched in here.
            scope.launch { snackbarHostState.showSnackbar(resultMessage) }
            onResultMessageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (selectedTab == MyBooksTab.MINE) {
                ExtendedFloatingActionButton(
                    onClick = onAddBook,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add book") },
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("My Books") },
                actions = actions,
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            PrimaryTabRow(selectedTabIndex = tabs.indexOf(selectedTab)) {
                tabs.forEach { tab ->
                    Tab(
                        selected = tab == selectedTab,
                        onClick = { selectedTabName = tab.name },
                        text = { Text(tab.label) },
                        // Tab's default draws unselected labels in the selected colour.
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (selectedTab) {
                MyBooksTab.MINE -> MineTab(state, viewModel, onBookClick, onOpenBook)
                MyBooksTab.SAVED -> SavedTab(onOpenBook = onOpenBook)
                MyBooksTab.WISHLIST -> WishlistTab(onOpenBook = onOpenBook)
            }
        }
    }
}

private enum class MyBooksTab(val label: String) {
    MINE("Mine"),
    SAVED("Saved"),
    WISHLIST("Wishlist"),
}

@Composable
private fun MineTab(
    state: MyBooksUiState,
    viewModel: MyBooksViewModel,
    onBookClick: (String) -> Unit,
    onOpenBook: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            state.error != null && state.books.isEmpty() && state.reading.isEmpty() -> CenteredMessage(
                text = state.error.orEmpty(),
                actionLabel = "Try again",
                onAction = viewModel::refresh,
            )

            state.books.isEmpty() && state.reading.isEmpty() -> CenteredMessage(
                text = "You haven't listed any books yet.\nTap Add book to share your first one.",
            )

            else -> LazyColumn(
                // Extra bottom space so the Add book button never covers the last card.
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.reading.isNotEmpty()) {
                    item { SectionTitle("Books you're reading") }
                    items(state.reading, key = { "reading-${it.request.id}" }) { reading ->
                        ReadingCard(reading, onClick = { onOpenBook(reading.request.book.id) })
                    }
                    item { SectionTitle("Your listings", Modifier.padding(top = 8.dp)) }
                }
                if (state.books.isEmpty()) {
                    item {
                        Text(
                            "You haven't listed any books yet. Tap Add book to share your first one.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.books, key = { it.id }) { book ->
                    MyBookCard(book = book, onClick = { onBookClick(book.id) })
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

/** A book handed over to this user: who it is from, when it is due, how far they are. */
@Composable
private fun ReadingCard(reading: ReadingNow, onClick: () -> Unit) {
    val request = reading.request
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(request.book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = (if (request.book.listingType == ListingType.DONATE) "Donated by " else "Lent by ") +
                    (request.otherParty?.displayName ?: "another reader"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DueLine(request)
            val pct = reading.progressPct ?: 0
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.weight(1f))
                Text("$pct%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "Update your progress on the Requests tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MyBookCard(book: Book, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 80.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (book.coverUrl.isNullOrBlank()) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(book.status, modifier = Modifier.padding(start = 8.dp))
                }
                if (!book.author.isNullOrBlank()) {
                    Text(
                        text = book.author.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = listOfNotNull(
                        when (book.listingType) {
                            ListingType.DONATE -> "Giving away"
                            ListingType.LEND -> "Lending" + (book.lendingDurationMonths?.let { " · $it mo" } ?: "")
                        },
                        book.genre,
                        "In circulation".takeIf { book.acquiredViaDonation },
                        "Read ${book.readCount}×".takeIf { book.readCount > 0 },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
internal fun StatusChip(status: BookStatus, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (label, container, content) = when (status) {
        BookStatus.AVAILABLE -> Triple("Available", colors.primaryContainer, colors.onPrimaryContainer)
        BookStatus.UNAVAILABLE -> Triple("Unavailable", colors.tertiaryContainer, colors.onTertiaryContainer)
        BookStatus.GIVEN -> Triple("Given away", colors.surfaceVariant, colors.onSurfaceVariant)
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun CenteredMessage(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(actionLabel) }
        }
    }
}
