package com.openlibrarykashmir.olk.feature.logo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.openlibrarykashmir.olk.BuildConfig
import com.openlibrarykashmir.olk.ui.OlkLogo

/**
 * Opened by tapping the OLK logo: the logo large, who designed it, and for the
 * curious a way to the website's About page (vision and team), which is kept
 * there rather than copied into the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogoScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("The OLK logo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            OlkLogo(size = 280.dp)
            OutlinedButton(onClick = { runCatching { uriHandler.openUri(ABOUT_URL) } }) {
                Text("Our vision and team")
            }
            Text(
                text = buildAnnotatedString {
                    append("Logo designed by ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Owais Saleem") }
                    append(" and ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Mahi Qadri") }
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // The website's page also offers the SVG to download.
            TextButton(onClick = { runCatching { uriHandler.openUri("${BuildConfig.WEBSITE_URL}/logo") } }) {
                Text("See it on the website")
            }
        }
    }
}

/** The website's About page: vision, what the platform is for, and the team. */
private val ABOUT_URL = "${BuildConfig.WEBSITE_URL}/about"
