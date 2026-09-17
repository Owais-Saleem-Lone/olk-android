package com.openlibrarykashmir.olk.feature.mybooks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlibrarykashmir.olk.core.data.model.BookCondition
import com.openlibrarykashmir.olk.core.data.model.ListingType
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookScreen(
    onBack: () -> Unit,
    onDone: (message: String) -> Unit,
    onScanIsbn: () -> Unit,
    scannedIsbn: String?,
    onScannedIsbnUsed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddBookViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EditBookEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is EditBookEvent.Done -> onDone(event.message)
            }
        }
    }

    LaunchedEffect(scannedIsbn) {
        if (scannedIsbn != null) {
            viewModel.applyIsbn(scannedIsbn)
            onScannedIsbnUsed()
        }
    }

    val form = state.form
    val enabled = !state.isSaving && !state.isLookingUp

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Add a book") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onScanIsbn, enabled = enabled) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Scan ISBN", modifier = Modifier.padding(start = 6.dp))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = form.title,
                onValueChange = { value -> viewModel.onFormChange { it.copy(title = value) } },
                label = { Text("Title") },
                placeholder = { Text("e.g. The Alchemist") },
                singleLine = true,
                enabled = enabled,
                isError = state.showErrors && form.titleError != null,
                supportingText = form.titleError?.takeIf { state.showErrors }?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.author,
                onValueChange = { value -> viewModel.onFormChange { it.copy(author = value) } },
                label = { Text("Author") },
                placeholder = { Text("e.g. Paulo Coelho") },
                singleLine = true,
                enabled = enabled,
                isError = state.showErrors && form.authorError != null,
                supportingText = form.authorError?.takeIf { state.showErrors }?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Cover photo (optional)")
                CoverPicker(
                    choice = form.cover,
                    enabled = enabled,
                    onChoice = { choice -> viewModel.onFormChange { it.copy(cover = choice) } },
                )
            }

            GenreField(
                value = form.genre,
                options = state.genreOptions,
                enabled = enabled,
                onSelect = { genre -> viewModel.onFormChange { it.copy(genre = genre) } },
            )

            OutlinedTextField(
                value = form.publicationYear,
                onValueChange = { value ->
                    viewModel.onFormChange { it.copy(publicationYear = value.filter(Char::isDigit).take(4)) }
                },
                label = { Text("Publication year (optional)") },
                placeholder = { Text("e.g. 2008") },
                singleLine = true,
                enabled = enabled,
                isError = state.showErrors && form.yearError != null,
                supportingText = form.yearError?.takeIf { state.showErrors }?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.description,
                onValueChange = { value -> viewModel.onFormChange { it.copy(description = value) } },
                label = { Text("Description (optional)") },
                placeholder = { Text("A short blurb about the book") },
                minLines = 3,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            SegmentedChoice(
                label = "Condition",
                options = listOf(
                    BookCondition.EXCELLENT to "Excellent",
                    BookCondition.GOOD to "Good",
                    BookCondition.FAIR to "Fair",
                    BookCondition.POOR to "Poor",
                ),
                selected = form.condition,
                enabled = enabled,
                onSelect = { condition -> viewModel.onFormChange { it.copy(condition = condition) } },
            )

            SegmentedChoice(
                label = "I want to",
                options = listOf(ListingType.DONATE to "Donate", ListingType.LEND to "Lend"),
                selected = form.listingType,
                enabled = enabled,
                onSelect = { type -> viewModel.onFormChange { it.copy(listingType = type) } },
            )

            if (form.listingType == ListingType.LEND) {
                SegmentedChoice(
                    label = "Lending period",
                    options = BookForm.LENDING_PERIODS.map { it to if (it == 1) "1 month" else "$it months" },
                    selected = form.lendingDurationMonths,
                    enabled = enabled,
                    onSelect = { months -> viewModel.onFormChange { it.copy(lendingDurationMonths = months) } },
                )
            }

            Button(onClick = viewModel::save, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                if (state.isSaving || state.isLookingUp) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Add book")
                }
            }
        }
    }
}
