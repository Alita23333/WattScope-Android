package io.github.alita23333.wattscope

import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alita23333.wattscope.WDropdownMenu as DropdownMenu
import io.github.alita23333.wattscope.WDropdownMenuItem as DropdownMenuItem
import kotlin.math.*
import top.yukonga.miuix.kmp.basic.Switch as MiSwitch

@Composable
fun MiuixMonitorPage(app: WattApp, s: LiveState) {
    val context = LocalContext.current
    val compact = remember(LocalSettingsRevision.current) { app.settings.bool("compact", false) }
    val gap = if (compact) 10.dp else 16.dp
    val active = s.recording != "idle"
    var samplingMenu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val values = if (s.collector) s.latest?.values.orEmpty() else emptyMap()
    fun overlay(action: String) {
        if (!SystemSettings.canDrawOverlays(context))
            context.startActivity(
                Intent(
                    SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        else send(context, action)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(6.dp)
                        .background(
                            if (s.collector) Color(0xFF30C96B) else Color.Gray,
                            RoundedCornerShape(3.dp),
                        )
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    if (s.collector) "Root 已连接" else s.root,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "实时监控",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                MiSwitch(
                    checked = s.captureRequested,
                    onCheckedChange = { send(context, "monitoring", it) },
                    enabled = !active,
                )
            }
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            android.os.Build.MODEL,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Android ${android.os.Build.VERSION.RELEASE}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Box {
                        Row(
                            Modifier.clickable(enabled = !active) { samplingMenu = true }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${app.settings.interval} ms",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Icon(Icons.Outlined.KeyboardArrowDown, "采样间隔", Modifier.size(20.dp))
                        }
                        DropdownMenu(samplingMenu, { samplingMenu = false }) {
                            listOf(100, 200, 500, 1000).forEach { ms ->
                                DropdownMenuItem(
                                    text = { Text("$ms ms") },
                                    onClick = {
                                        app.settings.interval = ms
                                        app.engine.configurationChanged()
                                        samplingMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val charging = s.latest?.charging != "Discharging"
                    HeroNumber(
                        Metrics.format(
                            "battery.power",
                            if (s.collector) BatteryPower.display(s.latest) else null,
                        ),
                        if (charging) "Battery net power · W" else "Battery power · W",
                        Modifier.weight(1f),
                        compact,
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        MiniNumber(
                            "CPU power",
                            Metrics.format("qpt.cpu", Metrics.cpuPower(s.metrics, values)) + " W",
                        )
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniNumber("SoC power", Metrics.format("qpt.soc", values["qpt.soc"]) + " W")
                    MiniNumber("GPU power", Metrics.format("qpt.gpu", values["qpt.gpu"]) + " W")
                    MiniNumber(
                        "Battery temp",
                        Metrics.format("temp.battery", values["temp.battery"]) + " °C",
                    )
                }
            }
        }
        item {
            Panel {
                Text(
                    "功耗趋势",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                LivePowerTrend(app, s, compact)
            }
        }
        item {
            Panel {
                Text(
                    "功耗分项",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val rails =
                    s.metrics.filter {
                        it.id.startsWith("qpt.") && !it.id.endsWith(".energy") && it.id != "qpt.soc"
                    }
                rails.forEachIndexed { index, m ->
                    val v = values[m.id]
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = if (compact) 20.dp else 27.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            Metrics.label(m.id).removeSuffix(" power"),
                            Modifier.width(76.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val ratio =
                            if (v != null && values["qpt.soc"] != null && values["qpt.soc"]!! > 0)
                                (v / values["qpt.soc"]!!).coerceIn(0.0, 1.0).toFloat()
                            else 0f
                        Box(
                            Modifier.weight(1f)
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f),
                                    RoundedCornerShape(2.dp),
                                )
                        ) {
                            Box(
                                Modifier.fillMaxWidth(ratio)
                                    .fillMaxHeight()
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(2.dp),
                                    )
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "${Metrics.format(m.id,v)} W",
                            Modifier.width(64.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (index < rails.lastIndex)
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .22f)
                        )
                }
            }
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniNumber("CPU usage", Metrics.format("cpu.usage", values["cpu.usage"]) + "%")
                    MiniNumber("GPU usage", Metrics.format("gpu.usage", values["gpu.usage"]) + "%")
                    MiniNumber(
                        "RAM usage",
                        Metrics.format("ram.percent", values["ram.percent"]) + "%",
                    )
                    MiniNumber("DDR freq", Metrics.format("ddr.freq", values["ddr.freq"]) + " MHz")
                }
                WTextButton(onClick = { details = !details }) {
                    Text(
                        if (details) "收起详细指标" else "CPU 簇与详细指标",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Icon(
                        if (details) Icons.Outlined.KeyboardArrowUp
                        else Icons.Outlined.KeyboardArrowDown,
                        null,
                        Modifier.size(18.dp),
                    )
                }
                if (details) {
                    CpuLiveRows(s.metrics, values)
                    val ids =
                        (values.keys + s.health.keys)
                            .distinct()
                            .filterNot { it.endsWith(".energy") || it.startsWith("cpu.") }
                            .sorted()
                    for (id in ids) {
                        val h = s.health[id]
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(Metrics.label(id), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "${Metrics.format(id,if(id == "battery.power" && s.collector) BatteryPower.display(s.latest) else values[id])} ${Metrics.unit(id)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (values[id] == null && h != null && h.state != "正常")
                            Text(
                                h.state,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                }
            }
        }
        item {
            Panel {
                if (compact) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "记录小窗",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        MiSwitch(s.small, { overlay("small") })
                        Spacer(Modifier.width(18.dp))
                        Text(
                            "OSD",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        MiSwitch(s.osd, { overlay("osd") })
                    }
                } else {
                    ToggleRow("记录小窗", s.small) { overlay("small") }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f)
                    )
                    ToggleRow("OSD 面板", s.osd) { overlay("osd") }
                }
                WButton(
                    onClick = { send(context, "record") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = s.recording !in listOf("preparing", "saving"),
                ) {
                    Icon(
                        if (active) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow,
                        null,
                        Modifier.size(21.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (s.recording == "armed") "取消等待" else if (active) "结束并保存" else "开始记录",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (active) {
                    if (s.recordedApp.isNotBlank())
                        Text(s.recordedApp, style = MaterialTheme.typography.titleSmall)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${when(s.recording){"armed"->"等待进入应用"
"recording"->"记录中"
"auto_paused"->"自动暂停"
"manual_paused"->"手动暂停"
"saving"->"保存中"
else->"准备中"}} · ${readableTime(s.effectiveNs)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (s.recording in listOf("recording", "manual_paused", "auto_paused")) {
                            WTextButton(
                                onClick = {
                                    send(
                                        context,
                                        if (s.recording == "manual_paused") "resume" else "pause",
                                    )
                                }
                            ) {
                                Text(if (s.recording == "manual_paused") "继续" else "暂停")
                            }
                            WTextButton(onClick = { send(context, "mark") }) { Text("标记") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroNumber(value: String, label: String, modifier: Modifier, compact: Boolean) {
    Column(modifier) {
        Text(
            value,
            fontSize = if (compact) 42.sp else 48.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MiniNumber(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LivePowerTrend(app: WattApp, s: LiveState, compact: Boolean) {
    val points = remember(s.latest?.ns) { app.engine.history() }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = Color(0xFF00C8D6)
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    val last = points.lastOrNull()?.ns ?: 0L
    val first = max(points.firstOrNull()?.ns ?: 0L, last - 60_000_000_000L)
    val visible = points.filter { it.ns >= first }
    val values = visible.flatMap { listOfNotNull(it.battery, it.soc) }
    val low = min(0.0, values.minOrNull() ?: 0.0)
    val high = max(1.0, (values.maxOrNull() ?: 1.0) * 1.15)
    Canvas(Modifier.fillMaxWidth().height(if (compact) 124.dp else 158.dp)) {
        val x0 = 30.dp.toPx()
        val x1 = size.width - 8.dp.toPx()
        val y0 = 8.dp.toPx()
        val y1 = size.height - 22.dp.toPx()
        val text =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    android.graphics.Color.argb(
                        180,
                        (foreground.red * 255).toInt(),
                        (foreground.green * 255).toInt(),
                        (foreground.blue * 255).toInt(),
                    )
                textSize = 9.dp.toPx()
            }
        for (i in 0..4) {
            val y = y0 + (y1 - y0) * i / 4
            drawLine(foreground.copy(alpha = .13f), Offset(x0, y), Offset(x1, y))
            drawContext.canvas.nativeCanvas.drawText(
                "%.1f".format(high - (high - low) * i / 4),
                0f,
                y + 3.dp.toPx(),
                text,
            )
        }
        val span = max(1.0, (last - first) / 1e9)
        for (i in 0..3) {
            val x = x0 + (x1 - x0) * i / 3
            drawContext.canvas.nativeCanvas.drawText(
                "${(span*i/3).toInt()}s",
                x - 5.dp.toPx(),
                size.height - 4.dp.toPx(),
                text,
            )
        }
        fun curve(selector: (Engine.TrendPoint) -> Double?, color: Color) {
            val path = Path()
            var linked = false
            var previous = 0L
            for (p in visible) {
                val v = selector(p)
                if (v == null) {
                    linked = false
                    continue
                }
                val x = x0 + ((p.ns - first) / 1e9 / span * (x1 - x0)).toFloat()
                val y = y1 - ((v - low) / (high - low) * (y1 - y0)).toFloat()
                if (!linked || p.ns - previous > app.settings.interval * 2_600_000L)
                    path.moveTo(x, y)
                else path.lineTo(x, y)
                previous = p.ns
                linked = true
            }
            drawPath(path, color, style = Stroke(.8.dp.toPx()))
        }
        curve({ it.battery }, primary)
        curve({ it.soc }, secondary)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
            if (s.latest?.charging == "Discharging") "— Battery power" else "— Battery net power",
            color = primary,
            style = MaterialTheme.typography.labelSmall,
        )
        Text("— SoC power", color = secondary, style = MaterialTheme.typography.labelSmall)
    }
}
