package com.openlibrarykashmir.olk.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.openlibrarykashmir.olk.BuildConfig

/** The website's policy page. Play requires it to be reachable from inside the app. */
val PRIVACY_POLICY_URL = "${BuildConfig.WEBSITE_URL}/privacy"

@Composable
fun PrivacyPolicyLink(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    TextButton(
        // A phone with no browser at all has nowhere to open it; nothing useful to say then.
        onClick = { runCatching { uriHandler.openUri(PRIVACY_POLICY_URL) } },
        modifier = modifier,
    ) {
        Text("Privacy policy")
    }
}
