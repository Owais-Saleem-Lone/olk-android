package com.openlibrarykashmir.olk.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.repository.ActivityItem
import com.openlibrarykashmir.olk.core.data.repository.Announcement
import com.openlibrarykashmir.olk.core.data.repository.BookOfMonth
import com.openlibrarykashmir.olk.core.data.repository.CommunityStats
import com.openlibrarykashmir.olk.core.data.repository.HomeBook
import com.openlibrarykashmir.olk.core.designsystem.theme.OlkPalette
import org.koin.androidx.compose.koinViewModel

/**
 * The website's homepage (`src/app/page.tsx`) for signed-in readers: same copy,
 * same teal / amber / rose accents on the cream page. Clubs, events and "Join the
 * team" are left out until those features reach the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSearch: (String) -> Unit,
    onBookClick: (String) -> Unit,
    onStartSharing: () -> Unit,
    onOpenClubs: () -> Unit,
    /** Whether an admin has clubs switched on; see `platform_settings`. */
    clubsEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.refresh()
        onStopOrDispose {}
    }
    val accents = homeAccents()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Open Library Kashmir", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { onSearch("") }) {
                        Icon(Icons.Default.Search, contentDescription = "Search books")
                    }
                    if (clubsEnabled) {
                        IconButton(onClick = onOpenClubs) {
                            Icon(Icons.Default.Groups, contentDescription = "Clubs")
                        }
                    }
                    actions()
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh(userInitiated = true) },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    state.error?.let { error ->
                        item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                    items(state.feed.announcements, key = { it.id }) { AnnouncementCard(it) }
                    item { Hero(accents, onSearch = onSearch, onStartSharing = onStartSharing) }
                    state.feed.stats?.takeIf { it.totalBooks + it.totalUsers + it.completedExchanges > 0 }?.let { stats ->
                        item { StatsRow(stats, accents) }
                    }
                    state.feed.bookOfMonth?.let { book -> item { BookOfMonthCard(book, accents) } }
                    item {
                        RecentlyAdded(
                            books = state.feed.recentBooks,
                            accents = accents,
                            onBookClick = onBookClick,
                            onSeeAll = { onSearch("") },
                            onStartSharing = onStartSharing,
                        )
                    }
                    if (state.feed.activity.isNotEmpty()) {
                        item { SectionHeading("Live activity", "Happening right now", accents) }
                        items(state.feed.activity, key = { "activity-${it.bookId}" }) { item ->
                            ActivityRow(item, accents, onClick = { onBookClick(item.bookId) })
                        }
                    }
                    item { HowItWorks(accents) }
                    item { CallToAction(onStartSharing) }
                    item {
                        Text(
                            "Open Library Kashmir (OLK) is an independent, community-run project. It is not affiliated " +
                                "with, sponsored by, or endorsed by the Internet Archive or its Open Library service.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The homepage's accent set; brighter steps on the dark theme so they stay readable. */
private data class HomeAccents(
    val teal: Color,
    val amber: Color,
    val rose: Color,
    val tealTint: Color,
    val amberTint: Color,
    val roseTint: Color,
)

@Composable
private fun homeAccents(): HomeAccents {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        HomeAccents(
            teal = OlkPalette.TealLight,
            amber = OlkPalette.Amber400,
            rose = OlkPalette.Rose400,
            tealTint = OlkPalette.TealLight.copy(alpha = 0.15f),
            amberTint = OlkPalette.Amber400.copy(alpha = 0.15f),
            roseTint = OlkPalette.Rose400.copy(alpha = 0.15f),
        )
    } else {
        HomeAccents(
            teal = OlkPalette.Teal600,
            amber = OlkPalette.Amber600,
            rose = OlkPalette.Rose500,
            tealTint = OlkPalette.Teal50,
            amberTint = OlkPalette.Amber50,
            roseTint = OlkPalette.Rose50,
        )
    }
}

@Composable
private fun WhiteCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) { content() }
}

@Composable
private fun AnnouncementCard(announcement: Announcement) {
    val (icon, color) = when (announcement.type) {
        "warning" -> "⚠️" to OlkPalette.Amber600
        "success" -> "✅" to Color(0xFF047857)
        "event" -> "🎉" to Color(0xFF6D28D9)
        else -> "📢" to Color(0xFF1D4ED8)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(icon, fontSize = 18.sp)
            Column {
                Text(announcement.title, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                announcement.body?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Hero(accents: HomeAccents, onSearch: (String) -> Unit, onStartSharing: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
        Surface(
            shape = CircleShape,
            color = accents.tealTint,
            border = BorderStroke(1.dp, accents.teal.copy(alpha = 0.3f)),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(accents.teal))
                Text(
                    "Open Source • Privacy-First • Local",
                    style = MaterialTheme.typography.labelMedium,
                    color = accents.teal,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        val headline = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold)
        Column {
            Text("Share a book.", style = headline)
            Text(
                "Change a life.",
                style = headline.merge(
                    TextStyle(brush = Brush.linearGradient(listOf(accents.teal, OlkPalette.Teal, OlkPalette.Amber400))),
                ),
            )
        }

        Text(
            "OLK connects readers across regions. Donate or lend your used books, find your next read from " +
                "someone nearby — all with maximum privacy and zero cost.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search a book or author") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query.trim()) }),
            trailingIcon = {
                TextButton(onClick = { onSearch(query.trim()) }) { Text("Search") }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onStartSharing,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OlkPalette.Teal600, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Text("Start Sharing Books", fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(18.dp))
        }
    }
}

