package com.openlibrarykashmir.olk.feature.browse

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.openlibrarykashmir.olk.core.data.model.BookCondition
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.BrowseBook
import com.openlibrarykashmir.olk.core.data.model.BrowseFilters
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.feature.mybooks.GenreField
import com.openlibrarykashmir.olk.feature.mybooks.StatusChip
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onBookClick: (String) -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showFilters by rememberSaveable { mutableStateOf(false) }

    // Pages in when the user is within three rows of the end, so the next batch is
    // usually already there by the time they reach it.
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 3
        }
    }

    androidx.compose.runtime.LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Browse") }, actions = actions) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = state.filters.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Search title or author") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            FilterRow(
                filters = state.filters,
                onOpenFilters = {
                    viewModel.onFilterSheetOpened()
                    showFilters = true
                },
                onListingTypeToggle = { type ->
                    viewModel.applyFilters(
                        state.filters.copy(listingType = type.takeIf { it != state.filters.listingType }),
                    )
                },
            )

            when {
                state.isLoading -> CenteredBox { CircularProgressIndicator() }

                state.error != null -> CenteredBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.error.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = viewModel::retry,
                            modifier = Modifier.padding(top = 16.dp),
                        ) { Text("Try again") }
                    }
                }

                state.books.isEmpty() -> CenteredBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = when {
                                state.filters.activeCount > 0 -> "No books match your filters."
                                state.filters.query.isNotBlank() -> "No books match \"${state.filters.query}\"."
                                else -> "No books listed yet."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (state.filters.activeCount > 0) {
                            OutlinedButton(
                                onClick = viewModel::clearFilters,
                                modifier = Modifier.padding(top = 16.dp),
                            ) { Text("Clear filters") }
                        }
                    }
                }

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.books, key = { it.id }) { book ->
                        BookCard(book = book, onClick = { onBookClick(book.id) })
                    }
                    if (state.isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            initial = state.filters,
            genres = state.genres,
            hasLocation = state.hasLocation,
            onDismiss = { showFilters = false },
            onApply = {
                viewModel.applyFilters(it)
                showFilters = false
            },
        )
    }
}

@Composable
private fun FilterRow(
    filters: BrowseFilters,
    onOpenFilters: () -> Unit,
    onListingTypeToggle: (ListingType) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filters.activeCount > 0,
            onClick = onOpenFilters,
            label = { Text(if (filters.activeCount > 0) "Filters · ${filters.activeCount}" else "Filters") },
            leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null) },
        )
        FilterChip(
            selected = filters.listingType == ListingType.DONATE,
            onClick = { onListingTypeToggle(ListingType.DONATE) },
            label = { Text("Giving away") },
        )
        FilterChip(
            selected = filters.listingType == ListingType.LEND,
            onClick = { onListingTypeToggle(ListingType.LEND) },
            label = { Text("Lending") },
        )
        filters.radiusKm?.let {
            FilterChip(selected = true, onClick = onOpenFilters, label = { Text("Within $it km") })
        }
    }
}

/** Edits a draft; nothing is fetched until "Show books", so each change is not a round trip. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    initial: BrowseFilters,
    genres: List<String>,
    hasLocation: Boolean,
    onDismiss: () -> Unit,
    onApply: (BrowseFilters) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Filters", style = MaterialTheme.typography.titleLarge)

            GenreField(
                value = draft.genre ?: ANY_GENRE,
                options = listOf(ANY_GENRE) + genres,
                enabled = true,
                label = "Genre",
                onSelect = { draft = draft.copy(genre = it.takeIf { g -> g != ANY_GENRE }) },
            )

            SheetSection("Type") {
                ChoiceChip("Any", draft.listingType == null) { draft = draft.copy(listingType = null) }
                ChoiceChip("Giving away", draft.listingType == ListingType.DONATE) {
                    draft = draft.copy(listingType = ListingType.DONATE)
                }
                ChoiceChip("Lending", draft.listingType == ListingType.LEND) {
                    draft = draft.copy(listingType = ListingType.LEND)
                }
            }

            SheetSection("Condition") {
                ChoiceChip("Any", draft.condition == null) { draft = draft.copy(condition = null) }
                BookCondition.entries.forEach { condition ->
                    ChoiceChip(condition.label(), draft.condition == condition) {
                        draft = draft.copy(condition = condition)
                    }
                }
            }

            OutlinedTextField(
                value = draft.area,
                onValueChange = { draft = draft.copy(area = it.take(MAX_AREA_LENGTH)) },
                label = { Text("Area") },
                placeholder = { Text("e.g. Anantnag") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SheetSection("Distance") {
                ChoiceChip("Any", draft.radiusKm == null) { draft = draft.copy(radiusKm = null) }
                BrowseFilters.RADIUS_STEPS_KM.forEach { km ->
                    ChoiceChip("Within $km km", draft.radiusKm == km, enabled = hasLocation) {
                        draft = draft.copy(radiusKm = km)
                    }
                }
            }
            if (!hasLocation) {
                Text(
                    text = "Share your location on your Profile to filter by distance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { draft = BrowseFilters(query = draft.query) }) { Text("Clear") }
                Button(onClick = { onApply(draft) }) { Text("Show books") }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) })
}

private fun BookCondition.label() = when (this) {
    BookCondition.EXCELLENT -> "Excellent"
    BookCondition.GOOD -> "Good"
    BookCondition.FAIR -> "Fair"
    BookCondition.POOR -> "Poor"
}

private const val ANY_GENRE = "All genres"

// profiles.area_name is capped at 100; a longer term can never match.
private const val MAX_AREA_LENGTH = 100

@Composable
private fun BookCard(book: BrowseBook, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 92.dp)
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

            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    text = when (book.listingType) {
                        ListingType.DONATE -> "Giving away"
                        ListingType.LEND -> "Lending" +
                            (book.lendingDurationMonths?.let { " · $it mo" } ?: "")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                val whereLine = listOfNotNull(formatDistance(book.distanceKm), book.ownerArea?.takeIf { it.isNotBlank() })
                if (whereLine.isNotEmpty()) {
                    Text(
                        text = whereLine.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (book.status != BookStatus.AVAILABLE) {
                    StatusChip(book.status, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
