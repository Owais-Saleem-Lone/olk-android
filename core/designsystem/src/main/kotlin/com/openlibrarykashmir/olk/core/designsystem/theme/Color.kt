package com.openlibrarykashmir.olk.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand palette, lifted verbatim from the web app's `globals.css` so the phone and
 * the website read as one product. Keep these two in sync — if a token changes
 * there, change it here in the same PR.
 */
object OlkPalette {
    val Teal = Color(0xFF14B8A6)
    val TealDark = Color(0xFF0D9488)
    val TealLight = Color(0xFF2DD4BF)

    val Slate = Color(0xFF0F172A)
    val SlateLight = Color(0xFF1E293B)
    val SlateMuted = Color(0xFF334155)

    val Cream = Color(0xFFFBF6EC)

    val White = Color(0xFFFFFFFF)
    val Ink = Color(0xFF171717)
    val NearBlack = Color(0xFF0A0A0A)
    val OffWhite = Color(0xFFEDEDED)

    /** Error ramp — Material's default red, tuned to sit beside the teal. */
    val Error = Color(0xFFBA1A1A)
    val ErrorDark = Color(0xFFFFB4AB)
}
