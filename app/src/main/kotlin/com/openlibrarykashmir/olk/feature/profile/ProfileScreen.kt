package com.openlibrarykashmir.olk.feature.profile

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlibrarykashmir.olk.core.data.repository.ProfileRepository
import org.koin.androidx.compose.koinViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // Asked at the moment of the tap, never up front. Whatever the answer, the
    // view model finds out from the locator (which re-checks the permission).
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.shareLocation()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Your profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val current = state) {
                ProfileUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is ProfileUiState.Error -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(current.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = viewModel::load, modifier = Modifier.padding(top = 16.dp)) { Text("Try again") }
                    TextButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
                }

                is ProfileUiState.Editing -> ProfileFields(
                    state = current,
                    viewModel = viewModel,
                    onShareLocation = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                    onSignOut = { confirmSignOut = true },
                )
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("You can sign back in any time with your email and password.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileFields(
    state: ProfileUiState.Editing,
    viewModel: ProfileViewModel,
    onShareLocation: () -> Unit,
    onSignOut: () -> Unit,
) {
    val form = state.form
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.suspension?.let { SuspensionCard(it) }

        OutlinedTextField(
            value = form.displayName,
            onValueChange = viewModel::onDisplayNameChange,
            label = { Text("Display name") },
            placeholder = { Text("e.g. Owais S.") },
            singleLine = true,
            isError = state.showErrors && form.displayNameError != null,
            supportingText = {
                Row {
                    Text(
                        text = if (state.showErrors) form.displayNameError ?: VISIBLE_HINT else VISIBLE_HINT,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${form.displayName.length}/${ProfileRepository.MAX_DISPLAY_NAME}")
                }
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )

        AreaField(
            value = form.areaName,
            suggestions = state.areaSuggestions,
            onValueChange = viewModel::onAreaChange,
        )

        OutlinedTextField(
            value = form.bio,
            onValueChange = viewModel::onBioChange,
            label = { Text("Bio") },
            placeholder = { Text("A few words about yourself and the kinds of books you love…") },
            minLines = 3,
            supportingText = {
                Row {
                    Text("Shown on your public profile", modifier = Modifier.weight(1f))
                    Text("${form.bio.length}/${ProfileRepository.MAX_BIO}")
                }
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )

        LocationCard(
            hasLocation = form.location != null,
            isLocating = state.isLocating,
            onShare = onShareLocation,
            onRemove = viewModel::removeLocation,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Weekly digest email", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "New books near you, once a week",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = form.emailDigest, onCheckedChange = viewModel::onEmailDigestChange)
        }

        Button(
            onClick = viewModel::save,
            enabled = !state.isSaving && state.hasChanges,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Save profile")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Sign out", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private const val VISIBLE_HINT = "Other readers see this next to your books"

/**
 * Free text with suggestions, like the website's AreaInput: the admin-curated
 * list is nowhere near every locality people actually live in, so it suggests
 * and never restricts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AreaField(value: String, suggestions: List<String>, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val matches = remember(value, suggestions) { matchingAreas(value, suggestions) }

    ExposedDropdownMenuBox(expanded = expanded && matches.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text("Area / locality") },
            placeholder = { Text("e.g. Srinagar - Rajbagh") },
            singleLine = true,
            supportingText = { Text("Keep it approximate — no house numbers") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded && matches.isNotEmpty(), onDismissRequest = { expanded = false }) {
            matches.forEach { area ->
                DropdownMenuItem(
                    text = { Text(area) },
                    onClick = {
                        onValueChange(area)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Case-insensitive "contains", capped so the menu stays short; the exact current value is left out. */
internal fun matchingAreas(query: String, suggestions: List<String>, limit: Int = 6): List<String> {
    val term = query.trim()
    if (term.isEmpty()) return emptyList()
    return suggestions
        .filter { it.contains(term, ignoreCase = true) && !it.equals(term, ignoreCase = true) }
        .take(limit)
}

@Composable
private fun LocationCard(hasLocation: Boolean, isLocating: Boolean, onShare: () -> Unit, onRemove: () -> Unit) {
    Column {
        Text("Location", style = MaterialTheme.typography.titleSmall)
        if (hasLocation) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Text("Location shared", modifier = Modifier.padding(start = 8.dp).weight(1f))
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
        } else {
            OutlinedButton(onClick = onShare, enabled = !isLocating, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (isLocating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Finding your location…", modifier = Modifier.padding(start = 8.dp))
                } else {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Share my location", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Text(
            "Helps show books near you. Only your approximate area is used, and no one else ever sees it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
private fun SuspensionCard(suspension: Suspension) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = suspension.until?.let { "Your account is suspended until ${DATE.format(it.atZone(ZoneId.systemDefault()))}" }
                    ?: "Your account is suspended",
                style = MaterialTheme.typography.titleSmall,
            )
            suspension.reason?.takeIf { it.isNotBlank() }?.let {
                Text("Reason: $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            Text(
                "Questions? Use Contact Admin on the website.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
