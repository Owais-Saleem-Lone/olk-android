package com.openlibrarykashmir.olk.feature.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.repository.Conversation
import com.openlibrarykashmir.olk.core.data.repository.MessagesRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MessagesUiState(
    val conversations: List<Conversation> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val viewerId: String? = null,
)

class MessagesViewModel(
    private val repository: MessagesRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MessagesUiState())
    val uiState: StateFlow<MessagesUiState> = _uiState.asStateFlow()

    fun refresh(userInitiated: Boolean = false) {
        val userId = auth.currentUserId() ?: run {
            _uiState.update { it.copy(isLoading = false, error = "Your session has ended. Sign in again.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(error = null, isRefreshing = userInitiated, viewerId = userId) }
            runCatching { repository.conversations(userId) }
                .onSuccess { list ->
                    _uiState.update { it.copy(conversations = list, isLoading = false, isRefreshing = false) }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = "Could not load your messages.") }
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onConversationClick: (requestId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MessagesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleStartEffect(viewModel) {
        viewModel.refresh()
        onStopOrDispose {}
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Messages") }) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh(userInitiated = true) },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.conversations.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column(
                            modifier = Modifier.fillParentMaxSize().padding(32.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = state.error
                                    ?: "No conversations yet.\nA chat opens once a request is accepted.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            if (state.error != null) {
                                Button(onClick = { viewModel.refresh() }, modifier = Modifier.padding(top = 16.dp)) {
                                    Text("Try again")
                                }
                            }
                        }
                    }
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.conversations, key = { it.request.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            viewerId = state.viewerId,
                            onClick = { onConversationClick(conversation.request.id) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, viewerId: String?, onClick: () -> Unit) {
    val other = conversation.request.otherParty
    val name = other?.displayName ?: "Reader"
    val last = conversation.lastMessage

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(name.first().uppercase(), style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                last?.let {
                    Text(
                        text = timeAgo(it.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = conversation.request.book.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    last == null -> "No messages yet"
                    last.senderId == viewerId -> "You: ${last.content}"
                    else -> last.content
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/** Same buckets as the web inbox's timeAgo. */
internal fun timeAgo(createdAt: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val then = parseTimestamp(createdAt)?.toInstant() ?: return ""
    val minutes = Duration.between(then, now).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 48 -> "yesterday"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
        else -> then.atZone(zone).format(SHORT_DATE)
    }
}
