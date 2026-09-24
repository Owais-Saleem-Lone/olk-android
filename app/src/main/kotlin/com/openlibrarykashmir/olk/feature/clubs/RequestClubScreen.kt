package com.openlibrarykashmir.olk.feature.clubs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlibrarykashmir.olk.core.data.model.CLUB_INTERESTS
import com.openlibrarykashmir.olk.core.data.model.ClubRequestStatus
import com.openlibrarykashmir.olk.core.data.model.OrganiserRules
import com.openlibrarykashmir.olk.feature.mybooks.CoverPicker
import com.openlibrarykashmir.olk.feature.mybooks.FieldLabel
import com.openlibrarykashmir.olk.feature.profile.AreaField
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestClubScreen(
    onBack: () -> Unit,
    onOpenClub: (clubId: String) -> Unit,
    viewModel: RequestClubViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Request a club") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                RequestClubUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is RequestClubUiState.Error -> Centered(s.message, "Try again", viewModel::load)
                is RequestClubUiState.NotEligible -> NotEligible(s, onBack)
                is RequestClubUiState.UnderReview -> UnderReview(s, viewModel::withdraw)
                is RequestClubUiState.Form -> RequestForm(s, viewModel, onOpenClub)
            }
        }
    }
}

@Composable
private fun NotEligible(state: RequestClubUiState.NotEligible, onBack: () -> Unit) {
    val e = state.eligibility
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text("Not eligible to request a club yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        OutlinedCard(Modifier.fillMaxWidth().padding(top = 20.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Requirement(
                    met = e.completedExchanges >= e.minExchanges,
                    title = "${e.minExchanges}+ completed exchanges",
                    detail = "You have ${e.completedExchanges} so far",
                )
                Requirement(
                    met = e.reportCount == 0,
                    title = "No upheld reports of misconduct",
                    detail = if (e.reportCount == 0) "Clean record" else "A report against you was upheld",
                )
            }
        }
        Text(
            "Keep sharing books and building trust — you'll be eligible soon!",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("Back to clubs") }
    }
}

@Composable
private fun Requirement(met: Boolean, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (met) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = if (met) "Met" else "Not met",
            tint = if (met) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UnderReview(state: RequestClubUiState.UnderReview, onWithdraw: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text("Your request is under review", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        OutlinedCard(Modifier.fillMaxWidth().padding(top = 20.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(state.request.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "An admin will approve or reject it soon.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "Keep an eye on your inbox — we'll email you asking for your ID and CV as part of verifying this request.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        TextButton(onClick = onWithdraw, enabled = !state.isWithdrawing, modifier = Modifier.padding(top = 16.dp)) {
            Text(
                if (state.isWithdrawing) "Withdrawing…" else "Withdraw this request",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestForm(
    state: RequestClubUiState.Form,
    viewModel: RequestClubViewModel,
    onOpenClub: (String) -> Unit,
) {
    val form = state.form
    val enabled = !state.isSubmitting
    fun err(message: String?) = if (state.showErrors) message else null

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            "Start a local interest group for readers near you — an admin will review your request before it goes live.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(IdCvNotice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        state.previous?.let { previous ->
            when (previous.status) {
                ClubRequestStatus.REJECTED -> OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Your last request, \"${previous.name}\", was not approved.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        previous.reviewNote?.takeIf { it.isNotBlank() }?.let {
                            Text("Admin note: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                ClubRequestStatus.APPROVED -> previous.createdClubId?.let { clubId ->
                    OutlinedCard(onClick = { onOpenClub(clubId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "You already run \"${previous.name}\". You can still request another club below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                ClubRequestStatus.PENDING -> Unit
            }
        }

        OutlinedTextField(
            value = form.name,
            onValueChange = { v -> viewModel.onFormChange { it.copy(name = v) } },
            label = { Text("Club name") },
            placeholder = { Text("e.g. English Fiction Club Anantnag") },
            singleLine = true,
            enabled = enabled,
            isError = err(form.nameError) != null,
            supportingText = { Text(err(form.nameError) ?: words(form.name, OrganiserRules.NAME_WORDS)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Categories")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CLUB_INTERESTS.forEach { interest ->
                    FilterChip(
                        selected = interest in form.interests,
                        onClick = { viewModel.toggleInterest(interest) },
                        label = { Text(interest) },
                        enabled = enabled,
                        leadingIcon = if (interest in form.interests) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
            err(form.interestsError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        LongField(
            value = form.description,
            label = "Description",
            placeholder = "What's your club about? What will members actually do together?",
            error = err(form.descriptionError),
            maxWords = OrganiserRules.DESCRIPTION_WORDS,
            minLines = 4,
            enabled = enabled,
        ) { v -> viewModel.onFormChange { it.copy(description = v) } }

        LongField(
            value = form.goal,
            label = "Goal (optional)",
            placeholder = "What do you want this club to achieve?",
            error = err(form.goalError),
            maxWords = OrganiserRules.SHORT_TEXT_WORDS,
            minLines = 2,
            enabled = enabled,
        ) { v -> viewModel.onFormChange { it.copy(goal = v) } }

        LongField(
            value = form.targetMembers,
            label = "Target members (optional)",
            placeholder = "Who are you hoping will join? e.g. college students who love poetry",
            error = err(form.targetMembersError),
            maxWords = OrganiserRules.SHORT_TEXT_WORDS,
            minLines = 2,
            enabled = enabled,
        ) { v -> viewModel.onFormChange { it.copy(targetMembers = v) } }

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
                isError = err(form.coverLinkError) != null,
                supportingText = err(form.coverLinkError)?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column {
            AreaField(
                value = form.areaName,
                suggestions = state.areaSuggestions,
                onValueChange = { v -> viewModel.onFormChange { it.copy(areaName = v) } },
            )
            Text(
                if (state.hasSavedLocation) {
                    "Your saved location (from Profile) is used for nearby discovery."
                } else {
                    "Set your location in Profile to enable nearby discovery."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Button(onClick = viewModel::submit, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (state.isSubmitting) "Submitting…" else "Submit for review")
        }
    }
}

@Composable
private fun LongField(
    value: String,
    label: String,
    placeholder: String,
    error: String?,
    maxWords: Int,
    minLines: Int,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) = OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    placeholder = { Text(placeholder) },
    minLines = minLines,
    enabled = enabled,
    isError = error != null,
    supportingText = { Text(error ?: words(value, maxWords)) },
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    modifier = Modifier.fillMaxWidth(),
)

private fun words(text: String, max: Int) = "${OrganiserRules.wordCount(text)}/$max words"

@Composable
private fun Centered(text: String, action: String, onAction: () -> Unit) = Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier.fillMaxSize().padding(32.dp),
) {
    Text(text, textAlign = TextAlign.Center)
    Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(action) }
}

/** The website's wording: identity is checked by email, never uploaded here. */
private const val IdCvNotice =
    "Part of the review is confirming who you are: after you submit, expect an email from our team asking " +
        "for a copy of your ID and CV. Approval won't happen until that's done."
