package com.openlibrarykashmir.olk.feature.requests

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.model.RequestStatus
import com.openlibrarykashmir.olk.core.data.repository.BookRequestItem
import com.openlibrarykashmir.olk.core.data.repository.RequestsRepository
import com.openlibrarykashmir.olk.core.designsystem.theme.OlkPalette
import kotlin.math.abs
import kotlin.math.roundToInt
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(
    onMessage: (requestId: String) -> Unit,
    onOpenProfile: (userId: String) -> Unit,
    modifier: Modifier = Modifier,
    messagingEnabled: Boolean = true,
    ratingsEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    viewModel: RequestsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleStartEffect(viewModel) {
        viewModel.refresh()
        onStopOrDispose {}
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("Requests") }, actions = actions) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            PrimaryTabRow(selectedTabIndex = state.selected.ordinal) {
                Tab(
                    selected = state.selected == RequestDirection.INCOMING,
                    onClick = { viewModel.select(RequestDirection.INCOMING) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("For my books")
                            if (state.incomingPendingCount > 0) {
                                Badge(modifier = Modifier.padding(start = 6.dp)) {
                                    Text("${state.incomingPendingCount}")
                                }
                            }
                        }
                    },
                )
                Tab(
                    selected = state.selected == RequestDirection.OUTGOING,
                    onClick = { viewModel.select(RequestDirection.OUTGOING) },
                    text = { Text("My requests") },
                )
            }

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh(userInitiated = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    state.error != null && state.visible.isEmpty() -> CenteredMessage(
                        text = state.error.orEmpty(),
                        actionLabel = "Try again",
                        onAction = viewModel::refresh,
                    )

                    state.visible.isEmpty() -> CenteredMessage(
                        text = when (state.selected) {
                            RequestDirection.INCOMING -> "No one has requested your books yet."
                            RequestDirection.OUTGOING -> "You haven't requested any books yet.\nFind one in Browse."
                        },
                    )

                    else -> LazyColumn(
                        // Full height, so a pull that starts below a short list still refreshes.
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.visible, key = { it.id }) { item ->
                            RequestCard(
                                item = item,
                                direction = state.selected,
                                isBusy = state.busyRequestId == item.id,
                                actionsEnabled = state.busyRequestId == null,
                                onAction = { action -> viewModel.onAction(item, action) },
                                onMessage = { onMessage(item.id) },
                                messagingEnabled = messagingEnabled,
                                onOpenProfile = onOpenProfile,
                                canRate = ratingsEnabled && canRate(item, state.ratedRequestIds),
                                onRate = { viewModel.openRating(item) },
                                progress = if (showsProgress(item, state.selected)) state.progress[item.id] ?: 0 else null,
                                onSaveProgress = { pct -> viewModel.saveProgress(item, pct) },
                            )
                        }
                    }
                }
            }
        }
    }

    state.confirmation?.let { pending ->
        val (title, body) = confirmationText(pending)
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirm,
                    colors = if (pending.action == RequestAction.DECLINE) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) { Text(pending.action.label) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirmation) { Text("Not now") } },
        )
    }

    state.rating?.let { item ->
        RatingDialog(
            personName = item.otherParty?.displayName ?: "this reader",
            onDismiss = viewModel::dismissRating,
            onSubmit = viewModel::submitRating,
        )
    }
}

@Composable
private fun RequestCard(
    item: BookRequestItem,
    direction: RequestDirection,
    isBusy: Boolean,
    actionsEnabled: Boolean,
    onAction: (RequestAction) -> Unit,
    onMessage: () -> Unit,
    messagingEnabled: Boolean,
    onOpenProfile: (String) -> Unit,
    canRate: Boolean,
    onRate: () -> Unit,
    /** Saved progress when the slider applies (this user's borrowed book), else null. */
    progress: Int?,
    onSaveProgress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 80.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.book.coverUrl.isNullOrBlank()) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        AsyncImage(
                            model = item.book.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = item.book.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        RequestStatusChip(item, direction, modifier = Modifier.padding(start = 8.dp))
                    }
                    val other = item.otherParty
                    Text(
                        text = buildAnnotatedString {
                            append(if (direction == RequestDirection.INCOMING) "Requested by " else "Owned by ")
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) {
                                append(other?.displayName ?: "Unknown reader")
                            }
                            other?.areaName?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Opens their public profile, as the name links on the website.
                        modifier = if (other != null) Modifier.clickable { onOpenProfile(other.id) } else Modifier,
                    )
                    Text(
                        text = when (item.book.listingType) {
                            ListingType.DONATE -> "Giving away"
                            ListingType.LEND -> "Lending" + (item.book.lendingDurationMonths?.let { " · $it mo" } ?: "")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    DueLine(item)
                }
            }

            progress?.let { saved -> ProgressControl(saved, onSaveProgress) }

            val actions = actionsFor(item, direction)
            // Chat is for arranging the exchange, so it is offered while one is under way.
            val canMessage = messagingEnabled &&
                (item.status == RequestStatus.ACCEPTED || item.status == RequestStatus.HANDED_OVER)
            if (actions.isNotEmpty() || canMessage || canRate) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                    }
                    actions.forEach { action ->
                        when (action) {
                            RequestAction.DECLINE -> OutlinedButton(
                                onClick = { onAction(action) },
                                enabled = actionsEnabled,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) { Text(action.label) }

                            RequestAction.ACCEPT -> Button(onClick = { onAction(action) }, enabled = actionsEnabled) {
                                Text(action.label)
                            }

                            else -> FilledTonalButton(onClick = { onAction(action) }, enabled = actionsEnabled) {
                                Text(action.label)
                            }
                        }
                    }
                    if (canMessage) {
                        OutlinedButton(onClick = onMessage) { Text("Message") }
                    }
                    if (canRate) {
                        OutlinedButton(onClick = onRate, enabled = actionsEnabled) { Text("Rate") }
                    }
                }
            }
        }
    }
}

