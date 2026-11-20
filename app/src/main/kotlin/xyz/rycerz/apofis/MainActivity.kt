package xyz.rycerz.apofis

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import xyz.rycerz.apofis.ui.AppNav
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ApofisApp).container
        // The activity's own lifecycle: Apofis has one activity, and no foreground service keeps streams alive.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> container.onStart()
                    Lifecycle.Event.ON_STOP -> container.onStop()
                    else -> Unit
                }
            },
        )
        setContent {
            val prefs = container.preferences
            val mode by prefs.themeMode.collectAsState()
            val dynamicColor by prefs.dynamicColor.collectAsState()
            val pureBlack by prefs.pureBlack.collectAsState()
            val dark = isDarkTheme(mode)
            // System bar icons follow the app theme, which may differ from the system one.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ApofisTheme(mode, dynamicColor, pureBlack) { AppNav(container) }
        }
    }
}