@Composable
private fun StatsRow(stats: CommunityStats, accents: HomeAccents) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Stat(stats.totalBooks, "Books Shared", accents.teal)
        Stat(stats.totalUsers, "Readers Joined", accents.amber)
        Stat(stats.completedExchanges, "Exchanges Made", accents.rose)
    }
}

@Composable
private fun Stat(value: Long, label: String, color: Color) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeading(eyebrow: String, title: String, accents: HomeAccents, action: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                eyebrow.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accents.teal,
                letterSpacing = 1.5.sp,
            )
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        action()
    }
}

@Composable
private fun BookOfMonthCard(book: BookOfMonth, accents: HomeAccents) {
    WhiteCard {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Cover(book.coverUrl, book.title, Modifier.width(88.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ("📖 Book of the Month" + (book.monthLabel?.let { " · $it" } ?: "")).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accents.amber,
                    letterSpacing = 1.sp,
                )
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                book.author?.let { Text("by $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                book.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentlyAdded(
    books: List<HomeBook>,
    accents: HomeAccents,
    onBookClick: (String) -> Unit,
    onSeeAll: () -> Unit,
    onStartSharing: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading("From the community", "Recently Added", accents) {
            TextButton(onClick = onSeeAll) { Text("See all books") }
        }
        if (books.isEmpty()) {
            WhiteCard {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Books will appear here once the community starts sharing.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onStartSharing) { Text("Be the first to add one") }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(books, key = { it.id }) { book ->
                    Column(Modifier.width(128.dp).clickable { onBookClick(book.id) }) {
                        Box {
                            Cover(book.coverUrl, book.title, Modifier.fillMaxWidth())
                            ListingBadge(book.listingType, Modifier.padding(6.dp))
                            if (book.status == BookStatus.GIVEN) {
                                Box(
                                    Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Surface(shape = CircleShape, color = OlkPalette.Amber400) {
                                        Text("Donated", color = Color(0xFF451A03), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                                    }
                                }
                            }
                        }
                        Text(book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        book.author?.let { Text("by $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** A 2:3 book cover, or the title on a plain card when there is no image. */
@Composable
private fun Cover(url: String?, title: String, modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 3)
            }
        } else {
            AsyncImage(model = url, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ListingBadge(type: ListingType, modifier: Modifier = Modifier) {
    val (label, color) = if (type == ListingType.DONATE) "Free" to OlkPalette.Teal600 else "Lend" to OlkPalette.Amber600
    Surface(shape = CircleShape, color = color, modifier = modifier) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, accents: HomeAccents, onClick: () -> Unit) {
    val donate = item.listingType == ListingType.DONATE
    WhiteCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(if (donate) accents.tealTint else accents.amberTint),
                contentAlignment = Alignment.Center,
            ) { Text(if (donate) "🎁" else "🤝", fontSize = 14.sp) }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)) {
                        append(activityName(item.ownerName))
                    }
                    append(if (donate) " donated " else " listed ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = accents.teal)) { append(item.title) }
                    item.ownerArea?.takeIf { it.isNotBlank() }?.let { area ->
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline)) { append(" in $area") }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text(timeAgo(item.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun HowItWorks(accents: HomeAccents) {
    val steps = listOf(
        Triple("01", "List your book", "Add a book you want to donate or lend — title, condition, and your area. Under a minute."),
        Triple("02", "Someone requests it", "A nearby reader finds your book and sends a request. You decide to accept or decline."),
        Triple("03", "Meet & exchange", "Chat in-app to coordinate, then meet locally and hand it over. No shipping, no cost."),
    )
    val colors = listOf(accents.teal to accents.tealTint, accents.amber to accents.amberTint, accents.rose to accents.roseTint)
    WhiteCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "HOW IT WORKS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.outline,
                letterSpacing = 1.5.sp,
            )
            steps.forEachIndexed { i, (step, title, desc) ->
                val (fg, bg) = colors[i]
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(bg).border(1.dp, fg.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(step, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                    Column {
                        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CallToAction(onStartSharing: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(OlkPalette.Teal, OlkPalette.Teal, OlkPalette.Amber400)))
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ready to share your first book?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
            Text(
                "Join readers across Kashmir already sharing knowledge, one book at a time.",
                style = MaterialTheme.typography.bodyMedium,
                color = OlkPalette.Teal50,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onStartSharing,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0F766E)),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Start Sharing Books →", fontWeight = FontWeight.SemiBold) }
        }
    }
}
