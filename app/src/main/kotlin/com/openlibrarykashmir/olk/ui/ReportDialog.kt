package com.openlibrarykashmir.olk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openlibrarykashmir.olk.core.data.model.ReportReason
import com.openlibrarykashmir.olk.core.data.model.ReportOutcome

/**
 * The website's report modal: one of the listed reasons, optional details.
 * [subject] is what is being reported: a book's title or a member's name.
 */
@Composable
fun ReportDialog(
    subject: String,
    isSending: Boolean,
    onDismiss: () -> Unit,
    onSend: (ReportReason, String) -> Unit,
    title: String = "Report this book",
) {
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var details by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = subject,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ReportReason.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = reason == option,
                                enabled = !isSending,
                                onClick = { reason = option },
                            )
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = reason == option,
                            onClick = { reason = option },
                            enabled = !isSending,
                        )
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { if (it.length <= ReportReason.DETAILS_MAX) details = it },
                    label = { Text("Anything else? (optional)") },
                    enabled = !isSending,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { reason?.let { onSend(it, details) } },
                enabled = reason != null && !isSending,
            ) {
                Text(if (isSending) "Sending..." else "Send report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSending) { Text("Cancel") }
        },
    )
}

/** What to tell the member after a report, for [what] ("this book", "this member"). */
fun ReportOutcome.message(what: String): String = when (this) {
    ReportOutcome.Sent -> "Thanks. The OLK team will review your report."
    ReportOutcome.AlreadyReported -> "You've already reported $what, and it's waiting for review."
    ReportOutcome.DailyLimitReached -> "You've sent a lot of reports today. Please try again tomorrow."
    ReportOutcome.Suspended -> "Your account is suspended, so you can't send reports until it ends."
}
