package com.openlibrarykashmir.olk.core.data.model

/**
 * Why a book is being reported. The labels are what is stored: the database
 * accepts exactly these five (`reports_reason_valid`, web migration
 * `20260922174737`), the same list as the website's report modal.
 */
enum class ReportReason(val label: String) {
    /** Added with reporting members directly (web migration 20260926085813). */
    HARASSMENT("Harassment or threats"),
    INAPPROPRIATE("Inappropriate content"),
    SPAM("Spam or fake listing"),
    OFFENSIVE("Offensive language"),
    SUSPICIOUS("Suspicious activity"),
    OTHER("Other"),
    ;

    companion object {
        /** `reports_details_length`. */
        const val DETAILS_MAX = 1000
    }
}

sealed interface ReportOutcome {
    data object Sent : ReportOutcome

    /** One open report per book, or per member, from each reporter: this one is still waiting for review. */
    data object AlreadyReported : ReportOutcome

    data object DailyLimitReached : ReportOutcome

    data object Suspended : ReportOutcome
}
