package com.openlibrarykashmir.olk.feature.events

import com.openlibrarykashmir.olk.ui.parseTimestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private fun shortDay(locale: Locale) = DateTimeFormatter.ofPattern("EEE d MMM", locale)
private fun longDay(locale: Locale) = DateTimeFormatter.ofPattern("EEEE d MMMM", locale)
private fun time(locale: Locale) = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

/** "Sat 27 Sep, 6:00 PM", as the website's event cards show it. */
fun eventWhenShort(
    startsAt: String,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val start = parseTimestamp(startsAt)?.atZoneSameInstant(zone) ?: return ""
    return "${start.format(shortDay(locale))}, ${start.format(time(locale))}"
}

/**
 * "Saturday 27 September, 6:00 PM – 8:00 PM". The end repeats its date only
 * when it falls on another day, so an overnight event is not misread.
 */
fun eventWhenLong(
    startsAt: String,
    endsAt: String?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val start = parseTimestamp(startsAt)?.atZoneSameInstant(zone) ?: return ""
    val startText = "${start.format(longDay(locale))}, ${start.format(time(locale))}"
    val end = endsAt?.let(::parseTimestamp)?.atZoneSameInstant(zone) ?: return startText

    val endText = if (end.toLocalDate() == start.toLocalDate()) {
        end.format(time(locale))
    } else {
        "${end.format(longDay(locale))}, ${end.format(time(locale))}"
    }
    return "$startText – $endText"
}

/** Where it happens, with the website's wording for the two gaps. */
fun eventPlace(isOnline: Boolean, locationName: String?): String = when {
    isOnline -> "Online"
    !locationName.isNullOrBlank() -> locationName.trim()
    else -> "Location TBA"
}

/**
 * Whether the event is over: its end if it has one, otherwise its start. The
 * database still accepts an RSVP to a past event, so the app is what stops
 * offering one.
 */
fun eventHasEnded(startsAt: String, endsAt: String?, now: Instant = Instant.now()): Boolean {
    val last = (endsAt ?: startsAt).let(::parseTimestamp)?.toInstant() ?: return false
    return last.isBefore(now)
}

/**
 * The meeting link, only if it is a web address. It is typed in by the
 * organiser, so anything else (an `intent:` or `file:` URI, say) is not handed
 * to the system to open.
 */
fun safeMeetingUrl(url: String?): String? {
    val trimmed = url?.trim().orEmpty()
    val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
    return trimmed.takeIf { scheme == "https" || scheme == "http" }
}

/** Milliseconds since the epoch, for the calendar app; null if unparseable. */
fun eventMillis(timestamp: String?): Long? =
    timestamp?.let(::parseTimestamp)?.toInstant()?.toEpochMilli()
