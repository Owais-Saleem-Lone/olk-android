package com.openlibrarykashmir.olk.core.data.repository

import com.openlibrarykashmir.olk.core.data.model.CoverBucket
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage

/**
 * The object path inside [bucket] for one of its public URLs, or null for a
 * pasted link or another bucket's file. Uploads are `<userId>/<millis>.webp`.
 */
internal fun coverStoragePath(publicUrl: String?, bucket: CoverBucket): String? {
    val marker = "/storage/v1/object/public/${bucket.id}/"
    val at = publicUrl?.indexOf(marker) ?: return null
    if (at < 0) return null
    return publicUrl.substring(at + marker.length).substringBefore('?').substringBefore('#').ifEmpty { null }
}

/**
 * Deletes a cover nothing points at any more: replaced, removed, or its book or
 * request gone. Storage is the free tier's scarcest resource, and the database
 * caps the files a member may keep per bucket (web migration 20260924193921).
 * Only files in [ownerId]'s own folder: a donated book's cover stays in the
 * donor's. Best effort, as on the website: a leftover file is never a reason to
 * fail what the member just did.
 */
internal suspend fun SupabaseClient.removeOwnCover(bucket: CoverBucket, publicUrl: String?, ownerId: String) {
    val path = coverStoragePath(publicUrl, bucket) ?: return
    if (!path.startsWith("$ownerId/")) return
    runCatching { storage.from(bucket.id).delete(path) }
}
