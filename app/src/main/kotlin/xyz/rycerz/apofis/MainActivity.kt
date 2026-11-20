package xyz.rycerz.apofis

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
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
        // Each Activity adds its own callback (removed with it) and enables it from the container's state, so Back
        // cancels a connect check before any frame and after rotation. Enabled at once from the current value.
        val connectBack = container.connectBack
        val back = object : OnBackPressedCallback(connectBack.busy.value) {
            override fun handleOnBackPressed() {
                if (connectBack.cancel()) moveTaskToBack(true) // while saving: consumed, nothing happens
            }
        }
        onBackPressedDispatcher.addCallback(this, back)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { connectBack.busy.collect { back.isEnabled = it } }
        }
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
            ApofisTheme(mode, dynamicColor, pureBlack) {
                // Test tags become resource ids, so the end-to-end tests find chat items by their opaque keys.
                Box(Modifier.semantics { testTagsAsResourceId = true }) { AppNav(container) }
            }
        }
    }
}
