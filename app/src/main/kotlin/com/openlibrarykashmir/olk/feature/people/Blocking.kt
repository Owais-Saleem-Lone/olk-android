package com.openlibrarykashmir.olk.feature.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.model.ReportReason
import com.openlibrarykashmir.olk.core.data.repository.BlockOutcome
import com.openlibrarykashmir.olk.core.data.repository.BlockedMember
import com.openlibrarykashmir.olk.core.data.repository.BlocksRepository
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.ui.ReportDialog
import com.openlibrarykashmir.olk.ui.message
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** What blocking does, said before the member confirms (same words as the website). */
const val BLOCK_EXPLANATION =
    "You won't see each other's books, and neither of you can request the other's books or send messages. " +
        "Their club posts and book notes are hidden from you. Open requests between you are closed; a book that " +
        "has already been handed over can still be returned. They won't be told."

data class BlockUiState(
    /** False for yourself, signed out, or before the check has answered. */
    val available: Boolean = false,
    val blocked: Boolean = false,
    val busy: Boolean = false,
    val isReportOpen: Boolean = false,
    val isReporting: Boolean = false,
)

sealed interface BlockEvent {
    data class Message(val text: String) : BlockEvent
    /** The block changed, so what the screen shows (their books) may have too. */
    data object Changed : BlockEvent
}

class BlockViewModel(
    private val userId: String,
    private val blocks: BlocksRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BlockUiState())
    val uiState: StateFlow<BlockUiState> = _uiState.asStateFlow()

    private val _events = Channel<BlockEvent>(Channel.BUFFERED)
    val events: Flow<BlockEvent> = _events.receiveAsFlow()

    init {
        val me = auth.currentUserId()
        if (me != null && me != userId) {
            viewModelScope.launch {
                runCatching { blocks.isBlocked(userId) }
                    .onSuccess { blocked -> _uiState.value = BlockUiState(available = true, blocked = blocked) }
            }
        }
    }

    fun block() {
        val me = auth.currentUserId() ?: return
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            runCatching { blocks.block(me, userId) }
                .onSuccess { outcome ->
                    when (outcome) {
                        BlockOutcome.Blocked -> {
                            _uiState.update { it.copy(blocked = true) }
                            _events.send(BlockEvent.Message("Member blocked."))
                            _events.send(BlockEvent.Changed)
                        }
                        BlockOutcome.LimitReached ->
                            _events.send(BlockEvent.Message("You've blocked the most members you can. Unblock someone first."))
                    }
                }
                .onFailure { _events.send(BlockEvent.Message("Couldn't block this member. Please try again.")) }
            _uiState.update { it.copy(busy = false) }
        }
    }

    fun openReport() = _uiState.update { it.copy(isReportOpen = true) }

    fun dismissReport() = _uiState.update { if (it.isReporting) it else it.copy(isReportOpen = false) }

    /** [context] says where the report came from (e.g. which chat), for the OLK team. */
    fun report(reason: ReportReason, details: String, context: String?) {
        val me = auth.currentUserId() ?: return
        if (_uiState.value.isReporting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isReporting = true) }
            runCatching { blocks.reportMember(me, userId, reason, details, context) }
                .onSuccess { outcome ->
                    _uiState.update { it.copy(isReporting = false, isReportOpen = false) }
                    _events.send(BlockEvent.Message(outcome.message("this member")))
                }
                .onFailure {
                    _uiState.update { it.copy(isReporting = false) }
                    _events.send(BlockEvent.Message("The report couldn't be sent. Please try again."))
                }
        }
    }

    fun unblock() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            runCatching { blocks.unblock(userId) }
                .onSuccess {
                    _uiState.update { it.copy(blocked = false) }
                    _events.send(BlockEvent.Message("Member unblocked."))
                    _events.send(BlockEvent.Changed)
                }
                .onFailure { _events.send(BlockEvent.Message("Couldn't unblock this member. Please try again.")) }
            _uiState.update { it.copy(busy = false) }
        }
    }
}

/**
 * A top-bar overflow menu with Report member and Block / Unblock for [userId],
 * and their dialogs. Shows nothing on your own profile or before the check has
 * answered. Google Play asks for both reporting and blocking of members.
 *
 * @param memberName shown in the report dialog.
 * @param reportContext where a report from here came from (e.g. which chat).
 */
@Composable
fun BlockMenu(
    userId: String,
    onMessage: suspend (String) -> Unit,
    onChanged: () -> Unit = {},
    memberName: String? = null,
    reportContext: String? = null,
    viewModel: BlockViewModel = koinViewModel(key = "block-$userId") { parametersOf(userId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BlockEvent.Message -> onMessage(event.text)
                BlockEvent.Changed -> onChanged()
            }
        }
    }

    if (!state.available) return

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Report member") },
                onClick = {
                    expanded = false
                    viewModel.openReport()
                },
            )
            DropdownMenuItem(
                text = { Text(if (state.blocked) "Unblock member" else "Block member") },
                enabled = !state.busy,
                onClick = {
                    expanded = false
                    if (state.blocked) viewModel.unblock() else confirming = true
                },
            )
        }
    }

    if (state.isReportOpen) {
        ReportDialog(
            subject = memberName?.takeIf { it.isNotBlank() } ?: "This member",
            title = "Report this member",
            isSending = state.isReporting,
            onDismiss = viewModel::dismissReport,
            onSend = { reason, details -> viewModel.report(reason, details, reportContext) },
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Block this member?") },
            text = { Text(BLOCK_EXPLANATION) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    viewModel.block()
                }) { Text("Block", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

// ── The list of members you blocked (Profile → Blocked members) ──

sealed interface BlockedMembersUiState {
    data object Loading : BlockedMembersUiState
    data class Content(val members: List<BlockedMember>) : BlockedMembersUiState
    data class Error(val message: String) : BlockedMembersUiState
}

class BlockedMembersViewModel(private val blocks: BlocksRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<BlockedMembersUiState>(BlockedMembersUiState.Loading)
    val uiState: StateFlow<BlockedMembersUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        _uiState.value = BlockedMembersUiState.Loading
        viewModelScope.launch {
            _uiState.value = runCatching { blocks.blocked() }.fold(
                onSuccess = { BlockedMembersUiState.Content(it) },
                onFailure = { BlockedMembersUiState.Error("Could not load the list. Please try again.") },
            )
        }
    }

    fun unblock(member: BlockedMember) {
        viewModelScope.launch {
            runCatching { blocks.unblock(member.userId) }
                .onSuccess {
                    _uiState.update { s ->
                        if (s is BlockedMembersUiState.Content) s.copy(members = s.members - member) else s
                    }
                    _messages.send("Member unblocked.")
                }
                .onFailure { _messages.send("Couldn't unblock this member. Please try again.") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedMembersScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BlockedMembersViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Blocked members") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                BlockedMembersUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is BlockedMembersUiState.Error -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, textAlign = TextAlign.Center)
                    Button(onClick = viewModel::load, modifier = Modifier.padding(top = 16.dp)) { Text("Try again") }
                }
                is BlockedMembersUiState.Content -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    item {
                        Text(
                            "They can't see your books or contact you, and aren't told they were blocked.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    if (s.members.isEmpty()) {
                        item { Text("You haven't blocked anyone.") }
                    }
                    items(s.members, key = { it.userId }) { member ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                member.displayName ?: "Member",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).clickable { onOpenProfile(member.userId) },
                            )
                            OutlinedButton(onClick = { viewModel.unblock(member) }) { Text("Unblock") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
