package com.openlibrarykashmir.olk.feature.support

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlibrarykashmir.olk.core.data.repository.SupportMessage
import com.openlibrarykashmir.olk.core.data.repository.SupportRepository
import com.openlibrarykashmir.olk.ui.parseTimestamp
import org.koin.androidx.compose.koinViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Contact Admin" — the website's /support: a private line to the admin team,
 * for questions, appealing a decision, or reporting something.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SupportViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Live only while visible; history reloads on return to cover the gap.
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Contact Admin") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
            when (val s = state) {
                SupportUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SupportUiState.Error -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, textAlign = TextAlign.Center)
                    Button(onClick = viewModel::start, modifier = Modifier.padding(top = 16.dp)) { Text("Try again") }
                }
                is SupportUiState.Ready -> Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "A private line to the OLK admin team — ask questions, appeal a decision, " +
                            "or report something that needs attention.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    HorizontalDivider()
                    SupportMessageList(state = s, modifier = Modifier.weight(1f))
                    HorizontalDivider()
                    SupportComposer(
                        draft = s.draft,
                        canSend = s.canSend,
                        isSending = s.isSending,
                        onDraftChange = viewModel::onDraftChange,
                        onSend = viewModel::send,
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportMessageList(state: SupportUiState.Ready, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    if (state.messages.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "No messages yet — send one to reach the admin team.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
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
        items(state.messages, key = { it.id }) { message ->
            SupportBubble(message = message, isMine = message.senderId == state.viewerId)
        }
    }
}

@Composable
private fun SupportBubble(message: SupportMessage, isMine: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = when {
                isMine -> colors.primary
                message.senderIsAdmin -> colors.tertiaryContainer
                else -> colors.surfaceContainerHigh
            },
            contentColor = when {
                isMine -> colors.onPrimary
                message.senderIsAdmin -> colors.onTertiaryContainer
                else -> colors.onSurface
            },
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isMine) 18.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                // The badge is safe to trust: the database only lets staff post with it.
                if (message.senderIsAdmin) {
                    Text(
                        text = "✓ OLK Admin Team",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                Text(text = message.content, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = supportTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMine) colors.onPrimary.copy(alpha = 0.7f) else colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SupportComposer(
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
            placeholder = { Text("Type a message to the admin team") },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            supportingText = if (draft.length > SupportRepository.MAX_LENGTH - COUNTER_THRESHOLD) {
                { Text("${draft.length} / ${SupportRepository.MAX_LENGTH}") }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
        FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.padding(start = 8.dp)) {
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

private val SUPPORT_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun supportTime(createdAt: String): String =
    parseTimestamp(createdAt)?.atZoneSameInstant(ZoneId.systemDefault())?.format(SUPPORT_TIME).orEmpty()
