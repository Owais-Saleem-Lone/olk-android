package com.openlibrarykashmir.olk.ui

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * PostgREST returns `2026-09-17T12:50:01.783555+00:00`; Realtime records carry
 * Postgres' text form, `2026-09-17 12:50:01.783555+00`. Accept both.
 */
fun parseTimestamp(value: String): OffsetDateTime? {
    val iso = value.trim().replaceFirst(' ', 'T')
        // A trailing "+00" or "+0530" offset becomes "+00:00" / "+05:30".
        .replace(TRAILING_SHORT_OFFSET) { m -> "${m.groupValues[1]}:${m.groupValues[2].ifEmpty { "00" }}" }
    return runCatching { OffsetDateTime.parse(iso) }.getOrNull()
}

/** An offset without minutes, or without the colon, at the very end of a timestamp. */
private val TRAILING_SHORT_OFFSET = Regex("(?<=\\d)([+-]\\d{2})(\\d{2})?$")

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/** Same buckets as the web's message inbox and notifications page. */
fun timeAgo(createdAt: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val then = parseTimestamp(createdAt)?.toInstant() ?: return ""
    val minutes = Duration.between(then, now).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 48 -> "yesterday"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
        else -> then.atZone(zone).format(SHORT_DATE)
    }
}
