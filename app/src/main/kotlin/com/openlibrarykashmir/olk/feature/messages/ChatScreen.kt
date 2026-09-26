package com.openlibrarykashmir.olk.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlibrarykashmir.olk.core.data.repository.ChatMessage
import com.openlibrarykashmir.olk.core.data.repository.MessagesRepository
import com.openlibrarykashmir.olk.feature.people.BlockMenu
import com.openlibrarykashmir.olk.ui.parseTimestamp
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    requestId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = koinViewModel(key = "chat-$requestId") { parametersOf(requestId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Live only while visible: the Realtime channel closes when the app goes to
    // the background, and history reloads on return to cover the gap.
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val ready = state as? ChatUiState.Ready

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (ready != null) {
                        Column {
                            Text(
                                text = ready.info.otherParty?.displayName ?: "Reader",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOfNotNull(ready.info.otherParty?.areaName, ready.info.bookTitle)
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    ready?.info?.let { info ->
                        info.otherParty?.let { other ->
                            BlockMenu(
                                other.id,
                                onMessage = { snackbarHostState.showSnackbar(it) },
                                memberName = other.displayName,
                                // Same wording as the website, so the team sees where it came from.
                                reportContext = "Reported from the chat about \"${info.bookTitle}\" (request ${info.requestId}).",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
            when (val s = state) {
                ChatUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ChatUiState.NotFound -> CenteredText("This conversation isn't available.")
                is ChatUiState.Error -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, textAlign = TextAlign.Center)
                    Button(onClick = viewModel::start, modifier = Modifier.padding(top = 16.dp)) { Text("Try again") }
                }
                is ChatUiState.Ready -> Column(modifier = Modifier.fillMaxSize()) {
                    MessageList(state = s, modifier = Modifier.weight(1f))
                    HorizontalDivider()
                    if (s.canMessage) {
                        Composer(
                            draft = s.draft,
                            canSend = s.canSend,
                            isSending = s.isSending,
                            onDraftChange = viewModel::onDraftChange,
                            onSend = viewModel::send,
                        )
                    } else {
                        Text(
                            text = "Messaging opens once the request is accepted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface ChatRow {
    val key: String

    data class DaySeparator(val label: String) : ChatRow {
        override val key: String get() = "day-$label"
    }

    data class Bubble(val message: ChatMessage, val time: String) : ChatRow {
        override val key: String get() = message.id
    }
}

@Composable
private fun MessageList(state: ChatUiState.Ready, modifier: Modifier = Modifier) {
    val rows = remember(state.messages) { buildRows(state.messages) }
    val listState = rememberLazyListState()

    // Follow the conversation: jump to the newest message whenever one arrives.
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }

    if (rows.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = "No messages yet. Say hello!",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is ChatRow.DaySeparator -> Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
                is ChatRow.Bubble -> MessageBubble(
                    message = row.message,
                    time = row.time,
                    isMine = row.message.senderId == state.viewerId,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, time: String, isMine: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isMine) colors.primary else colors.surfaceContainerHigh,
            contentColor = if (isMine) colors.onPrimary else colors.onSurface,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isMine) 18.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(text = message.content, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMine) colors.onPrimary.copy(alpha = 0.7f) else colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    canSend: Boolean,
    isSending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Type a message") },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            supportingText = if (draft.length > MessagesRepository.MAX_LENGTH - COUNTER_THRESHOLD) {
                { Text("${draft.length} / ${MessagesRepository.MAX_LENGTH}") }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
        FilledIconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            if (isSending) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(10.dp))
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/** Only show the counter when someone is close to the limit. */
private const val COUNTER_THRESHOLD = 200

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM")

/** Messages with a day label before the first message of each day: Today, Yesterday, or the date. */
private fun buildRows(messages: List<ChatMessage>, zone: ZoneId = ZoneId.systemDefault()): List<ChatRow> {
    val today = LocalDate.now(zone)
    val rows = mutableListOf<ChatRow>()
    var lastDay: LocalDate? = null
    for (message in messages) {
        val local = parseTimestamp(message.createdAt)?.atZoneSameInstant(zone)
        val day = local?.toLocalDate()
        if (day != null && day != lastDay) {
            rows += ChatRow.DaySeparator(
                when (day) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> day.format(DAY_FORMAT)
                },
            )
            lastDay = day
        }
        rows += ChatRow.Bubble(message, local?.format(TIME_FORMAT).orEmpty())
    }
    return rows
}
