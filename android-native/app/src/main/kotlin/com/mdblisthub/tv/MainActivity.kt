package com.mdblisthub.tv

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mdblisthub.tv.core.ui.theme.HubTheme
import com.mdblisthub.tv.navigation.HubNavHost
import com.mdblisthub.tv.update.AppUpdateManager
import com.mdblisthub.tv.update.AppUpdateOverlay
import java.util.Locale

/**
 * The single activity.
 *
 * A television app has no window management to speak of and one back stack, so
 * the whole interface is one activity and a Compose graph — which also means
 * the player never has to hand state across an activity boundary.
 */
class MainActivity : ComponentActivity() {

    private lateinit var appUpdateManager: AppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val graph = (application as HubApplication).graph
        appUpdateManager = AppUpdateManager(this, GITHUB_REPOSITORY)

        setContent {
            val language by graph.uiPreferences.language
                .collectAsStateWithLifecycle(initialValue = DEFAULT_LANGUAGE)

            val activity = this
            val localeContext = remember(language) {
                val locale = Locale.forLanguageTag(language)
                val configuration = Configuration(resources.configuration).apply {
                    setLocale(locale)
                }
                activity.createConfigurationContext(configuration)
            }

            // `Locale.setDefault` is a process-wide write and belongs nowhere
            // near a composable body: composition can run more than once for a
            // single state change, and can be discarded entirely. An effect
            // keyed on the language runs it exactly once per real change.
            LaunchedEffect(language) {
                Locale.setDefault(Locale.forLanguageTag(language))
            }

            CompositionLocalProvider(
                LocalContext provides localeContext,
                LocalConfiguration provides localeContext.resources.configuration,
                // The locale-wrapped context above is a bare `ContextImpl` —
                // not an `Activity`, and not even a `ContextWrapper`, so
                // nothing downstream can walk back to the Activity through it.
                // That silently broke Google sign-in, which needs a real
                // Activity to launch the credential picker. Publishing the
                // Activity separately is what keeps both facts available:
                // strings come from the locale context, windows from here.
                LocalHostActivity provides activity,
            ) {
                HubTheme {
                    Box {
                        HubNavHost(graph = graph)
                        AppUpdateOverlay(manager = appUpdateManager)
                    }
                }
            }
        }

        appUpdateManager.checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        if (::appUpdateManager.isInitialized) appUpdateManager.onHostResumed()
    }

    override fun onDestroy() {
        if (::appUpdateManager.isInitialized) appUpdateManager.close()
        super.onDestroy()
    }

    private companion object {
        const val DEFAULT_LANGUAGE = "en"
        const val GITHUB_REPOSITORY = "sauliiin/open-Stremio"
    }
}
