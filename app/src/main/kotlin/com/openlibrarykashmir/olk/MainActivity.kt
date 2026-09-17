package com.openlibrarykashmir.olk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlibrarykashmir.olk.core.data.session.AuthState
import com.openlibrarykashmir.olk.core.designsystem.theme.OlkTheme
import com.openlibrarykashmir.olk.navigation.OlkNavHost
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Hold the splash until we know whether there is a stored session, so a
        // returning user never sees the login screen flash past.
        splashScreen.setKeepOnScreenCondition {
            viewModel.authState.value is AuthState.Loading
        }

        enableEdgeToEdge()

        setContent {
            val authState by viewModel.authState.collectAsStateWithLifecycle()
            val featureFlags by viewModel.featureFlags.collectAsStateWithLifecycle()

            // An admin can flip a feature off while the app sits in the
            // background, so re-read on every return to the foreground.
            LifecycleStartEffect(Unit) {
                viewModel.refreshFeatureFlags()
                onStopOrDispose {}
            }

            OlkTheme {
                OlkNavHost(
                    isSignedIn = authState is AuthState.SignedIn,
                    featureFlags = featureFlags,
                )
            }
        }
    }
}
