package com.openlibrarykashmir.olk.feature.events

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.openlibrarykashmir.olk.core.data.model.Event
import com.openlibrarykashmir.olk.core.data.model.EventAttendee
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    eventId: String,
    onBack: () -> Unit,
    onClubClick: (String) -> Unit,
    onPersonClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EventDetailViewModel = koinViewModel { parametersOf(eventId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmingCancel by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Opening another app can fail (no browser, no calendar); say so rather than crash.
    val launch: (Intent, String) -> Unit = { intent, whenMissing ->
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(whenMissing) }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Event") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        val event = state.event

        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.notFound || event == null -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // Cancelled events and those of a club that was taken down are
                    // unreadable, so they land here too.
                    text = state.error ?: "This event is not available. It may have been cancelled.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> PullToRefreshBox(
                isRefreshing = false,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        EventHeader(
                            event = event,
                            creatorName = state.creatorName,
                            onClubClick = { onClubClick(event.clubId) },
                            onCreatorClick = { onPersonClick(event.creatorId) },
                        )
                    }

                    item {
                        EventActions(
                            state = state,
                            onRsvp = viewModel::toggleRsvp,
                            onJoinClub = { onClubClick(event.clubId) },
                            onOpenMeeting = { url ->
                                launch(Intent(Intent.ACTION_VIEW, url.toUri()), "No app can open this link.")
                            },
                            onAddToCalendar = {
                                launch(calendarIntent(event, state.meetingUrl), "No calendar app found.")
                            },
                            onCancelEvent = { confirmingCancel = true },
                        )
                    }

                    item {
                        Text("Going (${event.attendeeCount})", style = MaterialTheme.typography.titleMedium)
                    }

                    when {
                        !state.canSeeAttendees -> item {
                            Text(
                                text = "Join the club or RSVP to see who's going.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        state.attendees.isEmpty() -> item {
                            Text(
                                text = "No one has RSVP'd yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> items(state.attendees, key = { it.userId }) { attendee ->
                            AttendeeRow(attendee = attendee, onClick = { onPersonClick(attendee.userId) })
                        }
                    }
                }
            }
        }
    }

    if (confirmingCancel) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingCancel = false },
            title = { Text("Cancel this event?") },
            text = { Text("This cannot be undone. Attendees will no longer see it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingCancel = false
                    viewModel.cancelEvent()
                }) { Text("Cancel event") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingCancel = false }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun EventHeader(
    event: Event,
    creatorName: String?,
    onClubClick: () -> Unit,
    onCreatorClick: () -> Unit,
) {
    Column {
        if (event.coverUrl != null) {
            AsyncImage(
                model = event.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }

        event.clubName?.let {
            TextButton(onClick = onClubClick, contentPadding = PaddingValues(0.dp)) {
                Text(it, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (event.isMembersOnly) MembersOnlyLabel()
        Text(event.title, style = MaterialTheme.typography.headlineSmall)

        event.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }

        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(eventWhenLong(event.startsAt, event.endsAt), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (event.isOnline) "Online event" else eventPlace(false, event.locationName),
                style = MaterialTheme.typography.bodyMedium,
            )
            event.capacity?.let {
                Text("${event.attendeeCount} / $it spots filled", style = MaterialTheme.typography.bodyMedium)
            }
        }

        TextButton(onClick = onCreatorClick, contentPadding = PaddingValues(0.dp)) {
            Text("Organised by ${creatorName ?: "Anonymous"}")
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EventActions(
    state: EventDetailUiState,
    onRsvp: () -> Unit,
    onJoinClub: () -> Unit,
    onOpenMeeting: (String) -> Unit,
    onAddToCalendar: () -> Unit,
    onCancelEvent: () -> Unit,
) {
    when {
        state.isCancelled -> Notice("This event has been cancelled. It is no longer listed and nobody can RSVP.")

        state.hasEnded -> Notice("This event has ended.")

        else -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                !state.canRsvp -> OutlinedButton(onClick = onJoinClub) { Text("Join the club to RSVP") }

                state.isGoing -> OutlinedButton(onClick = onRsvp, enabled = !state.isRsvping) {
                    Text("Cancel RSVP")
                }

                else -> Button(onClick = onRsvp, enabled = !state.isRsvping && !state.isFull) {
                    Text(if (state.isFull) "Event full" else "I'm going")
                }
            }

            safeMeetingUrl(state.meetingUrl)?.let { url ->
                Button(onClick = { onOpenMeeting(url) }) { Text("Join meeting") }
            }

            OutlinedButton(onClick = onAddToCalendar) { Text("Add to calendar") }

            if (state.isCreator) {
                TextButton(onClick = onCancelEvent, enabled = !state.isCancelling) {
                    Text("Cancel event", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun AttendeeRow(attendee: EventAttendee, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = attendee.displayName ?: "Anonymous",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * Hands the event to the phone's calendar app to save — no calendar permission,
 * the user confirms it there. Mirrors the website's .ics file: an hour long when
 * no end is set, and for an online event the joining link as the location, but
 * only when this reader is allowed to have it.
 */
private fun calendarIntent(event: Event, meetingUrl: String?): Intent {
    val begin = eventMillis(event.startsAt)
    val end = eventMillis(event.endsAt) ?: begin?.plus(ONE_HOUR_MS)
    val location = if (event.isOnline) safeMeetingUrl(meetingUrl) ?: "Online" else event.locationName.orEmpty()
    val description = listOfNotNull(
        event.description?.takeIf { it.isNotBlank() },
        event.clubName?.let { "Hosted by $it" },
    ).joinToString("\n\n")

    return Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
        putExtra(CalendarContract.Events.TITLE, event.title)
        begin?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        end?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        putExtra(CalendarContract.Events.DESCRIPTION, description)
    }
}

private const val ONE_HOUR_MS = 60 * 60 * 1000L
