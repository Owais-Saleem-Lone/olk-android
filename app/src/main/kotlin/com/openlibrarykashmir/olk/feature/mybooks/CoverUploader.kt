package com.openlibrarykashmir.olk.feature.mybooks

import android.content.Context
import android.net.Uri
import com.openlibrarykashmir.olk.core.data.model.CoverBucket
import com.openlibrarykashmir.olk.core.data.repository.ClubOrganiserRepository
import com.openlibrarykashmir.olk.core.data.repository.MyBooksRepository

/**
 * Compresses a picked photo and uploads it as a cover, returning its public URL.
 * An interface so view models stay free of `Context` and can be tested without a
 * device.
 */
fun interface CoverUploader {
    /** Throws [IllegalArgumentException] when the file is not a usable image. */
    suspend fun upload(ownerId: String, photo: Uri): String
}

class DeviceCoverUploader(
    private val context: Context,
    private val repository: MyBooksRepository,
) : CoverUploader {
    override suspend fun upload(ownerId: String, photo: Uri): String =
        try {
            repository.uploadCover(ownerId, CoverImage.compress(context, photo))
        } finally {
            CoverImage.clearCaptures(context)
        }
}

/**
 * The same compression as a book cover, uploaded to a club's or an event's
 * cover bucket instead (each lets a member write only their own folder).
 */
class DeviceOrganiserCoverUploader(
    private val context: Context,
    private val repository: ClubOrganiserRepository,
    private val bucket: CoverBucket,
) : CoverUploader {
    override suspend fun upload(ownerId: String, photo: Uri): String =
        try {
            repository.uploadCover(bucket, ownerId, CoverImage.compress(context, photo))
        } finally {
            CoverImage.clearCaptures(context)
        }
}

/** The public URL a [CoverChoice] resolves to on save, uploading a new photo if needed. */
suspend fun CoverChoice.resolve(ownerId: String, uploader: CoverUploader): String? = when (this) {
    is CoverChoice.Current -> url
    is CoverChoice.Picked -> uploader.upload(ownerId, uri)
    CoverChoice.Removed -> null
}

internal fun Throwable.toCoverAwareMessage(): String {
    val raw = message.orEmpty().lowercase()
    return when {
        this is IllegalArgumentException -> "That photo couldn't be used. Try a different one."
        "network" in raw || "unable to resolve host" in raw || "timeout" in raw ->
            "No connection. Check your network and try again."
        else -> "Something went wrong. Please try again."
    }
}
