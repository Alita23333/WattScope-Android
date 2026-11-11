package io.github.alita23333.wattscope

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDark
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLight

/** A transient notification panel; closing it returns to the underlying app. */
class ControlPanelActivity : ComponentActivity() {
    private var closing by mutableStateOf(false)
    private val app
        get() = application as WattApp

    private fun dismissPanel(after: () -> Unit = {}) {
        if (closing) return
        closing = true
        lifecycleScope.launch {
            delay(180)
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            after()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        app.engine.controlPanel(true)
        setContent {
            val revision by app.settings.revision.collectAsStateWithLifecycle()
            val dark = remember(revision) { app.settings.dark }
            val live by app.engine.live.collectAsStateWithLifecycle()
            val background = if (dark) Color(0xFF1C1C1E) else Color(0xFFF9F9FB)
            val foreground = if (dark) Color(0xFFF3F3F3) else Color(0xFF171719)
            val secondary = if (dark) Color(0xFFA5A5AB) else Color(0xFF73737B)
            val colors = if (dark) miuixDark() else miuixLight()
            val opacity by
                animateFloatAsState(if (closing) 0f else 1f, tween(180), label = "panelDismiss")
            BackHandler { dismissPanel() }
            MiuixTheme(colors = colors) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { alpha = opacity }
                        .background(Color.Black.copy(alpha = .25f))
                        .clickable(remember { MutableInteractionSource() }, indication = null) {
                            dismissPanel()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.padding(24.dp)
                            .widthIn(max = 360.dp)
                            .fillMaxWidth()
                            .background(background, RoundedCornerShape(28.dp))
                            .clickable(
                                remember { MutableInteractionSource() },
                                indication = null,
                            ) {}
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            "悬浮窗",
                            color = foreground,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val state =
                            when (live.recording) {
                                "recording" -> "记录中"
                                "auto_paused" -> "临时暂停"
                                "manual_paused" -> "手动暂停"
                                "armed" -> "等待进入应用"
                                "preparing" -> "准备中"
                                "saving" -> "保存中"
                                else -> "实时监控"
                            }
                        Text(
                            live.recordedApp
                                .ifBlank { live.currentApp }
                                .takeIf { it.isNotBlank() }
                                ?.let { "$it · $state" } ?: state,
                            color = secondary,
                            fontSize = 13.sp,
                        )
                        if (live.sessionId != null) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    readableTime(live.effectiveNs),
                                    Modifier.weight(1f),
                                    color = secondary,
                                    fontSize = 13.sp,
                                )
                                if (
                                    live.recording in
                                        listOf("recording", "auto_paused", "manual_paused")
                                ) {
                                    Text(
                                        if (live.recording == "manual_paused") "继续" else "暂停",
                                        color = Color(0xFF3482FF),
                                        fontSize = 14.sp,
                                        modifier =
                                            Modifier.clickable {
                                                    send(
                                                        this@ControlPanelActivity,
                                                        if (live.recording == "manual_paused")
                                                            "resume"
                                                        else "pause",
                                                    )
                                                }
                                                .padding(10.dp),
                                    )
                                    Text(
                                        "标记",
                                        color = Color(0xFF3482FF),
                                        fontSize = 14.sp,
                                        modifier =
                                            Modifier.clickable {
                                                    send(this@ControlPanelActivity, "mark")
                                                }
                                                .padding(10.dp),
                                    )
                                }
                            }
                        }
                        @Composable
                        fun windowRow(title: String, checked: Boolean, action: String) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, color = foreground, fontSize = 17.sp)
                                }
                                Spacer(Modifier.width(16.dp))
                                Switch(
                                    checked,
                                    {
                                        if (closing) return@Switch
                                        if (
                                            AndroidSettings.canDrawOverlays(
                                                this@ControlPanelActivity
                                            )
                                        )
                                            send(this@ControlPanelActivity, action)
                                        else {
                                            dismissPanel {
                                                startActivity(
                                                    Intent(
                                                        AndroidSettings
                                                            .ACTION_MANAGE_OVERLAY_PERMISSION,
                                                        Uri.parse("package:$packageName"),
                                                    )
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        windowRow("记录小窗", live.small, "small")
                        windowRow("OSD 面板", live.osd, "osd")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "打开应用",
                                color = Color(0xFF3482FF),
                                fontSize = 14.sp,
                                modifier =
                                    Modifier.clickable {
                                            dismissPanel {
                                                startActivity(
                                                    Intent(
                                                            this@ControlPanelActivity,
                                                            MainActivity::class.java,
                                                        )
                                                        .addFlags(
                                                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                                        )
                                                )
                                            }
                                        }
                                        .padding(vertical = 10.dp),
                            )
                            Text(
                                if (live.recording != "idle") "结束并退出" else "退出监控",
                                color =
                                    if (live.recording != "idle") Color(0xFFE56C6C) else secondary,
                                fontSize = 14.sp,
                                modifier =
                                    Modifier.clickable {
                                            send(this@ControlPanelActivity, "exit")
                                            dismissPanel()
                                        }
                                        .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        app.engine.controlPanel(false)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        app.engine.controlPanel(true)
    }
}
