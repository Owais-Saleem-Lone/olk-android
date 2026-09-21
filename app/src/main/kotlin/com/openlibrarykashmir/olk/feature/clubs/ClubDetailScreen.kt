package com.openlibrarykashmir.olk.feature.clubs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.openlibrarykashmir.olk.core.data.model.Club
import com.openlibrarykashmir.olk.core.data.model.ClubEvent
import com.openlibrarykashmir.olk.core.data.model.ClubMember
import com.openlibrarykashmir.olk.core.data.model.ClubPost
import com.openlibrarykashmir.olk.core.data.model.MembershipStatus
import com.openlibrarykashmir.olk.feature.events.MembersOnlyLabel
import com.openlibrarykashmir.olk.feature.events.eventPlace
import com.openlibrarykashmir.olk.feature.events.eventWhenShort
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClubDetailScreen(
    clubId: String,
    onBack: () -> Unit,
    onMemberClick: (String) -> Unit,
    onEventClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether an admin has events switched on; see `platform_settings`. */
    eventsEnabled: Boolean = true,
    viewModel: ClubDetailViewModel = koinViewModel { parametersOf(clubId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmingLeave by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(state.club?.name ?: "Club") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        val club = state.club

        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.notFound || club == null -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: "This club is not available.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { ClubHeader(club) }

                item {
                    MembershipButton(
                        membership = state.membership,
                        isOwner = state.isOwner,
                        signedIn = state.currentUserId != null,
                        onJoin = viewModel::requestToJoin,
                        onLeave = { confirmingLeave = true },
                    )
                }

                if (state.isOwner && state.applicants.isNotEmpty()) {
                    item {
                        Text(
                            "Membership requests (${state.applicants.size})",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(state.applicants, key = { "applicant-${it.userId}" }) { applicant ->
                        ApplicantCard(
                            member = applicant,
                            onApprove = { viewModel.approve(applicant.userId) },
                            onReject = { viewModel.reject(applicant.userId) },
                            onClick = { onMemberClick(applicant.userId) },
                        )
                    }
                }

                if (eventsEnabled) {
                    item { Text("Upcoming events", style = MaterialTheme.typography.titleMedium) }
                    if (state.events.isEmpty()) {
                        item {
                            Text(
                                text = if (state.isOwner) {
                                    "No upcoming events. Events are scheduled on the website for now."
                                } else {
                                    "No upcoming events yet."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(state.events, key = { "event-${it.id}" }) { event ->
                            ClubEventCard(event = event, onClick = { onEventClick(event.id) })
                        }
                    }
                }

                item { Text("Club chat", style = MaterialTheme.typography.titleMedium) }

                if (!state.canSeeChat) {
                    item {
                        Text(
                            text = "The club chat is for members. Join this club to read and " +
                                "take part in the conversation.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    item {
                        ChatComposer(
                            draft = state.draft,
                            isSending = state.isSending,
                            onDraftChange = viewModel::onDraftChange,
                            onSend = viewModel::sendPost,
                        )
                    }

                    if (state.posts.isEmpty()) {
                        item {
                            Text(
                                text = "No messages yet. Be the first to say hello.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(state.posts, key = { it.id }) { post ->
                            PostCard(post = post, isOwnerPost = post.authorId == club.creatorId)
                        }
                    }

                    item {
                        RatingCard(
                            score = state.myScore,
                            comment = state.myComment,
                            isSaving = state.isRating,
                            onCommentChange = viewModel::onCommentChange,
                            onRate = viewModel::rate,
                        )
                    }

                    item {
                        Text(
                            "Members (${state.members.size})",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(state.members, key = { "member-${it.userId}" }) { member ->
                        MemberRow(
                            member = member,
                            isOwner = member.userId == club.creatorId,
                            onClick = { onMemberClick(member.userId) },
                        )
                    }
                }
            }
        }
    }

    if (confirmingLeave) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text(if (state.membership == MembershipStatus.PENDING) "Withdraw request?" else "Leave club?") },
            text = {
                Text(
                    if (state.membership == MembershipStatus.PENDING) {
                        "Your request to join will be withdrawn."
                    } else {
                        "You will lose access to this club's chat and members."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLeave = false
                    viewModel.leave()
                }) { Text(if (state.membership == MembershipStatus.PENDING) "Withdraw" else "Leave") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ClubEventCard(event: ClubEvent, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (event.isMembersOnly) MembersOnlyLabel()
            Text(event.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = listOf(
                    eventWhenShort(event.startsAt),
                    eventPlace(event.isOnline, event.locationName),
                    "${event.attendeeCount} going",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClubHeader(club: Club) {
    Column {
        if (club.coverUrl != null) {
            AsyncImage(
                model = club.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }

        club.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Text(
            text = buildList {
                add("${club.memberCount} ${if (club.memberCount == 1) "member" else "members"}")
                club.ratingAvg?.let {
                    add("★ ${"%.1f".format(it)} (${club.ratingCount})")
                }
                club.areaName?.takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (club.interests.isNotEmpty()) {
            Text(
                text = club.interests.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MembershipButton(
    membership: MembershipStatus,
    isOwner: Boolean,
    signedIn: Boolean,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
) {
    when {
        !signedIn -> Text(
            text = "Sign in to join this club.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The owner cannot leave their own club; the website has them delete it
        // instead, which is not in the app yet.
        isOwner -> Text(
            text = "You started this club.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        membership == MembershipStatus.APPROVED -> OutlinedButton(onClick = onLeave) { Text("Leave club") }

        membership == MembershipStatus.PENDING -> OutlinedButton(onClick = onLeave) {
            Text("Request pending")
        }

        else -> Button(onClick = onJoin) { Text("Request to join") }
    }
}

@Composable
private fun ApplicantCard(
    member: ClubMember,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(member.displayName ?: "Anonymous", style = MaterialTheme.typography.bodyLarge)
            member.areaName?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Button(onClick = onApprove) { Text("Approve") }
                OutlinedButton(onClick = onReject) { Text("Decline") }
            }
        }
    }
}

@Composable
private fun ChatComposer(
    draft: String,
    isSending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text("Send a message to the club") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Button(
            onClick = onSend,
            enabled = draft.isNotBlank() && !isSending,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(if (isSending) "Sending..." else "Send") }
    }
}

@Composable
private fun PostCard(post: ClubPost, isOwnerPost: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(post.content, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = listOfNotNull(
                    post.authorName ?: "Anonymous",
                    "Owner".takeIf { isOwnerPost },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RatingCard(
    score: Int,
    comment: String,
    isSaving: Boolean,
    onCommentChange: (String) -> Unit,
    onRate: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Rate this club", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Only members can rate a club they belong to.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(modifier = Modifier.padding(top = 8.dp)) {
                (1..5).forEach { star ->
                    IconButton(onClick = { onRate(star) }, enabled = !isSaving) {
                        Icon(
                            imageVector = if (star <= score) Icons.Default.Star else Icons.Outlined.StarOutline,
                            contentDescription = "Rate $star ${if (star == 1) "star" else "stars"}",
                            tint = if (star <= score) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = comment,
                onValueChange = onCommentChange,
                label = { Text("Optional comment") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }
    }
}

@Composable
private fun MemberRow(member: ClubMember, isOwner: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = listOfNotNull(member.displayName ?: "Anonymous", "Owner".takeIf { isOwner })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            member.areaName?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
