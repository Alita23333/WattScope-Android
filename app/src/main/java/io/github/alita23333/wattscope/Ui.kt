@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

// AGP 8.7's UAST misses the State.value setters under Kotlin 2.1; each producer below assigns it.
@file:android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")

package io.github.alita23333.wattscope

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alita23333.wattscope.WDialog as AlertDialog
import io.github.alita23333.wattscope.WIconButton as IconButton
import io.github.alita23333.wattscope.WTextField as OutlinedTextField
import java.io.File
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.NavigationBar as MiNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem as MiNavigationItem
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDark
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLight

val Mint = Color(0xFF79DDC8)
val LocalSettingsRevision = compositionLocalOf { 0 }

fun send(context: Context, action: String, enabled: Boolean = true) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, MonitorService::class.java).setAction(action).putExtra("enabled", enabled),
    )
}

fun readableTime(ns: Long): String {
    val s = ns / 1_000_000_000
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

fun recordStatusLabel(value: String) =
    when (value) {
        "complete" -> "已保存"
        "interrupted" -> "意外中断"
        "imported" -> "已导入"
        "recording" -> "记录中"
        "auto_paused" -> "自动暂停"
        "manual_paused",
        "paused" -> "已暂停"
        "saving" -> "保存中"
        else -> value
    }

@Composable
fun WattScope(app: WattApp) {
    val rev by app.settings.revision.collectAsStateWithLifecycle()
    val live by app.engine.live.collectAsStateWithLifecycle()
    val sessions by
        app.repository.dao.watch().collectAsStateWithLifecycle(initialValue = emptyList())
    var route by rememberSaveable { mutableStateOf("monitor") }
    var compareIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var comparisonIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var comparisonBack by rememberSaveable { mutableStateOf("records") }
    BackHandler(enabled = route != "monitor") {
        route =
            when {
                route == "compare" -> comparisonBack
                route.startsWith("detail:") -> "records"
                else -> "monitor"
            }
    }
    LaunchedEffect(route) { if (route != "monitor") app.engine.preview(false) }
    val scope = rememberCoroutineScope()
    val dark = remember(rev) { app.settings.dark }
    val colors =
        remember(dark) {
            if (dark)
                darkColorScheme(
                    primary = Color(0xFF4B91FF),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFF17345A),
                    onPrimaryContainer = Color(0xFFD5E7FF),
                    secondary = Color(0xFF00C8D6),
                    secondaryContainer = Color(0xFF173C40),
                    onSecondaryContainer = Color(0xFFD3FAFF),
                    background = Color(0xFF080808),
                    surface = Color(0xFF1C1C1E),
                    surfaceVariant = Color(0xFF28282A),
                    onSurface = Color(0xFFF3F3F3),
                    onSurfaceVariant = Color(0xFF9B9B9F),
                    outlineVariant = Color(0xFF454547),
                    surfaceContainer = Color(0xFF1C1C1E),
                    surfaceContainerHigh = Color(0xFF242426),
                    surfaceContainerHighest = Color(0xFF2C2C2E),
                    surfaceTint = Color.Transparent,
                )
            else
                lightColorScheme(
                    primary = Color(0xFF3482FF),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFE7F0FF),
                    onPrimaryContainer = Color(0xFF174A85),
                    secondary = Color(0xFF009AA7),
                    background = Color(0xFFF5F5F7),
                    surface = Color.White,
                    surfaceVariant = Color(0xFFF0F0F2),
                    onSurface = Color(0xFF171719),
                    onSurfaceVariant = Color(0xFF85858B),
                    outlineVariant = Color(0xFFDADADD),
                    surfaceContainer = Color.White,
                    surfaceContainerHigh = Color(0xFFF8F8F8),
                    surfaceContainerHighest = Color(0xFFF0F0F0),
                    surfaceTint = Color.Transparent,
                )
        }
    val miuiColors =
        remember(colors) {
            if (dark)
                miuixDark(
                    background = colors.background,
                    surface = colors.surface,
                    primary = colors.primary,
                )
            else
                miuixLight(
                    background = colors.background,
                    surface = colors.surface,
                    primary = colors.primary,
                )
        }
    val activity = androidx.activity.compose.LocalActivity.current
    SideEffect {
        activity?.let {
            val controller =
                androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
            controller.isAppearanceLightStatusBars = !app.settings.dark
            controller.isAppearanceLightNavigationBars = !app.settings.dark
        }
    }
    MiuixTheme(colors = miuiColors) {
        MaterialTheme(colorScheme = colors, typography = MiuiTypography) {
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(live.message) {
                if (
                    live.message.contains("失败") ||
                        live.message.startsWith("无法") ||
                        live.message.contains("中断") ||
                        live.message.contains("采集停止")
                )
                    snackbar.showSnackbar(live.message)
            }
            CompositionLocalProvider(
                LocalContentColor provides colors.onSurface,
                LocalSettingsRevision provides rev,
            ) {
                Box(Modifier.fillMaxSize().background(colors.background)) {
                    BackgroundLayer(app.settings.background)
                    Scaffold(
                        containerColor = Color.Transparent,
                        snackbarHost = { SnackbarHost(snackbar) { WSnackbar(it) } },
                        bottomBar = {
                            if (!route.startsWith("detail") && route != "compare")
                                MiNavigationBar(
                                    items =
                                        listOf(
                                            MiNavigationItem("监控", Icons.Outlined.Speed),
                                            MiNavigationItem("记录", Icons.Outlined.Timeline),
                                            MiNavigationItem("设置", Icons.Outlined.Tune),
                                        ),
                                    selected =
                                        listOf("monitor", "records", "settings")
                                            .indexOf(route)
                                            .coerceAtLeast(0),
                                    onClick = {
                                        route = listOf("monitor", "records", "settings")[it]
                                    },
                                    color = colors.background,
                                )
                        },
                    ) { padding ->
                        Box(Modifier.padding(padding)) {
                            when {
                                route == "monitor" -> MiuixMonitorPage(app, live)
                                route == "records" ->
                                    RecordsPage(
                                        app,
                                        sessions,
                                        compareIds,
                                        { id ->
                                            compareIds =
                                                if (id in compareIds) compareIds - id
                                                else (compareIds + id).takeLast(2)
                                        },
                                        { route = "detail:$it" },
                                        {
                                            comparisonIds = compareIds
                                            comparisonBack = "records"
                                            route = "compare"
                                        },
                                    )
                                route == "settings" -> SettingsPage(app, live)
                                route.startsWith("detail:") ->
                                    DetailPage(
                                        app,
                                        route.substringAfter(':'),
                                        { route = "records" },
                                        {
                                            comparisonIds =
                                                listOf(
                                                    route.substringAfter(':'),
                                                    route.substringAfter(':'),
                                                )
                                            comparisonBack = route
                                            route = "compare"
                                        },
                                    )
                                route == "compare" ->
                                    ComparePage(app, comparisonIds, { route = comparisonBack })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BackgroundLayer(path: String) {
    val bitmap by
        produceState<android.graphics.Bitmap?>(null, path) {
            value =
                withContext(Dispatchers.IO) {
                    if (path.isBlank()) null
                    else runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                }
        }
    bitmap?.let {
        Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val app = LocalContext.current.applicationContext as WattApp
    val compact = remember(LocalSettingsRevision.current) { app.settings.bool("compact", false) }
    top.yukonga.miuix.kmp.basic.Card(
        modifier = modifier.fillMaxWidth(),
        color =
            MaterialTheme.colorScheme.surface.copy(
                alpha = app.settings.int("card.opacity", 94) / 100f
            ),
        cornerRadius = 22.dp,
        insideMargin = PaddingValues(0.dp),
    ) {
        Column(
            Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 11.dp),
            content = content,
        )
    }
}

@Composable
fun PageTitle(eyebrow: String, title: String, subtitle: String = "", back: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
        if (back != null) IconButton(onClick = back) { Icon(Icons.Outlined.ArrowBack, "返回") }
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle.isNotBlank())
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall) }
        Switch(checked, onChecked)
    }
}

@Composable
fun MetricTiles(ids: List<String>, values: Map<String, Double?>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ids.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { id ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            Metrics.label(id),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                Metrics.format(id, values[id]),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                " " + Metrics.unit(id),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun AppIcon(context: Context, pkg: String, modifier: Modifier = Modifier) {
    val icon =
        remember(pkg) {
            runCatching {
                    context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
                }
                .getOrNull()
        }
    if (icon != null) Image(icon, null, modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
    else Icon(Icons.Outlined.Apps, null, modifier.size(44.dp))
}

@Composable
fun RecordsPage(
    app: WattApp,
    sessions: List<SessionEntity>,
    selected: List<String>,
    select: (String) -> Unit,
    open: (String) -> Unit,
    compare: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selecting by rememberSaveable { mutableStateOf(selected.isNotEmpty()) }
    var onlyFavorites by remember { mutableStateOf(false) }
    var days by remember { mutableIntStateOf(0) }
    var deleted by remember { mutableStateOf<SessionEntity?>(null) }
    val scope = rememberCoroutineScope()
    var appFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val cutoff = if (days == 0) 0 else System.currentTimeMillis() - days * 86400000L
    val shown =
        sessions.filter {
            it.startWall >= cutoff &&
                (!onlyFavorites || it.favorite) &&
                (appFilter == null || it.packageName == appFilter) &&
                (it.title + it.appName + it.packageName + it.note + it.tags).contains(query, true)
        }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(pageSpacing()),
        verticalArrangement = Arrangement.spacedBy(pageSpacing()),
    ) {
        item { PageTitle("RECORD LIBRARY", "记录") }
        item {
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                placeholder = "搜索应用、名称、标签或备注",
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
            )
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WChip(appFilter == null, { appFilter = null }, label = { Text("全部应用") })
                sessions
                    .distinctBy { it.packageName }
                    .forEach { application ->
                        WChip(
                            appFilter == application.packageName,
                            { appFilter = application.packageName },
                            label = { Text(application.appName) },
                        )
                    }
            }
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WChip(onlyFavorites, { onlyFavorites = !onlyFavorites }, label = { Text("收藏") })
                listOf(0 to "全部日期", 1 to "近24小时", 7 to "近7天", 30 to "近30天").forEach { (d, t) ->
                    WChip(days == d, { days = d }, label = { Text(t) })
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${shown.size} 条记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selecting)
                        WTextButton(
                            onClick = {
                                selected.toList().forEach(select)
                                selecting = false
                            }
                        ) {
                            Text("取消")
                        }
                    WSecondaryButton(
                        onClick = {
                            if (selecting && selected.size == 2) compare() else selecting = true
                        },
                        enabled = !selecting || selected.size == 2,
                    ) {
                        Text(if (selecting) "对比 ${selected.size}/2" else "选择对比")
                    }
                }
            }
        }
        deleted?.let { d ->
            item {
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("记录已移入回收状态")
                        WTextButton(
                            onClick = {
                                scope.launch {
                                    app.repository.dao.update(d.copy(deleted = false))
                                    deleted = null
                                }
                            }
                        ) {
                            Text("撤销")
                        }
                    }
                }
            }
        }
        if (shown.isEmpty())
            item { Panel { Text("还没有符合条件的记录", style = MaterialTheme.typography.titleMedium) } }
        items(shown, key = { it.id }) { s ->
            val summary by
                produceState<Summary?>(null, s.id, s.endNs) {
                    if (s.status in listOf("complete", "interrupted", "imported"))
                        value =
                            withContext(Dispatchers.IO) {
                                runCatching { app.repository.cachedSummary(s) }.getOrNull()
                            }
                }
            RecordEntry(
                session = s,
                summary = summary,
                selecting = selecting,
                selected = s.id in selected,
                onOpen = { open(s.id) },
                onSelect = {
                    selecting = true
                    select(s.id)
                },
                onFavorite = {
                    scope.launch { app.repository.dao.update(s.copy(favorite = !s.favorite)) }
                },
                onDelete = {
                    scope.launch {
                        app.repository.dao.update(s.copy(deleted = true))
                        if (s.id in selected) select(s.id)
                        deleted = s
                    }
                },
            )
        }
    }
}

