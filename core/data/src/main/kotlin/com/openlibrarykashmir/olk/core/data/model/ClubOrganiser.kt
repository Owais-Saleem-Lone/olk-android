package com.openlibrarykashmir.olk.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** `my_club_eligibility()`: may the caller ask for a club? */
@Serializable
data class ClubEligibility(
    val eligible: Boolean,
    @SerialName("completed_exchanges") val completedExchanges: Int,
    /** Only UPHELD reports count (web F2). */
    @SerialName("report_count") val reportCount: Int,
    @SerialName("min_exchanges") val minExchanges: Int,
)

enum class ClubRequestStatus { PENDING, APPROVED, REJECTED }

/** The caller's most recent club request, as the website's create page shows it. */
data class ClubRequestSummary(
    val id: String,
    val name: String,
    val status: ClubRequestStatus,
    val reviewNote: String?,
    val createdClubId: String?,
    val createdAt: String?,
)

/** What the "Request a club" form sends; the cover is already an https URL or null. */
data class ClubRequestDraft(
    val name: String,
    val interests: List<String>,
    val description: String,
    val goal: String,
    val targetMembers: String,
    val areaName: String,
    val coverUrl: String?,
)

/** What the "Schedule an event" form sends. */
data class EventDraft(
    val clubId: String,
    val title: String,
    val description: String,
    val coverUrl: String?,
    val startsAt: Instant,
    val endsAt: Instant?,
    val isOnline: Boolean,
    val locationName: String,
    val meetingUrl: String,
    val membersOnly: Boolean,
    val capacity: Int?,
)

sealed interface ClubRequestOutcome {
    data object Submitted : ClubRequestOutcome
    data object AlreadyPending : ClubRequestOutcome
    /** A member may run one club (web migration 20260924193921). */
    data object AlreadyRunsClub : ClubRequestOutcome
    data object Suspended : ClubRequestOutcome
    /** The database's own sentence, e.g. "requires 5 completed exchanges (you have 2)". */
    data class NotEligible(val reason: String) : ClubRequestOutcome
    data object Invalid : ClubRequestOutcome
}

sealed interface CreateEventOutcome {
    data class Created(val eventId: String) : CreateEventOutcome
    data object MonthlyLimitReached : CreateEventOutcome
    data object CapacityTooHigh : CreateEventOutcome
    data object StartsInPast : CreateEventOutcome
    data object NotAllowed : CreateEventOutcome
    data object Suspended : CreateEventOutcome
    data object Invalid : CreateEventOutcome
}

/** Where a cover photo is stored; each bucket lets a member write only `<their id>/…`. */
enum class CoverBucket(val id: String) {
    BOOKS("book-covers"),
    CLUBS("club-covers"),
    EVENTS("event-covers"),
}

/**
 * The limits the database enforces on club requests and events (web migrations
 * `20260718150000` word limits, `20260924134533` characters, links, dates).
 */
object OrganiserRules {
    const val NAME_WORDS = 10
    const val NAME_CHARS = 150
    const val DESCRIPTION_WORDS = 200
    const val DESCRIPTION_CHARS = 2000
    const val SHORT_TEXT_WORDS = 50
    const val SHORT_TEXT_CHARS = 500
    const val AREA_CHARS = 200
    const val EVENT_TITLE_CHARS = 200
    const val EVENT_DESCRIPTION_CHARS = 2000
    const val VENUE_CHARS = 200

    /** `max_event_capacity`'s default; the database has the final say. */
    const val MAX_CAPACITY = 10

    /** The five minutes' grace the database allows a slow form. */
    const val PAST_GRACE_SECONDS = 5 * 60L

    fun wordCount(text: String): Int = BookNoteRules.wordCount(text)

    /** Covers: a pasted link must be https (an upload always is). */
    fun isHttpsUrl(value: String): Boolean = Regex("^https://\\S+$", RegexOption.IGNORE_CASE).matches(value.trim())

    /** Meeting links: http or https, nothing else. */
    fun isHttpUrl(value: String): Boolean = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(value.trim())
}