/** "How far have you read?" — public as a percentage, like the website's slider. */
@Composable
private fun ProgressControl(saved: Int, onSave: (Int) -> Unit) {
    var value by remember(saved) { mutableFloatStateOf(saved.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reading progress", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${value.roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            // Saved when the finger lifts, not on every step of the drag.
            onValueChangeFinished = { if (value.roundToInt() != saved) onSave(value.roundToInt()) },
            valueRange = 0f..100f,
            steps = 19,
        )
    }
}

@Composable
private fun RatingDialog(personName: String, onDismiss: () -> Unit, onSubmit: (Int, String) -> Unit) {
    var score by remember { mutableIntStateOf(0) }
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rate $personName") },
        text = {
            Column {
                Row {
                    (1..5).forEach { star ->
                        IconButton(onClick = { score = star }) {
                            Icon(
                                imageVector = if (star <= score) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "$star star" + if (star > 1) "s" else "",
                                tint = if (star <= score) OlkPalette.Amber400 else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(RequestsRepository.MAX_RATING_COMMENT) },
                    placeholder = { Text("Optional comment…") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(score, comment) }, enabled = score > 0) { Text("Submit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** "Due back in N days" / "Overdue by N days" for a lend the reader has; nothing otherwise. */
@Composable
internal fun DueLine(item: BookRequestItem) {
    if (item.status != RequestStatus.HANDED_OVER || item.book.listingType != ListingType.LEND) return
    val days = dueDaysLeft(item.handedOverAt, item.book.lendingDurationMonths) ?: return
    val (text, isWarning) = when {
        days < 0 -> "Overdue by ${abs(days)} ${if (abs(days) == 1L) "day" else "days"}" to true
        days == 0L -> "Due back today" to true
        days <= DUE_SOON_DAYS -> "Due back in $days ${if (days == 1L) "day" else "days"}" to true
        else -> "Due back in $days days" to false
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

private const val DUE_SOON_DAYS = 7L

@Composable
private fun RequestStatusChip(item: BookRequestItem, direction: RequestDirection, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val incoming = direction == RequestDirection.INCOMING
    val (label, container, content) = when (item.status) {
        RequestStatus.PENDING -> Triple("Pending", colors.tertiaryContainer, colors.onTertiaryContainer)
        RequestStatus.ACCEPTED -> Triple("Accepted", colors.primaryContainer, colors.onPrimaryContainer)
        RequestStatus.DECLINED -> Triple("Declined", colors.surfaceVariant, colors.onSurfaceVariant)
        RequestStatus.CANCELLED -> Triple("Cancelled", colors.surfaceVariant, colors.onSurfaceVariant)
        RequestStatus.HANDED_OVER -> Triple(
            when (item.book.listingType) {
                ListingType.LEND -> if (incoming) "Lent out" else "Borrowed"
                ListingType.DONATE -> if (incoming) "Given" else "Received"
            },
            colors.secondaryContainer,
            colors.onSecondaryContainer,
        )
        RequestStatus.RETURNED -> Triple(
            if (item.book.listingType == ListingType.DONATE) "Passed on" else "Returned",
            colors.surfaceVariant,
            colors.onSurfaceVariant,
        )
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun confirmationText(pending: PendingConfirmation): Pair<String, String> {
    val title = pending.item.book.title
    return when (pending.action) {
        RequestAction.DECLINE ->
            "Decline this request?" to "The reader will be told. A declined request can't be re-opened."
        RequestAction.CONFIRM_HANDOVER -> "Confirm handover?" to when (pending.item.book.listingType) {
            ListingType.LEND -> "Confirm that \"$title\" has changed hands. The lending period starts now."
            ListingType.DONATE -> "Confirm that \"$title\" has changed hands. It will be marked as given."
        }
        RequestAction.CONFIRM_RETURN ->
            "Mark as returned?" to "Confirm that \"$title\" is back with its owner. It becomes available again."
        RequestAction.FINISH_READING ->
            "Finished reading?" to "\"$title\" becomes yours and goes into permanent circulation: you can pass it on, but not delete it."
        RequestAction.ACCEPT -> "Accept this request?" to ""
    }
}

@Composable
private fun CenteredMessage(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    // A one-item lazy list rather than a plain Column: pull-to-refresh only reacts
    // to a drag passed up by a scrollable child, and fillParentMaxSize keeps the
    // message centred, which a verticalScroll's unbounded height would not.
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = Modifier.fillParentMaxSize().padding(32.dp),
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
    }
}
