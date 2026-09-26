package com.openlibrarykashmir.olk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import com.openlibrarykashmir.olk.R

/** The website's cream, behind the badge: its inside is transparent and its lettering dark. */
private val LogoBackground = Color(0xFFFBF6EC)

/**
 * The OLK logo, the website's `public/olk-logo.svg` rendered once to
 * `drawable-nodpi/olk_logo.webp`. With [onClick] it opens the logo page, as the
 * website's navbar logo does.
 */
@Composable
fun OlkLogo(size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val base = modifier.size(size).clip(CircleShape).background(LogoBackground)
    Image(
        painter = painterResource(R.drawable.olk_logo),
        contentDescription = if (onClick != null) "OLK logo, about the logo" else "OLK logo",
        modifier = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base,
    )
}
