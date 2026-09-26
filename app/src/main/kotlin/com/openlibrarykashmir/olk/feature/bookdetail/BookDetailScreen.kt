package com.openlibrarykashmir.olk.feature.bookdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.openlibrarykashmir.olk.core.data.model.AcquiredVia
import com.openlibrarykashmir.olk.core.data.model.Book
import com.openlibrarykashmir.olk.core.data.model.BookCondition
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.model.OwnerSummary
import com.openlibrarykashmir.olk.core.data.model.OwnershipEntry
import com.openlibrarykashmir.olk.core.data.model.RequestStatus
import com.openlibrarykashmir.olk.ui.ReportDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: String,
    onBack: () -> Unit,
    onOpenProfile: (userId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookDetailViewModel = koinViewModel(key = bookId) { parametersOf(bookId) },
    notesViewModel: BookNotesViewModel = koinViewModel(key = "notes-$bookId") { parametersOf(bookId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val notesState by notesViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(notesViewModel) {
        notesViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val content = state as? BookDetailUiState.Content

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (content != null && content.canSave) {
                        IconButton(onClick = viewModel::toggleSaved) {
                            Icon(
                                imageVector = if (content.detail.isSaved) {
                                    Icons.Default.Bookmark
                                } else {
                                    Icons.Default.BookmarkBorder
                                },
                                contentDescription = if (content.detail.isSaved) "Remove from saved" else "Save",
                                tint = if (content.detail.isSaved) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (content != null && content.canReport) {
                        BookMenu(onReport = viewModel::openReport)
                    }
                },
            )
        },
        bottomBar = {
            if (content != null) {
                ActionBar(
                    action = content.primaryAction,
                    isRequesting = content.isRequesting,
                    onRequest = viewModel::requestBook,
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                BookDetailUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                BookDetailUiState.NotFound -> CenteredMessage(
                    text = "This book isn't available any more.",
                    actionLabel = "Back to browse",
                    onAction = onBack,
                )

                is BookDetailUiState.Error -> CenteredMessage(
                    text = s.message,
                    actionLabel = "Try again",
                    onAction = viewModel::load,
                )

                is BookDetailUiState.Content -> DetailBody(s, onOpenProfile) {
                    BookNotesSection(
                        state = notesState,
                        onWrite = notesViewModel::openEditor,
                        onDelete = notesViewModel::delete,
                        onRetry = notesViewModel::load,
                    )
                }
            }
        }
    }

    if (content?.isReportOpen == true) {
        ReportDialog(
            subject = content.detail.book.title,
            isSending = content.isReporting,
            onDismiss = viewModel::dismissReport,
            onSend = viewModel::report,
        )
    }

    if (notesState.isEditorOpen) {
        NoteEditorDialog(
            initialText = notesState.myNote?.note.orEmpty(),
            isEditing = notesState.myNote != null,
            isSaving = notesState.isSaving,
            onDismiss = notesViewModel::dismissEditor,
            onSave = notesViewModel::save,
        )
    }
}

@Composable
private fun BookMenu(onReport: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Report this book") },
                onClick = {
                    expanded = false
                    onReport()
                },
            )
        }
    }
}

