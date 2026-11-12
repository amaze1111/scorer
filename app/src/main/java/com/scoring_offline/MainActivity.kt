package com.scoring_offline

import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.scoring_offline.phone.PhoneApp
import com.scoring_offline.tv.TvApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // targetSdk 35+ forces edge-to-edge on Android 15+. Enable it on every version so the
        // layout is the same everywhere. All screens are dark, so the bar icons stay light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        // Screen stays on while this activity is visible. No permission, no WakeLock needed.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ui = getSystemService(UI_MODE_SERVICE) as UiModeManager
        val isTv = ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        setContent {
            ScoreboardTheme {
                if (isTv) TvApp() else PhoneApp()
            }
        }
    }
}
