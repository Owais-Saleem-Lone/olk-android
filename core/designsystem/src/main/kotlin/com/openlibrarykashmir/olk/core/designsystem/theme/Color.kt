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

    /**
     * Surface-container ramps. Material 3 paints cards, sheets, menus and nav bars
     * from these five roles; leaving them undefined falls back to Material's stock
     * purple-tinted greys, which is exactly the lavender that showed up on the first
     * device run. Light steps are warm paper tones built out from [Cream]; dark
     * steps are Tailwind's slate scale, the same family the web app uses.
     */
    val PaperLowest = Color(0xFFFFFFFF)
    val PaperLow = Color(0xFFFDFAF4)
    val Paper = Cream
    val PaperHigh = Color(0xFFF7F0E2)
    val PaperHighest = Color(0xFFF2EADA)

    val Slate950 = Color(0xFF020617)
    val SlateDeep = Color(0xFF0A1122)
    val Slate750 = Color(0xFF273449)

    /** Tailwind slate-200/400/500, for outlines and dividers. */
    val Slate200 = Color(0xFFE2E8F0)
    val Slate400 = Color(0xFF94A3B8)
    val Slate500 = Color(0xFF64748B)

    val White = Color(0xFFFFFFFF)
    val Ink = Color(0xFF171717)
    val NearBlack = Color(0xFF0A0A0A)
    val OffWhite = Color(0xFFEDEDED)

    /**
     * The website homepage's accents (Tailwind teal/amber/rose): stats, the
     * "how it works" steps, Lend badges and the teal-to-amber call to action.
     */
    val Teal50 = Color(0xFFF0FDFA)
    val Teal600 = TealDark
    val Amber50 = Color(0xFFFFFBEB)
    val Amber400 = Color(0xFFFBBF24)
    val Amber600 = Color(0xFFD97706)
    val Rose50 = Color(0xFFFFF1F2)
    val Rose400 = Color(0xFFFB7185)
    val Rose500 = Color(0xFFF43F5E)

    /** Error ramp — Material's default red, tuned to sit beside the teal. */
    val Error = Color(0xFFBA1A1A)
    val ErrorDark = Color(0xFFFFB4AB)
}
