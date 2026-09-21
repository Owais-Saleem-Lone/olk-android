package com.openlibrarykashmir.olk.feature.team

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlibrarykashmir.olk.core.data.repository.TeamApplicationRules
import org.koin.androidx.compose.koinViewModel

/** The website's /join-team: volunteer and internship applications, with a CV. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinTeamScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JoinTeamViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The system document picker: the app only ever gets the one file chosen,
    // so no storage permission is needed.
    val pickCv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onCvPicked(it.toString()) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Join the OLK Team") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.submitted) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎉", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "Application received!",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        text = "Thank you for offering your time and skills to OLK. We'll review your " +
                            "application and get back to you at the email you provided.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) { Text("Done") }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "OLK is built and run by volunteers. We're looking for students, professionals, and " +
                    "anyone curious to help — as a short-term intern (3–6 months, studying IT, Computer " +
                    "Science or a related field), or a developer keen to contribute, guide interns, or " +
                    "otherwise pitch in. Tell us about yourself below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Field("Full name", state.fullName) { v -> viewModel.update { it.copy(fullName = v) } }
            Field("Age", state.age, keyboard = KeyboardType.Number) { v ->
                viewModel.update { it.copy(age = v.filter(Char::isDigit).take(3)) }
            }
            Field("Email", state.email, keyboard = KeyboardType.Email) { v -> viewModel.update { it.copy(email = v) } }
            Field("Phone (optional)", state.phone, keyboard = KeyboardType.Phone) { v ->
                viewModel.update { it.copy(phone = v) }
            }
            Field("Profession", state.profession, placeholder = "e.g. Student, Software Developer, Teacher") { v ->
                viewModel.update { it.copy(profession = v) }
            }
            Field("Studies", state.studies, placeholder = "e.g. BSc Computer Science (in progress)") { v ->
                viewModel.update { it.copy(studies = v) }
            }

            FieldOfInterest(selected = state.field) { v -> viewModel.update { it.copy(field = v) } }

            LongAnswer(
                label = "Why do you want to join OLK?",
                value = state.motivation,
                words = state.motivationWords,
                limit = TeamApplicationRules.MOTIVATION_WORD_LIMIT,
            ) { v -> viewModel.update { it.copy(motivation = v) } }

            LongAnswer(
                label = "How would you like to contribute?",
                value = state.contribution,
                words = state.contributionWords,
                limit = TeamApplicationRules.CONTRIBUTION_WORD_LIMIT,
            ) { v -> viewModel.update { it.copy(contribution = v) } }

            Text("CV / Resume", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = { pickCv.launch(TeamApplicationRules.CV_MIME_TYPES.toTypedArray()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(state.cvName ?: "Choose a PDF or Word file (max 4 MB)")
            }
            state.cvError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = viewModel::submit,
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.isSubmitting) "Submitting..." else "Submit application") }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(TeamApplicationRules.MAX_TEXT_LENGTH)) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LongAnswer(label: String, value: String, words: Int, limit: Int, onChange: (String) -> Unit) {
    val over = words > limit
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 4,
        isError = over,
        supportingText = { Text("$words / $limit words") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldOfInterest(selected: String, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Field of interest") },
            placeholder = { Text("Select a field") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TeamApplicationRules.FIELDS.forEach { field ->
                DropdownMenuItem(
                    text = { Text(field) },
                    onClick = {
                        onSelect(field)
                        expanded = false
                    },
                )
            }
        }
    }
}
