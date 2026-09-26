package com.openlibrarykashmir.olk.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.openlibrarykashmir.olk.core.data.model.ListingType
import com.openlibrarykashmir.olk.core.data.repository.BookOfMonth
import com.openlibrarykashmir.olk.core.data.repository.HomeBook
import com.openlibrarykashmir.olk.core.designsystem.theme.OlkPalette
import kotlinx.coroutines.delay

// The website homepage's own colours (Tailwind steps), for the pieces that
// stay light in both themes, as they are on the website.
private val Slate900 = Color(0xFF0F172A)
private val Slate600 = Color(0xFF475569)
private val Slate500 = Color(0xFF64748B)
private val Amber200 = Color(0xFFFDE68A)
private val Amber700 = Color(0xFFB45309)
private val Teal200 = Color(0xFF99F6E4)
private val Teal700 = Color(0xFF0F766E)

/** Same pause as the website's CommunityShelf between books. */
private const val HIGHLIGHT_INTERVAL_MS = 2_800L

/**
 * The website's page: cream with three soft glows (teal at the top, amber
 * lower left, rose lower right), fixed behind the content as on the website.
 * On the dark theme the glows stay, much fainter, on the dark background.
 */
@Composable
internal fun HomeBackground(content: @Composable BoxScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val base = if (dark) MaterialTheme.colorScheme.background else OlkPalette.Cream
    val strength = if (dark) 0.35f else 1f
    Box(
        Modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                fun glow(color: Color, alpha: Float, center: Offset, radius: Float) = drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = alpha * strength), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
                // teal-300 / amber-300 / rose-200, as in src/app/page.tsx.
                glow(Color(0xFF5EEAD4), 0.30f, Offset(size.width / 2, size.height * 0.02f), size.width * 0.95f)
                glow(Color(0xFFFCD34D), 0.30f, Offset(size.width * 0.15f, size.height * 0.50f), size.width * 0.75f)
                glow(Color(0xFFFECDD3), 0.40f, Offset(size.width * 0.90f, size.height * 0.80f), size.width * 0.70f)
            },
        content = content,
    )
}

/**
 * "Highlights from Library": the books an admin featured, one at a time,
 * sliding sideways on their own every [HIGHLIGHT_INTERVAL_MS] and pausing
 * while the reader swipes. Tapping one opens it.
 */
@Composable
internal fun HighlightsCard(books: List<HomeBook>, onBookClick: (String) -> Unit) {
    if (books.isEmpty()) return
    // Many pages that wrap onto the few books, so it always slides forwards.
    val pageCount = if (books.size > 1) books.size * 1_000 else 1
    val pagerState = rememberPagerState(initialPage = if (books.size > 1) books.size * 500 else 0) { pageCount }
    val dragged by pagerState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(books.size, dragged) {
        if (books.size <= 1 || dragged) return@LaunchedEffect
        while (true) {
            delay(HIGHLIGHT_INTERVAL_MS)
            pagerState.animateScrollToPage(pagerState.currentPage + 1)
        }
    }

    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "HIGHLIGHTS FROM LIBRARY",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    color = Slate600,
                )
                Spacer(Modifier.height(20.dp))
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                    val book = books[page % books.size]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onBookClick(book.id) }
                            .padding(horizontal = 24.dp),
                    ) {
                        Cover(book.coverUrl, book.title, Modifier.width(120.dp).shadow(4.dp, RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                book.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate900,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            book.author?.let {
                                Text(
                                    "by $it",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Slate500,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            ListingBadge(book.listingType, Modifier.padding(top = 12.dp))
                        }
                    }
                }
                if (books.size > 1) {
                    val current = pagerState.currentPage % books.size
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 16.dp)) {
                        books.indices.forEach { i ->
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (i == current) OlkPalette.Teal else OlkPalette.Slate200),
                            )
                        }
                    }
                }
            }
        }
        Sticker("📖 100% Free", Amber700, Amber200, rotation = 3f, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-10).dp))
        Sticker("🤝 Community-driven", Teal700, Teal200, rotation = -2f, Modifier.align(Alignment.BottomStart).offset(x = (-4).dp, y = 10.dp))
    }
}

