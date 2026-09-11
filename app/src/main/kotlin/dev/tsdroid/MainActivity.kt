package dev.tsdroid

import android.os.Bundle
import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.viewmodel.ConnectionViewModel
import dev.tsdroid.ui.theme.TsDroidTheme
import dev.tsdroid.ui.screen.AppNavigation
import dev.tsdroid.ui.screen.SplashScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import android.content.Intent
import android.provider.Settings
import dev.tsdroid.service.TsConnectionService

class MainActivity : ComponentActivity() {
    private val connectionViewModel: ConnectionViewModel by viewModels()
    private val TAG = "MainActivity"

    override fun attachBaseContext(newBase: Context) {
        val languageTag = runBlocking(Dispatchers.IO) {
            SettingsStore(newBase).language.first()
        }
        val locale = java.util.Locale.forLanguageTag(languageTag)
        java.util.Locale.setDefault(locale)
        val config = newBase.resources.configuration
        config.setLocale(locale)
        config.setLocales(android.os.LocaleList(locale))
        val updatedContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(updatedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var showSplash by remember { mutableStateOf(true) }

            TsDroidTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (showSplash) {
                        SplashScreen(onReady = { showSplash = false })
                    } else {
                        AppNavigation()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: showing floating window")
        if (!isChangingConfigurations) {
            val enableFloatingWindow = runBlocking(Dispatchers.IO) {
                SettingsStore(this@MainActivity).enableFloatingWindow.first()
            }
            if (enableFloatingWindow) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "Overlay permission not granted, prompting user")
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
                    startActivity(intent)
                } else {
                    connectionViewModel.showFloatingWindow()
                }
            } else {
                Log.d(TAG, "Floating window is disabled in settings")
            }
        }
    }
}
