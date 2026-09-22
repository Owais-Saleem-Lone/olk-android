package com.openlibrarykashmir.olk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The app's version of the website's suspension notice. The database is what
 * stops a suspended member from listing, requesting, messaging, joining or
 * posting; this says so before a button fails.
 */
@Composable
fun SuspensionBanner(
    suspension: Suspension,
    onSeeWhy: () -> Unit,
    onContactAdmin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp)) {
            Text(suspensionHeadline(suspension), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Your books are hidden and your open requests were closed. You can finish exchanges where a book has changed hands, and message about those, but not list, request, join or post.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = onSeeWhy, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("See why") }
                TextButton(onClick = onContactAdmin, modifier = Modifier.padding(start = 16.dp)) { Text("Contact Admin") }
            }
        }
    }
}

private val UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy")

/** "Your account is suspended until 4 October 2026", or without a date if it has none. */
fun suspensionHeadline(suspension: Suspension, zone: ZoneId = ZoneId.systemDefault()): String =
    suspension.until?.let { "Your account is suspended until ${UNTIL.format(it.atZone(zone))}" }
        ?: "Your account is suspended"
