package com.openlibrarykashmir.olk.feature.notifications

/**
 * Icons per notification type, kept in step with the web's
 * `src/lib/notification-icons.ts`. Types the app does not know about fall back
 * to the bell, the same way the web does.
 */
val NOTIFICATION_ICONS: Map<String, String> = mapOf(
    "book_requested" to "📩",
    "request_accepted" to "✅",
    "request_declined" to "❌",
    "request_cancelled" to "↩️",
    "new_message" to "💬",
    "wishlist_match" to "🌟",
    "handover_confirmed" to "🤝",
    "book_returned" to "📗",
    "club_joined" to "🏘️",
    "club_announcement" to "📢",
    "event_created" to "📅",
    "club_request_approved" to "🎉",
    "club_request_rejected" to "ℹ️",
    "admin_message" to "🛟",
)

const val FALLBACK_NOTIFICATION_ICON = "🔔"

/** Where tapping a notification should take the reader. */
sealed interface NotificationTarget {
    data class Book(val bookId: String) : NotificationTarget
    data class Chat(val requestId: String) : NotificationTarget
    data object Requests : NotificationTarget
    data object Messages : NotificationTarget
    data object MyBooks : NotificationTarget
    data class Club(val clubId: String) : NotificationTarget
    data object Clubs : NotificationTarget
    data class Event(val eventId: String) : NotificationTarget
    data object Events : NotificationTarget
    data object Support : NotificationTarget
    data object JoinTeam : NotificationTarget

    /** Nowhere to go — the notification is its own content. Just mark it read. */
    data object None : NotificationTarget

    /** A page the app does not have yet, such as the club-request form. */
    data object WebsiteOnly : NotificationTarget

    /** Messaging exists in the app but an admin has switched the feature off. */
    data object MessagingOff : NotificationTarget

    /** Clubs exist in the app but an admin has switched the feature off. */
    data object ClubsOff : NotificationTarget

    /** Events exist in the app but an admin has switched events (or clubs) off. */
    data object EventsOff : NotificationTarget
}

/**
 * Maps a notification's `link` — always a path on the website, because the
 * database triggers write them for the web app — onto a screen in this app.
 *
 * Unknown paths deliberately resolve to [NotificationTarget.WebsiteOnly] rather
 * than being ignored: a new notification type shipped on the website should
 * still tell the reader there is something to see, not silently do nothing.
 */
fun notificationTarget(
    link: String?,
    messagingEnabled: Boolean = true,
    clubsEnabled: Boolean = true,
    eventsEnabled: Boolean = true,
): NotificationTarget {
    val path = link?.trim()?.substringBefore('?')?.trimEnd('/').orEmpty()
    if (path.isEmpty() || path == "/notifications") return NotificationTarget.None

    val segments = path.removePrefix("/").split('/')
    return when {
        segments.size == 2 && segments[0] == "books" -> NotificationTarget.Book(segments[1])
        segments[0] == "clubs" && !clubsEnabled -> NotificationTarget.ClubsOff
        // club_joined, club_membership_approved, club_announcement and
        // club_request_approved all link to a club. "create" is the website's
        // club-request form, which the app does not have.
        segments.size == 2 && segments[0] == "clubs" && segments[1] != "create" ->
            NotificationTarget.Club(segments[1])
        segments.size == 1 && segments[0] == "clubs" -> NotificationTarget.Clubs
        // Events belong to clubs, so switching clubs off hides them as well.
        segments[0] == "events" && !(eventsEnabled && clubsEnabled) -> NotificationTarget.EventsOff
        // event_created links to the event itself.
        segments.size == 2 && segments[0] == "events" -> NotificationTarget.Event(segments[1])
        segments.size == 1 && segments[0] == "events" -> NotificationTarget.Events
        // admin_message: a reply from the admin team.
        segments[0] == "support" -> NotificationTarget.Support
        segments[0] == "join-team" -> NotificationTarget.JoinTeam
        segments[0] == "requests" -> NotificationTarget.Requests
        segments[0] == "my-books" -> NotificationTarget.MyBooks
        segments[0] == "messages" && !messagingEnabled -> NotificationTarget.MessagingOff
        segments.size == 2 && segments[0] == "messages" -> NotificationTarget.Chat(segments[1])
        segments.size == 1 && segments[0] == "messages" -> NotificationTarget.Messages
        else -> NotificationTarget.WebsiteOnly
    }
}
