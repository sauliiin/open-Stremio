package com.mdblisthub.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mdblisthub.tv.core.ui.theme.HubTheme
import com.mdblisthub.tv.navigation.HubNavHost

/**
 * The single activity.
 *
 * A television app has no window management to speak of and one back stack, so
 * the whole interface is one activity and a Compose graph — which also means
 * the player never has to hand state across an activity boundary.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val graph = (application as HubApplication).graph

        setContent {
            HubTheme {
                HubNavHost(graph = graph)
            }
        }
    }
}
