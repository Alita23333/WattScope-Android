package io.github.alita23333.wattscope

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { WattScope(application as WattApp) }
        debugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        debugIntent(intent)
    }

    override fun onStop() {
        (application as WattApp).engine.preview(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        (application as WattApp).engine.mainAppOpened()
    }

    private fun debugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val action = intent.getStringExtra("testAction") ?: return
        intent.removeExtra("testAction")
        val app = application as WattApp
        intent.getStringExtra("game")?.let {
            app.settings.game = it
            app.settings.gameName = intent.getStringExtra("gameName") ?: it
        }
        intent
            .getIntExtra("interval", 0)
            .takeIf { it in listOf(100, 200, 500, 1000) }
            ?.let { app.settings.interval = it }
        if (action == "plain") return
        // Debug-only deterministic setup for independent power trials. Production builds ignore it.
        if (action == "benchmark") {
            val mode = intent.getStringExtra("mode") ?: return
            if (mode !in listOf("baseline", "recording", "small", "osd-gauge", "osd-text", "both"))
                return
            app.settings.osdTemplate = if (mode == "osd-text") "text" else "gauges"
            if (mode == "baseline") {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, MonitorService::class.java).setAction("exit"),
                )
            } else {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, MonitorService::class.java).setAction("record_start"),
                )
                app.engine.small(mode == "small" || mode == "both")
                app.engine.osd(mode in listOf("osd-gauge", "osd-text", "both"))
            }
            return
        }
        val serviceAction =
            when (action) {
                "start" -> "record_start"
                "stop" -> "record_stop"
                "osd" -> "osd"
                "small" -> "small"
                "exit" -> "exit"
                else -> "preview"
            }
        ContextCompat.startForegroundService(
            this,
            Intent(this, MonitorService::class.java).setAction(serviceAction),
        )
    }
}
