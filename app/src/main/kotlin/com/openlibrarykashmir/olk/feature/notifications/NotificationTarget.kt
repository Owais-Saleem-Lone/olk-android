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
    "new_message" to "💬",
    "wishlist_match" to "🌟",
    "handover_confirmed" to "🤝",
    "book_returned" to "📗",
    "club_joined" to "🏘️",
    "club_announcement" to "📢",
    "event_created" to "📅",
    "club_request_approved" to "🎉",
    "club_request_rejected" to "ℹ️",
)

const val FALLBACK_NOTIFICATION_ICON = "🔔"

/** Where tapping a notification should take the reader. */
sealed interface NotificationTarget {
    data class Book(val bookId: String) : NotificationTarget
    data class Chat(val requestId: String) : NotificationTarget
    data object Requests : NotificationTarget
    data object Messages : NotificationTarget
    data object MyBooks : NotificationTarget

    /** Nowhere to go — the notification is its own content. Just mark it read. */
    data object None : NotificationTarget

    /** A page the app does not have yet: profile, clubs, events. */
    data object WebsiteOnly : NotificationTarget

    /** Messaging exists in the app but an admin has switched the feature off. */
    data object MessagingOff : NotificationTarget
}

/**
 * Maps a notification's `link` — always a path on the website, because the
 * database triggers write them for the web app — onto a screen in this app.
 *
 * Unknown paths deliberately resolve to [NotificationTarget.WebsiteOnly] rather
 * than being ignored: a new notification type shipped on the website should
 * still tell the reader there is something to see, not silently do nothing.
 */
fun notificationTarget(link: String?, messagingEnabled: Boolean = true): NotificationTarget {
    val path = link?.trim()?.substringBefore('?')?.trimEnd('/').orEmpty()
    if (path.isEmpty() || path == "/notifications") return NotificationTarget.None

    val segments = path.removePrefix("/").split('/')
    return when {
        segments.size == 2 && segments[0] == "books" -> NotificationTarget.Book(segments[1])
        segments[0] == "requests" -> NotificationTarget.Requests
        segments[0] == "my-books" -> NotificationTarget.MyBooks
        segments[0] == "messages" && !messagingEnabled -> NotificationTarget.MessagingOff
        segments.size == 2 && segments[0] == "messages" -> NotificationTarget.Chat(segments[1])
        segments.size == 1 && segments[0] == "messages" -> NotificationTarget.Messages
        else -> NotificationTarget.WebsiteOnly
    }
}
