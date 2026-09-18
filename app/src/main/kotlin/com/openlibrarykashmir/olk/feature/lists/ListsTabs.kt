package com.openlibrarykashmir.olk.feature.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.repository.SavedBook
import com.openlibrarykashmir.olk.core.data.repository.WishlistItem
import com.openlibrarykashmir.olk.feature.mybooks.StatusChip
import org.koin.androidx.compose.koinViewModel

@Composable
fun SavedTab(
    onOpenBook: (String) -> Unit,
    viewModel: SavedViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.refresh()
        onStopOrDispose {}
    }

    when {
        state.isLoading -> Centered { CircularProgressIndicator() }
        state.error != null && state.books.isEmpty() -> Centered {
            Message(state.error.orEmpty(), "Try again", viewModel::refresh)
        }
        state.books.isEmpty() -> Centered {
            Message("No saved books yet.\nTap Save on a book to keep it here.")
        }
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { item { ErrorLine(it) } }
            items(state.books, key = { it.bookmarkId }) { book ->
                SavedCard(book, onClick = { onOpenBook(book.bookId) }, onRemove = { viewModel.remove(book) })
            }
        }
    }
}

@Composable
private fun SavedCard(book: SavedBook, onClick: () -> Unit, onRemove: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(width = 56.dp, height = 80.dp).clip(RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (book.coverUrl.isNullOrBlank()) {
                    Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                book.author?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (book.listingType == ListingType.DONATE) "Giving away" else "Lending",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (book.status != BookStatus.AVAILABLE) {
                    StatusChip(book.status, modifier = Modifier.padding(top = 6.dp))
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Bookmark, contentDescription = "Remove from saved", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun WishlistTab(
    onOpenBook: (String) -> Unit,
    viewModel: WishlistViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.refresh()
        onStopOrDispose {}
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { WishForm(state, viewModel) }

        when {
            state.isLoading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error != null && state.items.isEmpty() -> item {
                Message(state.error.orEmpty(), "Try again", viewModel::refresh)
            }
            state.items.isEmpty() -> item {
                Message("Your wishlist is empty.\nAdd a book you're looking for and you'll be notified when someone lists it.")
            }
            else -> {
                state.error?.let { item { ErrorLine(it) } }
                items(state.items, key = { it.id }) { item ->
                    WishCard(item, onOpenBook = onOpenBook, onRemove = { viewModel.remove(item) })
                }
            }
        }
    }
}

@Composable
private fun WishForm(state: WishlistUiState, viewModel: WishlistViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Books you're looking for. You'll be notified when someone lists a match.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.title,
            onValueChange = viewModel::onTitleChange,
            label = { Text("Title") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.author,
            onValueChange = viewModel::onAuthorChange,
            label = { Text("Author (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.add() }),
            modifier = Modifier.fillMaxWidth(),
        )
        state.formError?.let { ErrorLine(it) }
        Button(onClick = viewModel::add, enabled = state.canAdd, modifier = Modifier.align(Alignment.End)) {
            Text(if (state.isAdding) "Adding…" else "Add to wishlist")
        }
    }
}

@Composable
private fun WishCard(item: WishlistItem, onOpenBook: (String) -> Unit, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.author?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "by $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val matched = item.matchedBookId
                if (matched != null) {
                    FilledTonalButton(onClick = { onOpenBook(matched) }, modifier = Modifier.padding(top = 6.dp)) {
                        Text("Match found — view book")
                    }
                } else {
                    Text(
                        "Waiting for a match",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove from wishlist")
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Message(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
