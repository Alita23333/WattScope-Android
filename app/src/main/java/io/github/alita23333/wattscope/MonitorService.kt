package io.github.alita23333.wattscope

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.FileDescriptor
import java.io.PrintWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import org.json.JSONObject

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app
        get() = application as WattApp

    private var overlays: Overlays? = null
    private var lastNotice = ""

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                    "monitor_controls",
                    "监控快捷控制",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
        )
        startForeground(7, notification())
        overlays = Overlays(this, app.engine, app.settings)
        scope.launch {
            combine(app.engine.live, app.settings.revision) { s, r -> s to r }
                .collect { (s, _) ->
                    overlays?.render(s)
                    val key =
                        "${s.small}:${s.osd}:${s.recording}:${s.sessionId}:${s.recordedApp}:${s.currentApp}"
                    if (key != lastNotice) {
                        lastNotice = key
                        nm.notify(7, notification())
                    }
                }
        }
    }

    private fun notification(): Notification {
        val s = app.engine.live.value
        val title = s.recordedApp.ifBlank { if (s.currentRecordable) s.currentApp else "自动识别前台应用" }
        val seconds = s.effectiveNs / 1_000_000_000L
        val status =
            when (s.recording) {
                "armed" -> "等待进入应用"
                "recording" -> "记录中"
                "auto_paused" -> "自动暂停"
                "manual_paused" -> "手动暂停"
                "preparing" -> "准备中"
                "saving" -> "保存中"
                else -> "实时监控"
            }
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, ControlPanelActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, "monitor_controls")
            .setSmallIcon(R.drawable.ic_watt)
            .setContentTitle("WattScope · $title")
            .setContentText(
                if (s.recording == "recording") "记录中 · 点击控制悬浮窗"
                else "$status · ${seconds/60}分${seconds%60}秒"
            )
            .setSubText("点击打开悬浮窗控制")
            .setWhen(System.currentTimeMillis() - s.effectiveNs / 1_000_000L)
            .setUsesChronometer(s.recording == "recording")
            .setShowWhen(s.sessionId != null)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if ((BuildConfig.DEBUG || BuildConfig.VALIDATION) && intent?.action == "validate_record") {
            val id = intent.getStringExtra("record_id")
            if (id != null && id.matches(Regex("[A-Fa-f0-9-]{36}")))
                app.engine.scope.launch {
                    val report = ValidationChecks.record(app, id)
                    app.engine.notifyMessage("记录校验完成：${report.name}")
                }
            return START_NOT_STICKY
        }
        if ((BuildConfig.DEBUG || BuildConfig.VALIDATION) && intent?.action == "benchmark") {
            val mode = intent.getStringExtra("mode") ?: "baseline"
            intent
                .getIntExtra("interval", 500)
                .takeIf { it in listOf(100, 200, 500, 1000) }
                ?.let { app.settings.interval = it }
            app.settings.osdTemplate = if (mode == "osd-text") "text" else "gauges"
            if (mode == "baseline")
                scope.launch {
                    app.engine.exit()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            else if (mode in listOf("recording", "small", "osd-gauge", "osd-text", "both")) {
                app.engine.small(mode == "small" || mode == "both")
                app.engine.osd(mode in listOf("osd-gauge", "osd-text", "both"))
                app.engine.startRecording()
            }
            return START_NOT_STICKY
        }
        when (intent?.action) {
            "small" -> app.engine.small(!app.engine.live.value.small)
            "osd" -> app.engine.osd(!app.engine.live.value.osd)
            "record" -> app.engine.toggleRecord()
            "record_start" -> app.engine.startRecording()
            "record_stop" -> app.engine.stopRecording()
            "preview" -> app.engine.preview(intent.getBooleanExtra("enabled", true))
            "monitoring" -> app.engine.monitoring(intent.getBooleanExtra("enabled", true))
            "pause" -> app.engine.pause()
            "resume" -> app.engine.resume()
            "mark" -> app.engine.mark()
            "refresh" -> app.engine.configurationChanged()
            "exit" ->
                scope.launch {
                    app.engine.exit()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        val s = app.engine.live.value
        writer.println(
            JSONObject()
                .put("collector", s.collector)
                .put("root", s.root)
                .put("preview", s.preview)
                .put("captureRequested", s.captureRequested)
                .put("small", s.small)
                .put("osd", s.osd)
                .put("recording", s.recording)
                .put("sessionId", s.sessionId)
                .put("effectiveNs", s.effectiveNs)
                .put("fps", Metrics.format("fps", s.fps))
                .put("latestNs", s.latest?.ns)
                .put("foreground", s.latest?.foreground)
                .put("currentPackage", s.currentPackage)
                .put("currentApp", s.currentApp)
                .put("recordedApp", s.recordedApp)
                .put("message", s.message)
                .put("events", org.json.JSONArray(s.events.takeLast(15).map { it.toString() }))
                .toString()
        )
    }

    override fun onDestroy() {
        overlays?.close()
        scope.cancel()
        super.onDestroy()
    }
}
