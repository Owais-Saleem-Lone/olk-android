package com.openlibrarykashmir.olk.feature.notifications

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Caps the badge the same way the web's bell does. */
internal fun badgeLabel(unreadCount: Int): String = if (unreadCount > 9) "9+" else "$unreadCount"

/**
 * The bell that sits in every top-level screen's top bar, mirroring the web
 * header. Stateless on purpose: the count comes from the one shared
 * [NotificationsViewModel] so every screen shows the same number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationBell(
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        BadgedBox(
            badge = {
                if (unreadCount > 0) {
                    Badge { Text(badgeLabel(unreadCount)) }
                }
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = if (unreadCount > 0) {
                    "Notifications, $unreadCount unread"
                } else {
                    "Notifications"
                },
            )
        }
    }
}
