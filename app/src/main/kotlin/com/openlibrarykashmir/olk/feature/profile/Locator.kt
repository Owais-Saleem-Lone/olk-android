package com.openlibrarykashmir.olk.feature.profile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.openlibrarykashmir.olk.core.data.repository.SharedLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Why a location could not be found; each maps to its own message. */
sealed interface LocateOutcome {
    data class Found(val location: SharedLocation) : LocateOutcome
    data object PermissionDenied : LocateOutcome
    data object LocationOff : LocateOutcome
    data object Unavailable : LocateOutcome
}

/**
 * One-shot device location. An interface so the view model stays free of
 * `Context` and can be tested without a device.
 */
fun interface Locator {
    suspend fun locate(): LocateOutcome
}

/**
 * Coarse location only, through the platform [LocationManager] — no Google
 * Play services, so it works the same on de-Googled phones and an F-Droid
 * build. "Books near me" needs a neighbourhood, not a house: the website
 * promises exact coordinates are never shown to anyone, and asking only for
 * approximate location means the app never has them to begin with.
 */
class DeviceLocator(private val context: Context) : Locator {

    @SuppressLint("MissingPermission") // Checked right below.
    override suspend fun locate(): LocateOutcome {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return LocateOutcome.PermissionDenied

        val manager = context.getSystemService(LocationManager::class.java) ?: return LocateOutcome.Unavailable
        if (!LocationManagerCompat.isLocationEnabled(manager)) return LocateOutcome.LocationOff

        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            // With only coarse permission, Android 12+ hands out a fuzzed GPS fix.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.GPS_PROVIDER)
        }
        val enabled = manager.getProviders(true)
        for (provider in candidates.filter { it in enabled }) {
            val fix = withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    LocationManagerCompat.getCurrentLocation(
                        manager,
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(context),
                    ) { location -> continuation.resume(location) }
                }
            }
            if (fix != null) return LocateOutcome.Found(SharedLocation(fix.latitude, fix.longitude))
        }
        // Nothing live: a recent last-known fix is still better than nothing.
        val last = enabled.firstNotNullOfOrNull { manager.getLastKnownLocation(it) }
        return last?.let { LocateOutcome.Found(SharedLocation(it.latitude, it.longitude)) } ?: LocateOutcome.Unavailable
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