@Composable
fun SettingsPage(app: WattApp, live: LiveState) {
    val context = LocalContext.current
    val prefs = remember(LocalSettingsRevision.current) { app.settings }
    val scope = rememberCoroutineScope()
    var editOsd by remember { mutableStateOf(false) }
    var smallChoice by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    busy = true
                    runCatching {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)!!.use {
                                    Transfer.importRecord(app.repository, it)
                                }
                            }
                        }
                        .onSuccess { app.engine.notifyMessage("记录导入成功，原有记录保持完整") }
                        .onFailure { app.engine.notifyMessage("导入失败：${it.message}") }
                    busy = false
                }
        }
    val background =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null)
                scope.launch {
                    busy = true
                    runCatching {
                            withContext(Dispatchers.IO) { Backgrounds.import(context, uri, prefs) }
                        }
                        .onFailure { app.engine.notifyMessage("背景导入失败：${it.message}") }
                    busy = false
                }
        }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(pageSpacing()),
        verticalArrangement = Arrangement.spacedBy(pageSpacing()),
    ) {
        item { PageTitle("PREFERENCES", "设置") }
        item {
            Panel {
                Text("权限与运行", style = MaterialTheme.typography.titleMedium)
                Text(live.root)
                WSecondaryButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                ) {
                    Text("悬浮窗权限")
                }
                WSecondaryButton(onClick = { send(context, "exit") }) { Text("结束并退出监控") }
            }
        }
        item {
            Panel {
                Text("记录小窗", style = MaterialTheme.typography.titleMedium)
                WSecondaryButton(onClick = { smallChoice = true }) {
                    Text("显示：${Metrics.label(prefs.smallMetric)}")
                }
                SettingSlider("大小", prefs.int("small.scale", 100), 70..170) {
                    prefs.set("small.scale", it)
                }
                ToggleRow("锁定位置", prefs.bool("small.locked")) { prefs.set("small.locked", it) }
            }
        }
        item {
            Panel {
                Text("OSD 数据面板", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WChip(
                        prefs.osdTemplate == "gauges",
                        { prefs.osdTemplate = "gauges" },
                        label = { Text("仪表＋文字") },
                    )
                    WChip(
                        prefs.osdTemplate == "text",
                        { prefs.osdTemplate = "text" },
                        label = { Text("纯文字") },
                    )
                }
                WSecondaryButton(onClick = { editOsd = true }) {
                    Text("指标与排序 · ${prefs.osdMetrics.size} 项")
                }
                SettingSlider("整体大小", prefs.int("osd.scale", 100), 60..150) {
                    prefs.set("osd.scale", it)
                }
                SettingSlider("字号", prefs.int("osd.font", 12), 9..18) { prefs.set("osd.font", it) }
                SettingSlider("不透明度", prefs.int("osd.opacity", 85), 25..100) {
                    prefs.set("osd.opacity", it)
                }
                ToggleRow("锁定位置", prefs.bool("osd.locked")) { prefs.set("osd.locked", it) }
                Text("强调色", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(0xFF7ECDED, 0xFF79DDC8, 0xFFFFCA8A, 0xFFCEB6F5).forEach { hex ->
                        val c = hex.toInt()
                        Box(
                            Modifier.size(32.dp)
                                .background(Color(c), RoundedCornerShape(16.dp))
                                .clickable { prefs.set("osd.accent", c) }
                        )
                    }
                }
                Text("面板底色", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(0xFF1D2630, 0xFF191919, 0xFF2D2337, 0xFF17332D).forEach { hex ->
                        Box(
                            Modifier.size(32.dp)
                                .background(Color(hex.toInt()), RoundedCornerShape(16.dp))
                                .border(1.dp, Color.Gray, RoundedCornerShape(16.dp))
                                .clickable { prefs.set("osd.background", hex.toInt()) }
                        )
                    }
                }
            }
        }
        item {
            Panel {
                Text("主题与背景", style = MaterialTheme.typography.titleMedium)
                ToggleRow("深色主题", prefs.dark) { prefs.dark = it }
                ToggleRow("紧凑布局", prefs.bool("compact", false)) { prefs.set("compact", it) }
                WSecondaryButton(onClick = { background.launch("image/*") }, enabled = !busy) {
                    Text("选择本地背景图片")
                }
                SettingSlider("背景模糊", prefs.int("background.blur", 8), 0..20) {
                    prefs.set("background.blur", it)
                }
                SettingSlider("明暗遮罩", prefs.int("background.dim", 50), 0..90) {
                    prefs.set("background.dim", it)
                }
                SettingSlider("裁剪缩放", prefs.int("background.zoom", 100), 100..200) {
                    prefs.set("background.zoom", it)
                }
                SettingSlider("水平定位", prefs.int("background.x", 50), 0..100) {
                    prefs.set("background.x", it)
                }
                SettingSlider("垂直定位", prefs.int("background.y", 50), 0..100) {
                    prefs.set("background.y", it)
                }
                SettingSlider("卡片不透明度", prefs.int("card.opacity", 94), 50..100) {
                    prefs.set("card.opacity", it)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WSecondaryButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching {
                                        withContext(Dispatchers.IO) {
                                            Backgrounds.process(context, prefs)
                                        }
                                    }
                                    .onFailure { app.engine.notifyMessage("背景处理失败：${it.message}") }
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text("应用背景调整")
                    }
                    WTextButton(onClick = { prefs.background = "" }) { Text("恢复默认") }
                }
            }
        }
        item {
            Panel {
                Text("数据与备份", style = MaterialTheme.typography.titleMedium)
                WSecondaryButton(
                    onClick = {
                        import.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    enabled = !busy,
                ) {
                    Text("导入完整记录包")
                }
                WSecondaryButton(
                    onClick = {
                        scope.launch {
                            val f =
                                withContext(Dispatchers.IO) {
                                    File(context.cacheDir, "exports")
                                        .apply { mkdirs() }
                                        .let { File(it, "diagnostics.json") }
                                        .apply {
                                            writeText(
                                                org.json
                                                    .JSONObject()
                                                    .put(
                                                        "device",
                                                        "${android.os.Build.MODEL} ${android.os.Build.VERSION.RELEASE}",
                                                    )
                                                    .put("root", live.root)
                                                    .put(
                                                        "health",
                                                        org.json.JSONObject(
                                                            live.health.mapValues {
                                                                it.value.toString()
                                                            }
                                                        ),
                                                    )
                                                    .put(
                                                        "events",
                                                        org.json.JSONArray(
                                                            live.events.map { it.toString() }
                                                        ),
                                                    )
                                                    .toString(2)
                                            )
                                        }
                                }
                            val uri =
                                androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    context.packageName + ".files",
                                    f,
                                )
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND)
                                        .setType("application/json")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                    "导出诊断",
                                )
                            )
                        }
                    }
                ) {
                    Text("导出采集诊断")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        item {
            Text(
                "WattScope ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (smallChoice) {
        val ids =
            (listOf("fps", "battery.power") +
                    live.metrics
                        .filter { it.id.startsWith("qpt.") && !it.id.endsWith(".energy") }
                        .map { it.id })
                .distinct()
        AlertDialog(
            onDismissRequest = { smallChoice = false },
            title = { Text("小窗单项数据") },
            text = {
                LazyColumn {
                    items(ids) { id ->
                        WTextButton(
                            onClick = {
                                prefs.smallMetric = id
                                smallChoice = false
                            }
                        ) {
                            Text(Metrics.label(id))
                        }
                    }
                }
            },
            confirmButton = { WTextButton(onClick = { smallChoice = false }) { Text("关闭") } },
        )
    }
    if (editOsd) MetricPicker(prefs, live, onDismiss = { editOsd = false })
}

@Composable
fun SettingSlider(label: String, value: Int, range: IntRange, change: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value.toString(), style = MaterialTheme.typography.labelSmall)
        }
        WSlider(
            value.toFloat(),
            { change(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
fun MetricPicker(prefs: Settings, live: LiveState, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(prefs.osdMetrics) }
    val ids =
        (selected +
                Metrics.defaultOsd +
                live.metrics.map { it.id } +
                live.health.keys +
                (0..7).flatMap { listOf("cpu.$it.freq", "cpu.$it.usage") })
            .distinct()
            .filterNot {
                it.endsWith(".energy") ||
                    it.endsWith(".online") ||
                    it == "battery.current" ||
                    it == "battery.voltage"
            }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OSD 指标与排序") },
        text = {
            LazyColumn(Modifier.height(460.dp)) {
                items((selected + ids.filterNot { it in selected }).distinct(), key = { it }) { id
                    ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            id in selected,
                            { on -> selected = if (on) selected + id else selected - id },
                        )
                        Text(
                            Metrics.label(id),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (id in selected) {
                            IconButton(
                                onClick = {
                                    val i = selected.indexOf(id)
                                    if (i > 0)
                                        selected =
                                            selected.toMutableList().apply {
                                                removeAt(i)
                                                add(i - 1, id)
                                            }
                                }
                            ) {
                                Icon(Icons.Outlined.KeyboardArrowUp, "上移")
                            }
                            IconButton(
                                onClick = {
                                    val i = selected.indexOf(id)
                                    if (i < selected.lastIndex)
                                        selected =
                                            selected.toMutableList().apply {
                                                removeAt(i)
                                                add(i + 1, id)
                                            }
                                }
                            ) {
                                Icon(Icons.Outlined.KeyboardArrowDown, "下移")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            WTextButton(
                onClick = {
                    prefs.osdMetrics = selected.ifEmpty { listOf("fps") }
                    onDismiss()
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = { WTextButton(onClick = onDismiss) { Text("取消") } },
    )
}
