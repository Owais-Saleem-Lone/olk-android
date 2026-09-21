package com.openlibrarykashmir.olk.feature.team

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openlibrarykashmir.olk.core.data.repository.ProfileRepository
import com.openlibrarykashmir.olk.core.data.repository.TeamApplication
import com.openlibrarykashmir.olk.core.data.repository.TeamApplicationOutcome
import com.openlibrarykashmir.olk.core.data.repository.TeamApplicationRepository
import com.openlibrarykashmir.olk.core.data.repository.TeamApplicationRules
import com.openlibrarykashmir.olk.core.data.session.AuthRepository
import com.openlibrarykashmir.olk.core.data.session.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A CV the user picked, read into memory for the upload. */
class PickedCv(val fileName: String, val mimeType: String, val bytes: ByteArray)

/** Reads a picked document; a seam so the form can be tested without a device. */
fun interface CvReader {
    /** Null when the file cannot be read at all. */
    suspend fun read(uri: String): PickedCv?
}

/** Reads through the system's document picker grant: no storage permission needed. */
class DeviceCvReader(private val context: Context) : CvReader {
    override suspend fun read(uri: String): PickedCv? = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = Uri.parse(uri)
            val resolver = context.contentResolver
            val name = resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: "cv"
            val mime = resolver.getType(parsed) ?: "application/octet-stream"
            // Read one byte past the limit, so an oversized file is recognised
            // without pulling all of it into memory.
            val bytes = resolver.openInputStream(parsed)?.use { input ->
                input.readNBytesCompat(TeamApplicationRules.CV_MAX_BYTES + 1)
            } ?: return@runCatching null
            PickedCv(name, mime, bytes)
        }.getOrNull()
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

data class JoinTeamUiState(
    val fullName: String = "",
    val age: String = "",
    val email: String = "",
    val phone: String = "",
    val profession: String = "",
    val studies: String = "",
    val field: String = "",
    val motivation: String = "",
    val contribution: String = "",
    val cvName: String? = null,
    val cvError: String? = null,
    val isSubmitting: Boolean = false,
    val submitted: Boolean = false,
    val error: String? = null,
) {
    val motivationWords: Int get() = TeamApplicationRules.wordCount(motivation)
    val contributionWords: Int get() = TeamApplicationRules.wordCount(contribution)
}

class JoinTeamViewModel(
    private val applications: TeamApplicationRepository,
    private val profiles: ProfileRepository,
    private val auth: AuthRepository,
    private val cvReader: CvReader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JoinTeamUiState())
    val uiState: StateFlow<JoinTeamUiState> = _uiState.asStateFlow()

    private var cv: PickedCv? = null

    init {
        // Prefilled like the website's form: the account's name and email.
        viewModelScope.launch {
            val email = withTimeoutOrNull(PREFILL_TIMEOUT_MS) {
                (auth.authState.first { it is AuthState.SignedIn } as AuthState.SignedIn).email
            }
            val name = runCatching { profiles.myProfile()?.displayName }.getOrNull()
            _uiState.update {
                it.copy(
                    fullName = it.fullName.ifEmpty { name.orEmpty() },
                    email = it.email.ifEmpty { email.orEmpty() },
                )
            }
        }
    }

    fun update(transform: (JoinTeamUiState) -> JoinTeamUiState) = _uiState.update { transform(it).copy(error = null) }

    fun onCvPicked(uri: String) {
        viewModelScope.launch {
            val picked = cvReader.read(uri)
            val problem = if (picked == null) "Could not read that file. Try another." else cvProblem(picked)
            cv = picked.takeIf { problem == null }
            _uiState.update { it.copy(cvName = cv?.fileName, cvError = problem) }
        }
    }

    fun submit() {
        val state = _uiState.value
        if (state.isSubmitting || state.submitted) return

        val problem = formProblem(state) ?: (if (cv == null) "Please attach your CV." else null)
        if (problem != null) {
            _uiState.update { it.copy(error = problem) }
            return
        }
        val file = cv ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            runCatching {
                applications.submit(
                    TeamApplication(
                        fullName = state.fullName,
                        age = state.age.trim().toInt(),
                        email = state.email,
                        phone = state.phone,
                        profession = state.profession,
                        studies = state.studies,
                        field = state.field,
                        motivation = state.motivation,
                        contribution = state.contribution,
                        cvFileName = file.fileName,
                        cvMimeType = uploadMimeType(file.fileName, file.mimeType),
                        cv = file.bytes,
                    ),
                )
            }.onSuccess { outcome ->
                _uiState.update {
                    when (outcome) {
                        TeamApplicationOutcome.Submitted -> it.copy(isSubmitting = false, submitted = true)
                        is TeamApplicationOutcome.Refused -> it.copy(isSubmitting = false, error = outcome.message)
                    }
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                _uiState.update { it.copy(isSubmitting = false, error = "Network error. Please try again.") }
            }
        }
    }

    private companion object {
        const val PREFILL_TIMEOUT_MS = 3_000L
    }
}

