package com.openlibrarykashmir.olk.feature.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlibrarykashmir.olk.core.data.model.OrganiserRules
import com.openlibrarykashmir.olk.feature.mybooks.CoverPicker
import com.openlibrarykashmir.olk.feature.mybooks.FieldLabel
import com.openlibrarykashmir.olk.feature.mybooks.SegmentedChoice
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateEventScreen(
    clubId: String,
    clubName: String,
    onBack: () -> Unit,
    onCreated: (eventId: String) -> Unit,
    viewModel: CreateEventViewModel = koinViewModel(key = "create-event-$clubId") { parametersOf(clubId, clubName) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(state.createdEventId) { state.createdEventId?.let(onCreated) }

    val form = state.form
    val errors = viewModel.errors(form)
    val enabled = !state.isSaving
    fun err(message: String?) = if (state.showErrors) message else null

    // Which picker is open: "date", "start" or "end".
    var picker by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Schedule an event") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                "For ${state.clubName}. Members are told as soon as it's scheduled.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = form.title,
                onValueChange = { v -> viewModel.onFormChange { it.copy(title = v) } },
                label = { Text("Title") },
                placeholder = { Text("e.g. Monthly Book Discussion") },
                singleLine = true,
                enabled = enabled,
                isError = err(errors.title) != null,
                supportingText = err(errors.title)?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.description,
                onValueChange = { v -> viewModel.onFormChange { it.copy(description = v) } },
                label = { Text("Description (optional)") },
                placeholder = { Text("What will happen at this event?") },
                minLines = 3,
                enabled = enabled,
                supportingText = { Text("${form.description.length}/${OrganiserRules.EVENT_DESCRIPTION_CHARS}") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("When")
                OutlinedButton(onClick = { picker = "date" }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(form.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: "Pick a date")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker = "start" }, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text(form.startTime?.let { "Starts ${it.formatShort()}" } ?: "Start time")
                    }
                    OutlinedButton(onClick = { picker = "end" }, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text(form.endTime?.let { "Ends ${it.formatShort()}" } ?: "End (optional)")
                    }
                }
                (err(errors.start) ?: err(errors.end))?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("This is an online event", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = form.isOnline,
                    onCheckedChange = { on -> viewModel.onFormChange { it.copy(isOnline = on) } },
                    enabled = enabled,
                )
            }
            if (form.isOnline) {
                OutlinedTextField(
                    value = form.meetingUrl,
                    onValueChange = { v -> viewModel.onFormChange { it.copy(meetingUrl = v) } },
                    label = { Text("Meeting link") },
                    placeholder = { Text("https://meet.google.com/…") },
                    singleLine = true,
                    enabled = enabled,
                    isError = err(errors.meetingUrl) != null,
                    supportingText = {
                        Text(err(errors.meetingUrl) ?: "Only people going (and the organiser) see this link.")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = form.venue,
                    onValueChange = { v -> viewModel.onFormChange { it.copy(venue = v) } },
                    label = { Text("Venue (optional)") },
                    placeholder = { Text("e.g. Cafe Fiction, Anantnag") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SegmentedChoice(
                label = "Who can come",
                options = listOf(false to "Everyone", true to "Members only"),
                selected = form.membersOnly,
                enabled = enabled,
                onSelect = { v -> viewModel.onFormChange { it.copy(membersOnly = v) } },
            )

            OutlinedTextField(
                value = form.capacity,
                onValueChange = { v -> viewModel.onFormChange { it.copy(capacity = v) } },
                label = { Text("Capacity (optional)") },
                placeholder = { Text("Max ${OrganiserRules.MAX_CAPACITY}") },
                singleLine = true,
                enabled = enabled,
                isError = err(errors.capacity) != null,
                supportingText = { Text(err(errors.capacity) ?: "Leave empty for the maximum of ${OrganiserRules.MAX_CAPACITY}.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Cover image (optional)")
                CoverPicker(choice = form.cover, enabled = enabled, onChoice = viewModel::onCoverChoice)
                OutlinedTextField(
                    value = form.coverLink,
                    onValueChange = viewModel::onCoverLink,
                    label = { Text("…or paste an image link") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    enabled = enabled,
                    isError = err(errors.coverLink) != null,
                    supportingText = err(errors.coverLink)?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(onClick = viewModel::create, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (state.isSaving) "Scheduling…" else "Schedule event")
            }
        }
    }

    when (picker) {
        "date" -> EventDatePicker(
            initial = form.date,
            onDismiss = { picker = null },
            onPick = { d -> viewModel.onFormChange { it.copy(date = d) }; picker = null },
        )
        "start", "end" -> EventTimePicker(
            title = if (picker == "start") "Start time" else "End time",
            initial = if (picker == "start") form.startTime else form.endTime ?: form.startTime,
            onDismiss = { picker = null },
            onClear = if (picker == "end" && form.endTime != null) {
                { viewModel.onFormChange { it.copy(endTime = null) }; picker = null }
            } else {
                null
            },
            onPick = { t ->
                val which = picker
                viewModel.onFormChange { if (which == "start") it.copy(startTime = t) else it.copy(endTime = t) }
                picker = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventDatePicker(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The picker works in UTC midnights; today (local) is the earliest choice.
    val todayUtcMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtcMillis
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventTimePicker(
    title: String,
    initial: LocalTime?,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)?,
    onPick: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial?.hour ?: 18, initialMinute = initial?.minute ?: 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = {
            Row {
                onClear?.let { TextButton(onClick = it) { Text("No end time") } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun LocalTime.formatShort(): String = format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
