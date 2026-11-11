@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

// Each producer assigns State.value; AGP 8.7's Kotlin 2.1 UAST does not resolve these setters.
@file:android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")

package io.github.alita23333.wattscope

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alita23333.wattscope.WCircularProgressIndicator as CircularProgressIndicator
import io.github.alita23333.wattscope.WDialog as AlertDialog
import io.github.alita23333.wattscope.WDropdownMenu as DropdownMenu
import io.github.alita23333.wattscope.WDropdownMenuItem as DropdownMenuItem
import io.github.alita23333.wattscope.WIconButton as IconButton
import io.github.alita23333.wattscope.WLinearProgressIndicator as LinearProgressIndicator
import io.github.alita23333.wattscope.WRangeSlider as RangeSlider
import io.github.alita23333.wattscope.WTextField as OutlinedTextField
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DetailPage(app: WattApp, id: String, back: () -> Unit, compareRanges: () -> Unit) {
    val repo = app.repository
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val loaded by repo.dao.observe(id).collectAsStateWithLifecycle(initialValue = null)
    val s = loaded
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (s.status !in listOf("complete", "interrupted", "imported")) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("记录结束保存后显示分析")
                WTextButton(onClick = back) { Text("返回记录") }
            }
        }
        return
    }
    var revision by remember { mutableIntStateOf(0) }
    var loadError by remember(id) { mutableStateOf<String?>(null) }
    val full by
        produceState<Analysis?>(null, id, s.endNs, revision) {
            val result = withContext(Dispatchers.IO) { runCatching { repo.analyze(s) } }
            loadError = result.exceptionOrNull()?.message
            value = result.getOrNull()
        }
    val a = full
    if (a == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (loadError == null) {
                    CircularProgressIndicator()
                    Text("读取原始记录…")
                } else {
                    Text("无法读取记录：$loadError")
                    WSecondaryButton(
                        onClick = {
                            loadError = null
                            revision++
                        }
                    ) {
                        Text("重试")
                    }
                    WTextButton(onClick = back) { Text("返回记录") }
                }
            }
        }
        return
    }
    val total = ((a.lastNs - s.startNs) / 1e9).coerceAtLeast(.1)
    var viewport by remember(id) { mutableStateOf(Viewport(0.0, total)) }
    var selection by remember(id) { mutableStateOf(0f..total.toFloat()) }
    var requested by remember(id) { mutableStateOf<LongRange?>(null) }
    var selectedSummary by remember(id) { mutableStateOf<Summary?>(null) }
    var calculation by remember(id) { mutableIntStateOf(0) }
    var calculating by remember(id) { mutableStateOf(false) }
    val ranges by repo.dao.ranges(id).collectAsState(initial = emptyList())
    var nameDialog by remember { mutableStateOf(false) }
    var rangeName by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf(false) }
    var title by remember(s.id) { mutableStateOf(s.title) }
    var note by remember(s.id) { mutableStateOf(s.note) }
    var tags by remember(s.id) { mutableStateOf(s.tags) }
    var exporting by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var allCharts by remember { mutableStateOf(false) }
    fun currentRange() =
        (s.startNs + (selection.start * 1e9).toLong())..(s.startNs +
                (selection.endInclusive * 1e9).toLong())
    fun calculate(r: LongRange?) {
        val generation = ++calculation
        requested = r
        selectedSummary = null
        calculating = r != null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { if (r == null) null else repo.analyze(s, r).summary }
            }
            if (generation == calculation) {
                selectedSummary = result.getOrNull()
                calculating = false
                result.exceptionOrNull()?.let {
                    requested = null
                    app.engine.notifyMessage("选区重算失败：${it.message}")
                }
            }
        }
    }
    fun export(kind: String) {
        exportMenu = false
        val exportRange = requested
        scope.launch {
            exporting = true
            runCatching {
                    withContext(Dispatchers.IO) {
                        when (kind) {
                            "csv" -> Transfer.csv(repo, s, exportRange)
                            "image" -> Transfer.image(repo, s, exportRange)
                            else -> Transfer.backup(repo, s)
                        }
                    }
                }
                .onSuccess { Transfer.share(context, it) }
                .onFailure { app.engine.notifyMessage("导出失败：${it.message}") }
            exporting = false
        }
    }
    val leadingCharts =
        listOf("fps", "frame.ms", "group:cpu", "group:gpu", "group:cpuPower", "qpt.gpu")
    val remainingCharts =
        if (allCharts) a.series.map { it.id }
        else
            listOf("qpt.soc", "battery.power") +
                a.series.map { it.id }.filter { it.startsWith("qpt.") } +
                listOf("temp.soc", "temp.battery", "ram.percent", "ddr.freq")
    val chartIds =
        (leadingCharts +
                remainingCharts.filterNot {
                    it.startsWith("cpu.") ||
                        it == "gpu.freq" ||
                        it == "gpu.usage" ||
                        it == "qpt.cpu-l" ||
                        it == "qpt.cpu-m"
                })
            .distinct()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(pageSpacing()),
        verticalArrangement = Arrangement.spacedBy(pageSpacing()),
    ) {
        item {
            PageTitle(
                "TEST ANALYSIS",
                s.title,
                "${s.appName} ${s.appVersion} · ${s.intervalMs} ms",
                back,
            )
        }
        item {
            Panel {
                Text(s.device, style = MaterialTheme.typography.titleSmall)
                Text(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                        .format(Date(s.startWall)) + " · " + recordStatusLabel(s.status),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (s.note.isNotBlank()) Text(s.note)
                if (s.tags.isNotBlank()) Text(s.tags, color = MaterialTheme.colorScheme.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WSecondaryButton(onClick = { edit = true }) { Text("名称与备注") }
                    Box {
                        WSecondaryButton(
                            onClick = { exportMenu = true },
                            enabled = !exporting && s.endNs > 0,
                        ) {
                            Text("导出")
                        }
                        DropdownMenu(exportMenu, { exportMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("CSV 原始数据包") },
                                onClick = { export("csv") },
                            )
                            DropdownMenuItem(
                                text = { Text("摘要图片 PNG") },
                                onClick = { export("image") },
                            )
                            DropdownMenuItem(
                                text = { Text("完整记录备份") },
                                onClick = { export("backup") },
                            )
                        }
                    }
                }
                if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (a.damaged)
                    Text("检测到不完整尾块：已读取全部通过 CRC 的数据。", color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            if (calculating)
                Panel {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在重算选区…")
                }
            else SummaryPanel(selectedSummary ?: a.summary)
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("时间区间", style = MaterialTheme.typography.titleMedium)
                    WTextButton(
                        onClick = {
                            calculate(null)
                            viewport = Viewport(0.0, total)
                            selection = 0f..total.toFloat()
                        }
                    ) {
                        Text("重置")
                    }
                }
                Text(
                    "${elapsedTime(selection.start.toDouble(), 1)} — ${elapsedTime(selection.endInclusive.toDouble(), 1)}",
                    color = MaterialTheme.colorScheme.primary,
                )
                RangeSlider(
                    selection,
                    {
                        selection = it
                        calculation++
                        requested = null
                        selectedSummary = null
                        calculating = false
                        viewport =
                            Viewport(selection.start.toDouble(), selection.endInclusive.toDouble())
                    },
                    valueRange = 0f..total.toFloat(),
                    onValueChangeFinished = {
                        if (selection.start <= 0f && selection.endInclusive >= total.toFloat())
                            calculate(null)
                        else calculate(currentRange())
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WSecondaryButton(
                        onClick = {
                            rangeName = "区间 ${ranges.size+1}"
                            nameDialog = true
                        },
                        enabled = selection.endInclusive - selection.start > .1,
                    ) {
                        Text("保存区间")
                    }
                }
                WSecondaryButton(onClick = compareRanges, enabled = ranges.size >= 2) {
                    Text("对比区间")
                }
                ranges.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WTextButton(
                            onClick = {
                                selection =
                                    ((r.startNs - s.startNs) / 1e9).toFloat()..((r.endNs -
                                                s.startNs) / 1e9)
                                            .toFloat()
                                calculate(r.startNs..r.endNs)
                                viewport =
                                    Viewport(
                                        selection.start.toDouble(),
                                        selection.endInclusive.toDouble(),
                                    )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(r.name)
                        }
                        IconButton(onClick = { scope.launch { repo.dao.deleteRange(r.id) } }) {
                            Icon(Icons.Outlined.Close, "删除区间")
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("同步图表", style = MaterialTheme.typography.titleLarge)
                WTextButton(onClick = { allCharts = !allCharts }) {
                    Text(if (allCharts) "核心指标" else "所有指标")
                }
            }
        }
        items(chartIds, key = { it }) { metric ->
            when (metric) {
                "group:cpu" ->
                    CpuClusterCharts(a, viewport) { viewport = viewport.copy(cursor = it) }
                "group:gpu" -> GpuChart(a, viewport) { viewport = viewport.copy(cursor = it) }
                "group:cpuPower" ->
                    CpuPowerChart(a, viewport) { viewport = viewport.copy(cursor = it) }
                "qpt.soc",
                "battery.power" ->
                    SelectablePowerChart(a, metric, viewport, app.settings) {
                        viewport = viewport.copy(cursor = it)
                    }
                else ->
                    a.series
                        .firstOrNull { it.id == metric }
                        ?.let { series ->
                            Panel {
                                TraceChart(
                                    series,
                                    viewport,
                                    onCursor = { viewport = viewport.copy(cursor = it) },
                                )
                            }
                        }
            }
        }
    }
    if (nameDialog)
        AlertDialog(
            onDismissRequest = { nameDialog = false },
            title = { Text("命名分析区间") },
            text = { OutlinedTextField(rangeName, { rangeName = it }, singleLine = true) },
            confirmButton = {
                WTextButton(
                    onClick = {
                        val r = currentRange()
                        scope.launch {
                            repo.dao.insertRange(
                                RangeEntity(
                                    sessionId = id,
                                    name = rangeName.ifBlank { "分析区间" },
                                    startNs = r.first,
                                    endNs = r.last,
                                )
                            )
                        }
                        nameDialog = false
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = { WTextButton(onClick = { nameDialog = false }) { Text("取消") } },
        )
    if (edit)
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text("测试信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = "记录名称")
                    OutlinedTextField(note, { note = it }, label = "备注")
                    OutlinedTextField(tags, { tags = it }, label = "标签")
                }
            },
            confirmButton = {
                WTextButton(
                    onClick = {
                        scope.launch {
                            val updated =
                                s.copy(
                                    title = title.ifBlank { s.appName },
                                    note = note,
                                    tags = tags,
                                )
                            repo.dao.update(updated)
                            repo.metadata(id).writeText(repo.sessionJson(updated).toString(2))
                        }
                        edit = false
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = { WTextButton(onClick = { edit = false }) { Text("取消") } },
        )
}

@Composable
fun ComparePage(app: WattApp, ids: List<String>, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var allGraphs by remember { mutableStateOf(false) }
    var initializedRanges by remember(ids) { mutableStateOf(false) }
    val sessions by
        produceState<List<SessionEntity>>(emptyList(), ids) {
            value = withContext(Dispatchers.IO) { ids.mapNotNull { app.repository.dao.get(it) } }
        }
    if (sessions.size != 2) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            WTextButton(onClick = back) { Text("请选择两条已结束记录") }
        }
        return
    }
    val first = sessions[0]
    val second = sessions[1]
    val rangesA by app.repository.dao.ranges(first.id).collectAsState(initial = emptyList())
    val rangesB by app.repository.dao.ranges(second.id).collectAsState(initial = emptyList())
    var rangeA by remember { mutableStateOf<RangeEntity?>(null) }
    var rangeB by remember { mutableStateOf<RangeEntity?>(null) }
    LaunchedEffect(first.id, second.id, rangesA) {
        if (first.id == second.id && !initializedRanges && rangesA.size >= 2) {
            rangeA = rangesA.first()
            rangeB = rangesA.last()
            initializedRanges = true
        }
    }
    val a by
        produceState<Analysis?>(null, first.id, rangeA) {
            value =
                withContext(Dispatchers.IO) {
                    app.repository.analyze(first, rangeA?.let { it.startNs..it.endNs })
                }
        }
    val b by
        produceState<Analysis?>(null, second.id, rangeB) {
            value =
                withContext(Dispatchers.IO) {
                    app.repository.analyze(second, rangeB?.let { it.startNs..it.endNs })
                }
        }
    if (a == null || b == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    var viewport by
        remember(a, b) {
            mutableStateOf(
                Viewport(0.0, max(a!!.summary.seconds, b!!.summary.seconds).coerceAtLeast(1.0))
            )
        }
    fun aligned(series: Series, s: SessionEntity, analysis: Analysis) =
        series.copy(
            points =
                series.points.map { (seconds, v) ->
                    val abs = s.startNs + (seconds * 1e9).toLong()
                    analysis.segments.sumOf { g -> max(0L, min(g.end, abs) - g.start) } / 1e9 to v
                }
        )
    val keys =
        (a!!.series.map { it.id }.intersect(b!!.series.map { it.id }.toSet())).filter {
            allGraphs ||
                it in
                    listOf(
                        "fps",
                        "frame.ms",
                        "battery.power",
                        "qpt.soc",
                        "temp.soc",
                        "cpu.usage",
                        "gpu.usage",
                        "gpu.freq",
                    )
        }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(pageSpacing()),
        verticalArrangement = Arrangement.spacedBy(pageSpacing()),
    ) {
        item { PageTitle("A / B COMPARISON", "记录对比", back = back) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                WSecondaryButton(
                    enabled = !exporting,
                    onClick = {
                        val left = rangeA?.let { it.startNs..it.endNs }
                        val right = rangeB?.let { it.startNs..it.endNs }
                        scope.launch {
                            exporting = true
                            runCatching {
                                    withContext(Dispatchers.IO) {
                                        Transfer.comparisonCsv(
                                            app.repository,
                                            first,
                                            left,
                                            second,
                                            right,
                                        )
                                    }
                                }
                                .onSuccess { Transfer.share(context, it) }
                                .onFailure { app.engine.notifyMessage("对比导出失败：${it.message}") }
                            exporting = false
                        }
                    },
                ) {
                    Text(if (exporting) "正在导出" else "导出对比 CSV")
                }
                WTextButton(onClick = { allGraphs = !allGraphs }) {
                    Text(if (allGraphs) "核心图表" else "所有指标")
                }
            }
        }
        item {
            Panel {
                Text("A · ${first.title}", color = MaterialTheme.colorScheme.primary)
                Text(
                    "${first.appVersion} · ${first.intervalMs} ms · ${first.device}",
                    style = MaterialTheme.typography.labelSmall,
                )
                RangeChoice(rangesA, rangeA) { rangeA = it }
                HorizontalDivider()
                Text("B · ${second.title}", color = Color(0xFFFFB787))
                Text(
                    "${second.appVersion} · ${second.intervalMs} ms · ${second.device}",
                    style = MaterialTheme.typography.labelSmall,
                )
                RangeChoice(rangesB, rangeB) { rangeB = it }
            }
        }
        item {
            Panel {
                Text(
                    "指标                         A             B         B−A",
                    style = MaterialTheme.typography.labelSmall,
                )
                val x = a!!.summary
                val y = b!!.summary
                listOf(
                        "Avg FPS" to (x.avgFps to y.avgFps),
                        "1% Low" to (x.low1 to y.low1),
                        "5% Low" to (x.low5 to y.low5),
                        "Baseline FPS" to (x.frameBaseline.fps to y.frameBaseline.fps),
                        "Slow frames" to (x.slow?.toDouble() to y.slow?.toDouble()),
                        "Severe frames" to (x.severe?.toDouble() to y.severe?.toDouble()),
                        "P99 ms" to (x.p99 to y.p99),
                        "SoC power (W)" to
                            (x.powers["qpt.soc"]?.average to y.powers["qpt.soc"]?.average),
                        "Battery power (W)" to
                            (x.powers["battery.power"]?.average to
                                y.powers["battery.power"]?.average),
                    )
                    .forEach { (label, p) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                label,
                                Modifier.weight(1.1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            listOf(
                                    p.first,
                                    p.second,
                                    if (p.first != null && p.second != null) p.second!! - p.first!!
                                    else null,
                                )
                                .forEach { v ->
                                    Text(
                                        v?.let { "%.2f".format(it) } ?: "—",
                                        Modifier.weight(.8f),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                        }
                    }
            }
        }
        items(keys) { key ->
            Panel {
                TraceChart(
                    aligned(a!!.series.first { it.id == key }, first, a!!),
                    viewport,
                    second = aligned(b!!.series.first { it.id == key }, second, b!!),
                    onCursor = { viewport = viewport.copy(cursor = it) },
                )
            }
        }
    }
}

@Composable
private fun RangeChoice(
    ranges: List<RangeEntity>,
    selected: RangeEntity?,
    onChange: (RangeEntity?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        WSecondaryButton(onClick = { open = true }) { Text(selected?.name ?: "整段记录") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text("整段记录") },
                onClick = {
                    onChange(null)
                    open = false
                },
            )
            ranges.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.name) },
                    onClick = {
                        onChange(r)
                        open = false
                    },
                )
            }
        }
    }
}