private data class BadgeColors(val label: String, val text: Color, val fill: Color, val edge: Color)

@Composable
private fun ListingBadge(type: ListingType, modifier: Modifier = Modifier) {
    val c = when (type) {
        ListingType.DONATE -> BadgeColors("Free", Teal700, OlkPalette.Teal50, Teal200)
        ListingType.LEND -> BadgeColors("Lend", Amber700, OlkPalette.Amber50, Amber200)
    }
    Text(
        c.label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = c.text,
        modifier = modifier
            .clip(CircleShape)
            .background(c.fill)
            .border(1.dp, c.edge, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun Sticker(text: String, color: Color, edge: Color, rotation: Float, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier
            .rotate(rotation)
            .shadow(3.dp, CircleShape)
            .background(Color.White, CircleShape)
            .border(1.dp, edge, CircleShape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** The website's warm Book of the Month card, light in both themes as on the website. */
private val BookOfMonthGradient = Brush.linearGradient(
    listOf(OlkPalette.Amber50, Color.White, OlkPalette.Teal50),
)

/**
 * Book of the Month, as on the website: the card with a short excerpt and
 * "Read More" for the whole write-up, and the member who wrote it.
 */
@Composable
internal fun BookOfMonthCard(
    book: BookOfMonth,
    onOpenWriter: (String) -> Unit,
    onContactAdmin: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(BookOfMonthGradient)
            .border(1.dp, Amber200, RoundedCornerShape(24.dp))
            .clickable { open = true }
            .padding(20.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Cover(book.coverUrl, book.title, Modifier.width(104.dp).shadow(6.dp, RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f)) {
                BookOfMonthLabel(book)
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Slate900,
                    modifier = Modifier.padding(top = 8.dp),
                )
                book.author?.let {
                    Text("by $it", style = MaterialTheme.typography.bodyMedium, color = Slate500, modifier = Modifier.padding(top = 2.dp))
                }
                book.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate500,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Text(
                    "Read More →",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = OlkPalette.Amber600,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        book.writtenBy?.let { writerId ->
            HorizontalDivider(Modifier.padding(top = 16.dp), color = Amber700.copy(alpha = 0.12f))
            WriterCredit(book, onClick = { onOpenWriter(writerId) }, modifier = Modifier.padding(top = 12.dp))
        }
    }

    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(BookOfMonthGradient)
                    .border(1.dp, Amber200, RoundedCornerShape(24.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cover(book.coverUrl, book.title, Modifier.width(96.dp).shadow(6.dp, RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f)) {
                        BookOfMonthLabel(book)
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Slate900,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        book.author?.let { Text("by $it", style = MaterialTheme.typography.bodyMedium, color = Slate500) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Amber700.copy(alpha = 0.12f))
                Text(
                    book.description?.takeIf { it.isNotBlank() } ?: "No description added yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate600,
                )
                book.writtenBy?.let { writerId ->
                    WriterCredit(
                        book,
                        onClick = {
                            open = false
                            onOpenWriter(writerId)
                        },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                Text(
                    "Want to write the next Book of the Month? Tell us about a book you love.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        open = false
                        onContactAdmin()
                    }) { Text("Contact Admin", color = Teal700) }
                    TextButton(onClick = { open = false }) { Text("Close", color = Amber700) }
                }
            }
        }
    }
}

@Composable
private fun BookOfMonthLabel(book: BookOfMonth) {
    Text(
        "✦ BOOK OF THE MONTH" + (book.monthLabel?.let { " — $it" } ?: ""),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = OlkPalette.Amber600,
    )
}

/** "✍️ Written by …": opens the member's profile. */
@Composable
private fun WriterCredit(book: BookOfMonth, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            append("✍️ Written by ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Amber700)) {
                append(book.writerName?.takeIf { it.isNotBlank() } ?: "a member")
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = Slate600,
        textAlign = TextAlign.Start,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}
