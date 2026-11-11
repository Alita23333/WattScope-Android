package io.github.alita23333.wattscope

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox

fun recordDuration(seconds: Double): String = elapsedTime(seconds)

private data class SummaryCell(val label: String, val value: String, val unit: String)

@Composable
fun SummaryPanel(s: Summary) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val cpuPower =
        s.powers["qpt.cpu-m"]?.average?.let { m ->
            s.powers["qpt.cpu-l"]?.average?.let { l -> m + l }
        }
    val cells =
        listOf(
            SummaryCell("Avg FPS", Metrics.precise("fps", s.avgFps), "FPS"),
            SummaryCell("1% Low", Metrics.precise("fps", s.low1), "FPS"),
            SummaryCell("5% Low", Metrics.precise("fps", s.low5), "FPS"),
            SummaryCell("CPU pwr", Metrics.format("qpt.cpu", cpuPower), "W"),
            SummaryCell(
                "Battery power",
                Metrics.format("battery.power", s.powers["battery.power"]?.average),
                "W",
            ),
            SummaryCell("SoC power", Metrics.format("qpt.soc", s.powers["qpt.soc"]?.average), "W"),
            SummaryCell("P95", Metrics.precise("frame.ms", s.p95), "ms"),
            SummaryCell("GPU pwr", Metrics.format("qpt.gpu", s.powers["qpt.gpu"]?.average), "W"),
        )
    Panel {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("测试汇总", style = MaterialTheme.typography.titleMedium)
            Text(
                recordDuration(s.seconds),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BoxWithConstraints {
            val columns = if (maxWidth / LocalDensity.current.fontScale >= 310.dp) 4 else 2
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                cells.chunked(columns).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { cell ->
                            Column(
                                Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    cell.label,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    minLines = 2,
                                    maxLines = 2,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    cell.value,
                                    fontSize = 25.sp,
                                    lineHeight = 34.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    style =
                                        MaterialTheme.typography.headlineSmall.copy(
                                            fontFeatureSettings = "tnum"
                                        ),
                                    maxLines = 1,
                                )
                                Text(
                                    cell.unit,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                    "Slow frames" to s.slow,
                    "Severe frames" to s.severe,
                    "System jank" to s.systemJank,
                )
                .forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            value?.toString() ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${s.frameCount} frames",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WTextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起分项" else "功耗分项", style = MaterialTheme.typography.labelMedium)
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    Modifier.size(16.dp),
                )
            }
        }
        if (expanded) {
            BoxWithConstraints {
                val columns = if (maxWidth / LocalDensity.current.fontScale >= 260.dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    s.powers.entries.chunked(columns).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { (id, power) ->
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (id == "battery.power") "Battery power"
                                        else Metrics.label(id),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    Text(
                                        "${Metrics.format(id, power.average)} W",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        "覆盖 %.1f%% · %s J"
                                            .format(
                                                if (s.seconds > 0)
                                                    power.coverageSeconds / s.seconds * 100
                                                else 0.0,
                                                power.energyJ?.let { "%.1f".format(it) } ?: "—",
                                            ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (row.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (s.systemJankTypes.isNotEmpty()) {
                Text("System jank", style = MaterialTheme.typography.labelMedium)
                s.systemJankTypes.toSortedMap().forEach { (mask, count) ->
                    Text(
                        "${Metrics.jankLabel(mask)} · $count 帧",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordEntry(
    session: SessionEntity,
    summary: Summary?,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val settings = (context.applicationContext as WattApp).settings
    val compact = remember(LocalSettingsRevision.current) { settings.bool("compact", false) }
    val finished = session.status in listOf("complete", "imported", "interrupted")
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    val today = day.format(Date(session.startWall)) == day.format(Date())
    val battery =
        summary?.powers?.get("battery.power")?.takeIf {
            it.coverageSeconds > 0 && it.average != null
        }
    val power = battery ?: summary?.powers?.get("qpt.soc")
    val powerSource = if (battery != null) "Discharge" else "SoC"
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppIcon(context, session.packageName, Modifier.size(if (compact) 42.dp else 46.dp))
        Card(
            modifier =
                Modifier.weight(1f)
                    .combinedClickable(
                        onClick = if (selecting) onSelect else onOpen,
                        onLongClick = onSelect,
                    ),
            color =
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                else
                    MaterialTheme.colorScheme.surface.copy(
                        alpha = settings.int("card.opacity", 94) / 100f
                    ),
            cornerRadius = 20.dp,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = if (compact) 6.dp else 9.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (today) {
                        Text(
                            "今天",
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = .1f))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        session.title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (session.favorite)
                        Icon(
                            Icons.Outlined.Star,
                            "已收藏",
                            Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    if (selecting) Checkbox(selected, { onSelect() })
                    else
                        Box {
                            WIconButton(
                                onClick = { menu = true },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Outlined.MoreHoriz, "记录操作", Modifier.size(20.dp))
                            }
                            WDropdownMenu(menu, { menu = false }) {
                                WDropdownMenuItem(
                                    text = { Text(if (session.favorite) "取消收藏" else "收藏") },
                                    onClick = {
                                        menu = false
                                        onFavorite()
                                    },
                                )
                                WDropdownMenuItem(
                                    text = { Text("加入对比") },
                                    onClick = {
                                        menu = false
                                        onSelect()
                                    },
                                )
                                WDropdownMenuItem(
                                    text = { Text("删除") },
                                    onClick = {
                                        menu = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                }
                BoxWithConstraints {
                    val narrow = maxWidth / LocalDensity.current.fontScale < 245.dp
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                SimpleDateFormat(
                                        if (today) "HH:mm:ss" else "MM-dd HH:mm",
                                        Locale.CHINA,
                                    )
                                    .format(Date(session.startWall)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!narrow) RecordNumbers(summary, power?.average, powerSource)
                            Text(
                                summary?.let { recordDuration(it.seconds) }
                                    ?: if (finished) "—" else recordStatusLabel(session.status),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (narrow)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                RecordNumbers(summary, power?.average, powerSource)
                            }
                    }
                }
                if (session.status != "complete" || session.tags.isNotBlank()) {
                    Text(
                        listOfNotNull(
                                recordStatusLabel(session.status).takeIf {
                                    session.status != "complete"
                                },
                                session.tags.takeIf { it.isNotBlank() },
                            )
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (session.status == "interrupted") MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordNumbers(summary: Summary?, watts: Double?, source: String) {
    Text(
        "${summary?.avgFps?.let { "%.1f".format(it) } ?: "—"} FPS",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        "${Metrics.format("qpt.soc", watts)} W $source",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