@Composable
private fun DetailBody(
    state: BookDetailUiState.Content,
    onOpenProfile: (String) -> Unit,
    notes: @Composable () -> Unit,
) {
    val book = state.detail.book
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Cover(book)

        Spacer(Modifier.height(20.dp))
        Badges(book)

        Text(
            text = book.title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        book.author?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = "by $it",
                style = MaterialTheme.typography.titleMedium,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        MetaLine(book)

        state.detail.owner?.areaName?.takeIf { it.isNotBlank() }?.let { area ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = area,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        book.description?.takeIf { it.isNotBlank() }?.let {
            // IntrinsicSize.Min lets the accent rule stretch to exactly the text's height.
            Row(modifier = Modifier.padding(top = 20.dp).height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(2.dp)),
                )
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        }

        if (book.listingType == ListingType.LEND && book.lendingDurationMonths != null) {
            val months = book.lendingDurationMonths
            Text(
                text = "Lend period: up to $months month${if (months == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        state.detail.readingProgressPct?.let { pct ->
            Column(Modifier.padding(top = 16.dp)) {
                Text(
                    text = "Current reader is $pct% through",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }

        state.detail.owner?.let {
            Spacer(Modifier.height(28.dp))
            OwnerCard(it, onClick = { onOpenProfile(it.id) })
        }
        if (state.detail.hasChangedHands) {
            Spacer(Modifier.height(28.dp))
            Journey(state.detail.ownershipHistory)
        }
        Spacer(Modifier.height(28.dp))
        notes()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Cover(book: Book) = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth(0.62f)
            .aspectRatio(2f / 3f),
    ) {
        if (book.coverUrl.isNullOrBlank()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
            }
        } else {
            AsyncImage(
                model = book.coverUrl,
                contentDescription = "Cover of ${book.title}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Badges(book: Book) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(
            text = when (book.listingType) {
                ListingType.DONATE -> "Giving away"
                ListingType.LEND -> "Lending"
            },
            emphasised = true,
        )
        book.condition?.let { Pill(text = it.label(), emphasised = false) }
        book.genre?.takeIf { it.isNotBlank() }?.let { Pill(text = it, emphasised = false) }
    }
}

@Composable
private fun Pill(text: String, emphasised: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (emphasised) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MetaLine(book: Book) {
    val parts = buildList {
        book.publicationYear?.let { add(it.toString()) }
        if (book.readCount > 0) add("Read ${book.readCount}×")
    }
    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * A person's public summary: the book page's owner card, and the header of their
 * public profile. With [onClick] the card opens that profile.
 */
@Composable
internal fun OwnerCard(owner: OwnerSummary, heading: String = "ABOUT THE OWNER", onClick: (() -> Unit)? = null) {
    val name = owner.displayName?.takeIf { it.isNotBlank() } ?: "Anonymous"
    // A disabled clickable card would dim everything, so the plain card is used
    // when there is nothing to open.
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = heading,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Text(
                        text = name.first().uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(text = name, style = MaterialTheme.typography.titleMedium)
                    val sub = buildList {
                        owner.joinedAt?.formatJoined()?.let { add("Joined $it") }
                    }
                    if (sub.isNotEmpty()) {
                        Text(
                            text = sub.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (owner.ratingAverage != null) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = RatingAmber,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = " ${owner.ratingAverage} (${owner.ratingCount})",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics {
                                    contentDescription = "Rated ${owner.ratingAverage} from ${owner.ratingCount} ratings"
                                },
                            )
                        }
                        if (owner.isTrustedSharer) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = if (owner.ratingAverage != null) 10.dp else 0.dp)
                                    .size(16.dp),
                            )
                            Text(
                                text = " Trusted Sharer",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            owner.bio?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 18.dp))

            Row(Modifier.fillMaxWidth()) {
                Stat(owner.booksListed, "Listed", Modifier.weight(1f))
                Stat(owner.booksAvailable, "Available", Modifier.weight(1f))
                Stat(owner.booksShared, "Shared", Modifier.weight(1f))
            }
        }
    }
    if (onClick != null) {
        OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/** The web's "This Book's Journey": every owner, oldest first, the current one highlighted. */
@Composable
private fun Journey(history: List<OwnershipEntry>) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "THIS BOOK'S JOURNEY",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            history.forEachIndexed { i, entry ->
                val isCurrent = entry.relinquishedAt == null
                // IntrinsicSize.Min lets the connecting line run the full height of the entry.
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(12.dp)) {
                        Box(
                            Modifier
                                .padding(top = 6.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isCurrent) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                        )
                        if (i < history.lastIndex) {
                            Box(
                                Modifier
                                    .padding(top = 4.dp)
                                    .width(1.dp)
                                    .weight(1f)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                        }
                    }
                    Column(
                        Modifier
                            .padding(start = 10.dp, bottom = if (i < history.lastIndex) 16.dp else 0.dp),
                    ) {
                        Text(
                            text = "${entry.ownerName?.takeIf { it.isNotBlank() } ?: "A reader"} " +
                                when (entry.acquiredVia) {
                                    AcquiredVia.LISTED -> "listed this book"
                                    AcquiredVia.DONATION_RECEIVED -> "received this book via donation"
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${entry.acquiredAt.formatJourneyDate()} – " +
                                (entry.relinquishedAt?.formatJourneyDate() ?: "present"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(text = value.toString(), style = MaterialTheme.typography.titleLarge)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionBar(action: PrimaryAction, isRequesting: Boolean, onRequest: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            val buttonModifier = Modifier.fillMaxWidth().height(52.dp)
            when (action) {
                PrimaryAction.Request -> Button(
                    onClick = onRequest,
                    enabled = !isRequesting,
                    modifier = buttonModifier,
                ) {
                    if (isRequesting) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Requesting…")
                    } else {
                        Text("Request this book")
                    }
                }

                is PrimaryAction.AlreadyRequested -> FilledTonalButton(
                    onClick = {},
                    enabled = false,
                    modifier = buttonModifier,
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Request ${action.status.label().lowercase()}")
                }

                is PrimaryAction.Unavailable -> FilledTonalButton(
                    onClick = {},
                    enabled = false,
                    modifier = buttonModifier,
                ) { Text(action.label) }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(32.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(actionLabel) }
    }
}

/** Amber for star ratings, as on the web. Not a brand token, so it lives here. */
private val RatingAmber = androidx.compose.ui.graphics.Color(0xFFD97706)

private fun BookCondition.label(): String = name.lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) }

/** Labels match the web app's STATUS_LABEL map. */
private fun RequestStatus.label(): String = when (this) {
    RequestStatus.PENDING -> "Pending"
    RequestStatus.ACCEPTED -> "Accepted"
    RequestStatus.HANDED_OVER -> "Handed Over"
    RequestStatus.RETURNED -> "Returned"
    RequestStatus.DECLINED -> "Declined"
    RequestStatus.CANCELLED -> "Cancelled"
}

private val JoinedFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

private fun String.formatJoined(): String? =
    runCatching { OffsetDateTime.parse(this).format(JoinedFormat) }.getOrNull()

private val JourneyDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

/** In the device's own zone, like the web's toLocaleDateString. */
private fun String.formatJourneyDate(): String =
    runCatching {
        OffsetDateTime.parse(this).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(JourneyDateFormat)
    }.getOrDefault("")
