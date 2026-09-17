package com.openlibrarykashmir.olk.feature.mybooks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlibrarykashmir.olk.core.data.model.BookStatus
import com.openlibrarykashmir.olk.core.data.model.ListingType
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBookScreen(
    bookId: String,
    onBack: () -> Unit,
    onDone: (message: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditBookViewModel = koinViewModel(key = "edit-$bookId") { parametersOf(bookId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EditBookEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is EditBookEvent.Done -> onDone(event.message)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Edit book") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                EditBookUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                EditBookUiState.NotFound -> Message(
                    text = "This book no longer exists.",
                    actionLabel = "Back to My Books",
                    onAction = onBack,
                )

                is EditBookUiState.Error -> Message(
                    text = s.message,
                    actionLabel = "Try again",
                    onAction = viewModel::load,
                )

                is EditBookUiState.Editing -> EditForm(
                    state = s,
                    onFormChange = viewModel::onFormChange,
                    onSave = viewModel::save,
                    onCancel = onBack,
                    onDelete = { confirmDelete = true },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this book?") },
            text = { Text("It will be removed from your listings. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditForm(
    state: EditBookUiState.Editing,
    onFormChange: ((BookForm) -> BookForm) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val form = state.form
    val enabled = !state.isBusy

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = when (state.book.listingType) {
                ListingType.DONATE -> "Giving away"
                ListingType.LEND -> "Lending"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        CoverPicker(
            choice = form.cover,
            enabled = enabled,
            onChoice = { choice -> onFormChange { it.copy(cover = choice) } },
        )

        if (state.book.acquiredViaDonation) {
            Text(
                text = "This book is in permanent circulation. It can only be donated forward, not deleted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = form.title,
            onValueChange = { value -> onFormChange { it.copy(title = value) } },
            label = { Text("Title") },
            singleLine = true,
            enabled = enabled,
            isError = state.showErrors && form.titleError != null,
            supportingText = form.titleError?.takeIf { state.showErrors }?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = form.author,
            onValueChange = { value -> onFormChange { it.copy(author = value) } },
            label = { Text("Author") },
            singleLine = true,
            enabled = enabled,
            isError = state.showErrors && form.authorError != null,
            supportingText = form.authorError?.takeIf { state.showErrors }?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Status")
            val statuses = listOf(
                BookStatus.AVAILABLE to "Available",
                BookStatus.UNAVAILABLE to "Unavailable",
                BookStatus.GIVEN to "Given away",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                statuses.forEachIndexed { index, (status, label) ->
                    SegmentedButton(
                        selected = form.status == status,
                        onClick = { onFormChange { it.copy(status = status) } },
                        shape = SegmentedButtonDefaults.itemShape(index, statuses.size),
                        enabled = enabled,
                    ) { Text(label, maxLines = 1) }
                }
            }
        }

        GenreField(
            value = form.genre,
            options = state.genreOptions,
            enabled = enabled,
            onSelect = { genre -> onFormChange { it.copy(genre = genre) } },
        )

        if (state.book.listingType == ListingType.LEND) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Lending period")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    BookForm.LENDING_PERIODS.forEachIndexed { index, months ->
                        SegmentedButton(
                            selected = form.lendingDurationMonths == months,
                            onClick = { onFormChange { it.copy(lendingDurationMonths = months) } },
                            shape = SegmentedButtonDefaults.itemShape(index, BookForm.LENDING_PERIODS.size),
                            enabled = enabled,
                        ) { Text(if (months == 1) "1 month" else "$months months") }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSave, enabled = enabled, modifier = Modifier.weight(1f)) {
                if (state.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save changes")
                }
            }
            OutlinedButton(onClick = onCancel, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
        }

        if (state.canDelete) {
            HorizontalDivider()
            TextButton(
                onClick = onDelete,
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(if (state.isDeleting) "Deleting…" else "Delete book")
            }
        }
    }
}

@Composable
private fun Message(text: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(actionLabel) }
    }
}
