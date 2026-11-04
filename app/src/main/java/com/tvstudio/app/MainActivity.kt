package com.tvstudio.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvstudio.app.ui.ChooseScreen
import com.tvstudio.app.ui.ConnectScreen
import com.tvstudio.app.ui.FinishScreen
import com.tvstudio.app.ui.ReviewScreen
import com.tvstudio.app.ui.RunningScreen
import com.tvstudio.app.ui.SetupScreen

private val Colors = darkColorScheme(
    primary = Color(0xFF7FE3C4),
    onPrimary = Color(0xFF06241B),
    secondary = Color(0xFF9DB7FF),
    background = Color(0xFF14161C),
    surface = Color(0xFF14161C),
    surfaceVariant = Color(0xFF232731),
    error = Color(0xFFFFB4AB),
)

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = Colors) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) {
                        App(model)
                    }
                }
            }
        }
    }

    @Composable
    private fun App(model: AppViewModel) {
        val state by model.state.collectAsStateWithLifecycle()

        // The phone going to sleep mid-run would drop the connection to the TV.
        LaunchedEffect(state.busy != null) {
            if (state.busy != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        BackHandler(enabled = state.screen != Screen.SETUP) { model.back() }

        when (state.screen) {
            Screen.SETUP -> SetupScreen(model)
            Screen.CONNECT -> ConnectScreen(state, model)
            Screen.CHOOSE -> ChooseScreen(state, model)
            Screen.REVIEW -> ReviewScreen(state, model)
            Screen.RUNNING -> RunningScreen(state)
            Screen.FINISH -> FinishScreen(state, model)
        }
    }
}
