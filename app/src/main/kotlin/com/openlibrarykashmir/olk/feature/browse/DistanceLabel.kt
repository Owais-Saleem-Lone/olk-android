package com.openlibrarykashmir.olk.feature.browse

import kotlin.math.roundToInt

/**
 * Same wording as the web's `formatDistance` (src/lib/geo.ts). Locations are stored
 * snapped to ~1 km, so anything finer would be false precision.
 */
fun formatDistance(km: Double?): String? = when {
    km == null -> null
    km < 1 -> "< 1 km"
    km < 10 -> "~${km.roundToInt()} km"
    else -> "~${(km / 5).roundToInt() * 5} km"
}
