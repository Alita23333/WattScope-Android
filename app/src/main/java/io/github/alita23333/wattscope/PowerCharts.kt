package io.github.alita23333.wattscope

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Checkbox

@Composable
private fun powerColor(id: String): Color {
    val light = MaterialTheme.colorScheme.background.luminance() > .5f
    val index =
        when (id) {
            "qpt.soc" -> 0
            "battery.power" -> 1
            "qpt.gpu" -> 2
            "qpt.cpu-l" -> 3
            "qpt.cpu-m" -> 4
            else -> 5 + (id.hashCode().toLong().and(0x7fffffff) % 3).toInt()
        }
    val palette =
        if (light)
            listOf(
                0xFF00826B,
                0xFF2672DE,
                0xFFB96511,
                0xFF8351BA,
                0xFFCD4053,
                0xFF377E91,
                0xFFB0388B,
                0xFF737F12,
            )
        else
            listOf(
                0xFF63D5B4,
                0xFF78ACFF,
                0xFFFFB363,
                0xFFC19AFF,
                0xFFFF879A,
                0xFF70D0E6,
                0xFFF19AD7,
                0xFFD0DD71,
            )
    return Color(palette[index])
}

@Composable
fun CpuPowerChart(analysis: Analysis, viewport: Viewport, onCursor: (Double) -> Unit) {
    val lines =
        listOf("qpt.cpu-l", "qpt.cpu-m").mapNotNull { id ->
            analysis.series
                .firstOrNull { it.id == id }
                ?.let { TraceLine(it, Metrics.label(id), powerColor(id)) }
        }
    if (lines.isNotEmpty())
        Panel {
            TraceChart(
                lines.first().series,
                viewport,
                title = "CPU power · W",
                primaryLabel = lines.first().label,
                primaryColor = lines.first().color,
                additional = lines.drop(1),
                onCursor = onCursor,
            )
        }
}

@Composable
fun SelectablePowerChart(
    analysis: Analysis,
    baseId: String,
    viewport: Viewport,
    settings: Settings,
    onCursor: (Double) -> Unit,
) {
    val base = analysis.series.firstOrNull { it.id == baseId } ?: return
    val candidates =
        remember(analysis.series, baseId) {
            analysis.series.filter {
                it.id != baseId &&
                    (it.id == "battery.power" ||
                        (it.id.startsWith("qpt.") && !it.id.endsWith(".energy")))
            }
        }
    val key = "chart.overlays.$baseId"
    var selected by
        remember(settings, baseId) {
            mutableStateOf(settings.text(key).split(',').filter { it.isNotBlank() }.distinct())
        }
    var choosing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(emptyList<String>()) }
    val extras = candidates.filter { it.id in selected }
    val lines =
        (listOf(base) + extras).map { TraceLine(it, Metrics.label(it.id), powerColor(it.id)) }
    Panel {
        TraceChart(
            base,
            viewport,
            primaryLabel = lines.first().label,
            primaryColor = lines.first().color,
            additional = lines.drop(1),
            onCursor = onCursor,
            actions = {
                WTextButton(
                    onClick = {
                        pending = selected
                        choosing = true
                    }
                ) {
                    Text(if (extras.isEmpty()) "添加数据" else "数据 (${lines.size})")
                }
            },
        )
    }
    if (choosing)
        WDialog(
            onDismissRequest = { choosing = false },
            title = { Text("${Metrics.label(baseId)} · 添加数据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(candidates, key = { it.id }) { series ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    series.id in pending,
                                    { on ->
                                        pending =
                                            if (on) (pending + series.id).distinct()
                                            else pending - series.id
                                    },
                                )
                                Text(
                                    "●",
                                    color = powerColor(series.id),
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                                Text(
                                    Metrics.label(series.id),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                WTextButton(
                    onClick = {
                        selected = pending.filter { id -> candidates.any { it.id == id } }
                        settings.set(key, selected.joinToString(","))
                        choosing = false
                    }
                ) {
                    Text("应用")
                }
            },
            dismissButton = { WTextButton(onClick = { choosing = false }) { Text("取消") } },
        )
}
