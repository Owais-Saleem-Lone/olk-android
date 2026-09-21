package com.openlibrarykashmir.olk.ui

import com.openlibrarykashmir.olk.core.data.repository.OwnProfile
import java.time.Instant

/** An account suspension that is in force right now. */
data class Suspension(val reason: String?, val until: Instant?)

/**
 * The suspension in force for [profile], or null. Same rule as the website's
 * proxy and the database's `caller_is_suspended()`: a suspension whose end date
 * has passed no longer applies. The database is what actually holds a
 * suspended member back (web migration `20260921182823`); the app only says so.
 */
fun activeSuspension(profile: OwnProfile?, now: Instant = Instant.now()): Suspension? {
    if (profile?.isBanned != true) return null
    val until = profile.banExpiresAt?.let { parseTimestamp(it)?.toInstant() }
    if (until != null && !until.isAfter(now)) return null
    return Suspension(reason = profile.banReason, until = until)
}