private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

/**
 * The website route's checks, in its order and with its wording, so the form
 * can say what is wrong before the upload. The server checks them all again.
 */
internal fun formProblem(state: JoinTeamUiState): String? {
    val max = TeamApplicationRules.MAX_TEXT_LENGTH
    val age = state.age.trim().toIntOrNull()
    return when {
        state.fullName.isBlank() || state.fullName.trim().length > max -> "Please enter a valid full name."
        age == null || age !in TeamApplicationRules.MIN_AGE..TeamApplicationRules.MAX_AGE -> "Please enter a valid age."
        !EMAIL_PATTERN.matches(state.email.trim()) -> "Please enter a valid email address."
        state.profession.isBlank() || state.profession.trim().length > max -> "Please enter your profession."
        state.studies.isBlank() || state.studies.trim().length > max -> "Please enter your studies."
        state.field !in TeamApplicationRules.FIELDS -> "Please select a field."
        state.motivation.isBlank() -> "Please tell us why you want to join."
        state.motivationWords > TeamApplicationRules.MOTIVATION_WORD_LIMIT ->
            "Your \"why join\" answer is over the ${TeamApplicationRules.MOTIVATION_WORD_LIMIT}-word limit."
        state.contribution.isBlank() -> "Please tell us how you would like to contribute."
        state.contributionWords > TeamApplicationRules.CONTRIBUTION_WORD_LIMIT ->
            "Your \"how you'd contribute\" answer is over the ${TeamApplicationRules.CONTRIBUTION_WORD_LIMIT}-word limit."
        else -> null
    }
}

/** Same rule as the website: a .pdf/.doc/.docx name, a matching type if one is given, and at most 4 MB. */
internal fun cvProblem(cv: PickedCv): String? {
    val lowerName = cv.fileName.lowercase()
    val goodName = TeamApplicationRules.CV_EXTENSIONS.any { lowerName.endsWith(it) }
    val goodType = cv.mimeType == "application/octet-stream" || cv.mimeType in TeamApplicationRules.CV_MIME_TYPES
    return when {
        !goodName || !goodType -> "CV must be a PDF or Word document (.pdf, .doc, .docx)."
        cv.bytes.isEmpty() -> "That file is empty."
        cv.bytes.size > TeamApplicationRules.CV_MAX_BYTES -> "CV must be smaller than 4MB."
        else -> null
    }
}

/**
 * The type sent with the file. The website refuses a file whose stated type is
 * not a PDF or Word type, and some pickers only say "application/octet-stream",
 * so the name's extension decides in that case.
 */
internal fun uploadMimeType(fileName: String, mimeType: String): String {
    if (mimeType in TeamApplicationRules.CV_MIME_TYPES) return mimeType
    val lower = fileName.lowercase()
    return when {
        lower.endsWith(".pdf") -> "application/pdf"
        lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        lower.endsWith(".doc") -> "application/msword"
        else -> mimeType
    }
}
