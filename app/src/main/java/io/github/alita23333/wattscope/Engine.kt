package io.github.alita23333.wattscope

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WattApp : Application() {
    lateinit var settings: Settings
    lateinit var repository: Repository
    lateinit var engine: Engine
    lateinit var recovery: Job

    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(15))
        settings = Settings(this)
        repository = Repository(this)
        engine = Engine(this, settings, repository)
        recovery = engine.scope.launch { repository.dao.recover() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            File(filesDir, "last-crash.txt").writeText(e.stackTraceToString())
            previous?.uncaughtException(t, e)
        }
    }
}

class Engine(val context: Context, val settings: Settings, val repo: Repository) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val live = MutableStateFlow(LiveState())
    private val lifecycle = Mutex()
    private val lock = Any()
    private var process: Process? = null
    private var collectorJob: Job? = null
    private var session: SessionEntity? = null
    private var writer: BlockWriter? = null
    private val segments = ArrayList<Segment>()
    private var lastCheckpoint = 0L
    private var lastHardwareNs = 0L
    private val frameWindow = ArrayDeque<Long>()
    private var lastFrameArrival = 0L
    private var frameFailure = ""
    private var collectorSignature = ""
    private val apps = AppCatalog(context)
    private var savingJob: Job? = null
    private var preparingJob: Job? = null
    private var controlPanelVisible = false
    private var panelDismissGraceNs = 0L

    fun controlPanel(visible: Boolean) {
        synchronized(lock) {
            controlPanelVisible = visible
            live.update { it.copy(controlPanelVisible = visible) }
            if (!visible) panelDismissGraceNs = SystemClock.elapsedRealtimeNanos() + 3_000_000_000L
            if (visible && live.value.recording == "recording")
                pauseLocked("auto_paused", "快捷面板", SystemClock.elapsedRealtimeNanos())
        }
    }

    fun mainAppOpened() {
        synchronized(lock) {
            if (session != null && live.value.recording != "saving") {
                event(Event(SystemClock.elapsedRealtimeNanos(), "app_switch", "打开主界面，自动结束并保存"))
                stopRecording()
            }
        }
    }

    data class TrendPoint(val ns: Long, val battery: Double?, val soc: Double?)

    private val trend = ArrayDeque<TrendPoint>()

    fun history(): List<TrendPoint> = synchronized(lock) { trend.toList() }

    init {
        scope.launch {
            while (isActive) {
                delay(1000)
                synchronized(lock) {
                    writer?.flush()
                    updateDuration()
                }
            }
        }
    }

    fun notifyMessage(message: String) {
        live.update { it.copy(message = message) }
    }

    private fun toast(message: String) {
        scope.launch(Dispatchers.Main.immediate) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    fun preview(on: Boolean) {
        live.update { it.copy(preview = on) }
        reconcile()
    }

    fun monitoring(on: Boolean) {
        live.update { it.withMonitoring(on) }
        reconcile()
    }

    fun small(on: Boolean) {
        live.update { it.copy(small = on) }
        reconcile()
    }

    fun osd(on: Boolean) {
        live.update { it.copy(osd = on) }
        reconcile()
    }

    fun configurationChanged() {
        reconcile()
    }

    private fun wantsCapture(): Boolean {
        return live.value.captureRequested
    }

    private fun wantsFrames(): Boolean {
        val s = live.value
        return s.recording != "idle" ||
            (s.small && settings.smallMetric in listOf("fps", "frame.ms")) ||
            (s.osd && settings.osdMetrics.any { it in listOf("fps", "frame.ms") })
    }

    private fun signature() = "auto:${settings.interval}:${wantsFrames()}"

    fun reconcile() {
        scope.launch {
            lifecycle.withLock {
                val signature = signature()
                if (process != null && (!wantsCapture() || collectorSignature != signature)) {
                    stopCollector()
                }
                if (process == null && wantsCapture()) startCollector(signature)
            }
        }
    }

    private suspend fun startCollector(signature: String) {
        live.update { it.copy(root = "正在请求 Root", message = "正在连接常驻采集器") }
        try {
            var shell = Shell.getShell()
            if (!shell.isRoot) {
                shell.close()
                shell = Shell.getShell()
            }
            check(shell.isRoot) { "请在 KernelSU 中允许 WattScope 使用 Root" }
            val executable = File(context.applicationInfo.nativeLibraryDir, "libcollector.so")
            check(executable.exists()) { "采集器未打包：${executable.path}" }
            val state = File(context.filesDir, "collector").apply { mkdirs() }
            val appList = apps.prepare(state)
            val cmd =
                "exec '${executable.path}' --interval ${settings.interval} --state '${state.path}' --auto-packages '${appList.path}'" +
                    (if (wantsFrames()) "" else " --no-frames")
            val p = ProcessBuilder("su", "-c", cmd).start()
            process = p
            collectorSignature = signature
            synchronized(lock) {
                frameWindow.clear()
                lastFrameArrival = 0
                lastHardwareNs = 0
                frameFailure = ""
            }
            live.update {
                it.copy(
                    root = "Root 已连接",
                    collector = true,
                    metrics = emptyList(),
                    health = emptyMap(),
                    currentPackage = "",
                    currentApp = "",
                    currentRecordable = false,
                    message = "自动识别前台应用",
                )
            }
            scope.launch {
                p.errorStream.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        if (line.isNotBlank())
                            event(Event(SystemClock.elapsedRealtimeNanos(), "stderr", line))
                    }
                }
            }
            collectorJob =
                scope.launch {
                    try {
                        p.inputStream.bufferedReader().useLines { seq -> seq.forEach(::accept) }
                    } catch (e: Exception) {
                        event(
                            Event(
                                SystemClock.elapsedRealtimeNanos(),
                                "collector_error",
                                e.message ?: "读取失败",
                            )
                        )
                    }
                    if (process === p) {
                        process = null
                        live.update {
                            it.copy(collector = false, root = "采集器已停止", message = "采集停止，请查看数据质量与事件")
                        }
                        if (session != null) stopRecording("interrupted")
                    }
                }
        } catch (e: Exception) {
            live.update {
                it.copy(root = "Root 连接失败", collector = false, message = e.message ?: "连接失败")
            }
            if (session != null) stopRecording("interrupted")
        }
    }

    private suspend fun stopCollector() {
        val p = process ?: return
        process = null
        runCatching {
            p.outputStream.write("stop\n".toByteArray())
            p.outputStream.flush()
        }
        withTimeoutOrNull(4500) { collectorJob?.join() }
        runCatching { p.destroy() }
        collectorJob = null
        live.update {
            it.copy(
                collector = false,
                fps = null,
                frameMs = null,
                currentPackage = "",
                currentApp = "",
                currentRecordable = false,
            )
        }
    }

    private fun accept(line: String) {
        try {
            val p = line.split('|', limit = 6)
            synchronized(lock) {
                when (p[0]) {
                    "A" -> foregroundChanged(p.getOrElse(2) { "" }, p[1].toLong())
                    "D" -> {
                        val m =
                            Metric(p[1], p[2], p[3], p.getOrElse(4) { "" }, p.getOrElse(5) { "" })
                        live.update {
                            it.copy(metrics = it.metrics.filterNot { a -> a.id == m.id } + m)
                        }
                        writer?.append(line)
                    }
                    "H" -> hardware(Hardware.parse(line))
                    "F" -> {
                        val t = p[1].toLong()
                        lastFrameArrival = SystemClock.elapsedRealtimeNanos()
                        frameWindow.addLast(t)
                        while (frameWindow.size > 512) frameWindow.removeFirst()
                        if (segments.any { t >= it.start && t <= it.end } && writer != null)
                            writer?.append("F|$t|${p[2]}|${p.getOrElse(3){""}.hashCode()}")
                    }
                    "J" -> {
                        val t = p[1].toLong()
                        if (segments.any { t in it.start..it.end }) writer?.append("J|$t|${p[2]}")
                    }
                    "E" -> event(Event(p[1].toLong(), p[2], p.getOrElse(3) { "" }))
                }
            }
        } catch (e: Exception) {
            event(
                Event(SystemClock.elapsedRealtimeNanos(), "protocol_error", e.message ?: "采集行解析失败")
            )
        }
    }

    private fun foregroundChanged(pkg: String, now: Long) {
        frameWindow.clear()
        lastFrameArrival = 0
        val allowed = apps.recordable(pkg)
        live.update {
            it.copy(
                currentPackage = pkg,
                currentApp = apps.label(pkg),
                currentRecordable = allowed,
                fps = null,
                frameMs = null,
            )
        }
        event(Event(now, "foreground", pkg.ifBlank { "没有可识别的前台应用" }))
        val interactive =
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (
            session != null &&
                session!!.packageName != pkg &&
                !(pkg == context.packageName &&
                    (controlPanelVisible || now < panelDismissGraceNs)) &&
                interactive &&
                live.value.recording != "saving"
        ) {
            event(Event(now, "app_switch", "切换应用，自动结束并保存"))
            stopRecording(atNs = now)
        } else if (live.value.recording == "armed" && allowed && interactive) beginRecording(pkg)
    }

    private fun hardware(h: Hardware) {
        if (lastHardwareNs > 0 && h.ns - lastHardwareNs > settings.interval * 2_500_000L) {
            event(
                Event(h.ns, "sampling_gap", "硬件采样中断 %.3f 秒".format((h.ns - lastHardwareNs) / 1e9))
            )
            notifyMessage("检测到采样中断，记录将保留缺口")
        }
        lastHardwareNs = h.ns
        val interactive =
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val foreground =
            h.foreground && interactive && live.value.currentRecordable && !controlPanelVisible
        val state = live.value.recording
        if (session != null && state != "saving") {
            if (state == "recording" && !foreground)
                pauseLocked("auto_paused", if (!interactive) "锁屏" else "前台暂时不可见", h.ns)
            else if (state == "auto_paused" && foreground) resumeLocked(h.ns)
        }
        val arrival = SystemClock.elapsedRealtimeNanos()
        val times =
            frameWindow
                .filter { it >= h.ns - 1_000_000_000L && it <= h.ns + 50_000_000L }
                .distinct()
                .sorted()
        val fps =
            if (
                frameFailure.isEmpty() &&
                    foreground &&
                    arrival - lastFrameArrival < 2_000_000_000L &&
                    times.size > 1
            )
                (times.size - 1) * 1e9 / (times.last() - times.first())
            else null
        val frame =
            if (frameFailure.isEmpty() && times.size > 1)
                (times.last() - times[times.size - 2]) / 1e6
            else null
        val extended = h.copy(values = h.values + mapOf("fps" to fps, "frame.ms" to frame))
        trend.addLast(TrendPoint(h.ns, BatteryPower.display(h), h.values["qpt.soc"]))
        while (trend.size > 600) trend.removeFirst()
        val health = live.value.health.toMutableMap()
        extended.values.forEach { (id, v) ->
            val old = health[id] ?: MetricHealth("暂时缺失")
            val issue =
                h.issues[id]
                    ?: if (id in listOf("fps", "frame.ms") && frameFailure.isNotEmpty())
                        MetricIssue("read_failed", frameFailure)
                    else null
            health[id] =
                if (v != null)
                    old.copy(
                        state = "正常",
                        lastSuccessNs = h.ns,
                        received = old.received + 1,
                        reason = "",
                    )
                else
                    old.copy(
                        state =
                            when (issue?.state) {
                                "unsupported" -> "设备不支持"
                                "read_failed" -> "读取失败"
                                else -> if (id == "ddr.freq") "设备不支持" else "暂时缺失"
                            },
                        failures = old.failures + 1,
                        received = old.received + 1,
                        reason =
                            issue?.reason
                                ?: when (id) {
                                    "fps" ->
                                        if (!foreground) "当前不在可记录应用中"
                                        else "等待当前应用 SurfaceView 呈现帧；普通界面可能没有该图层"
                                    "ddr.freq" -> "未发现确认含义的 DDR 实际时钟源"
                                    else -> "节点缺失、读取失败或本周期没有有效值"
                                },
                    )
        }
        live.update { it.copy(latest = extended, fps = fps, frameMs = frame, health = health) }
        if (live.value.recording == "recording") writer?.append(h.recordLine())
        if (session != null && h.ns - lastCheckpoint >= 1_000_000_000L) {
            lastCheckpoint = h.ns
            val current = session!!
            val status = live.value.recording
            scope.launch { repo.dao.checkpoint(current.id, h.ns, status) }
        }
        updateDuration()
    }

    fun event(e: Event) {
        synchronized(lock) {
            if (e.kind == "frame_error") {
                frameFailure = e.message
                frameWindow.clear()
                live.update { it.copy(fps = null, frameMs = null) }
            } else if (e.kind == "layer") {
                frameFailure = ""
                frameWindow.clear()
                lastFrameArrival = 0
                val metrics =
                    listOf(
                        Metric("fps", "游戏 FPS", "FPS", "Perfetto PRESENT_FENCE @ ${e.message}"),
                        Metric("frame.ms", "呈现帧间隔", "ms", "Perfetto PRESENT_FENCE @ ${e.message}"),
                    )
                live.update {
                    it.copy(
                        metrics =
                            it.metrics.filterNot { m -> m.id in listOf("fps", "frame.ms") } +
                                metrics
                    )
                }
                metrics.forEach {
                    writer?.append("D|${it.id}|${it.label}|${it.unit}|${it.source}|")
                }
            }
            live.update { it.copy(events = (it.events + e).takeLast(60)) }
            writer?.append("E|${e.ns}|${e.kind}|${e.message.replace('\n',' ')}")
        }
    }

    private fun updateDuration() {
        val now = SystemClock.elapsedRealtimeNanos()
        val n = segments.sumOf { maxOf(0, minOf(it.end, now) - it.start) }
        live.update { it.copy(effectiveNs = n) }
    }

    fun toggleRecord() {
        if (live.value.recording == "idle") startRecording()
        else if (live.value.recording !in listOf("preparing", "saving")) stopRecording()
    }

    fun startRecording() {
        synchronized(lock) {
            if (live.value.recording != "idle") return
            live.update { it.copy(recording = "armed", message = "切到应用后自动开始；切换应用时结束保存") }
        }
        scope.launch {
            lifecycle.withLock {
                val desired = signature()
                if (process != null && collectorSignature != desired) stopCollector()
                if (process == null) startCollector(desired)
            }
            synchronized(lock) {
                if (process == null) live.update { it.copy(recording = "idle") }
                else if (live.value.recording == "armed" && live.value.currentRecordable)
                    beginRecording(live.value.currentPackage)
            }
        }
    }

    private fun beginRecording(pkg: String) {
        if (live.value.recording != "armed" || !apps.recordable(pkg)) return
        live.update { it.copy(recording = "preparing") }
        preparingJob =
            scope.launch {
                try {
                    (context.applicationContext as WattApp).recovery.join()
                    val pm = context.packageManager
                    val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (live.value.currentPackage != pkg) {
                        live.update { it.copy(recording = "idle", message = "应用已切换，记录未开始") }
                        reconcile()
                        return@launch
                    }
                    val name = apps.label(pkg)
                    val item =
                        SessionEntity(
                            title = name,
                            packageName = pkg,
                            appName = name,
                            appVersion = info?.versionName ?: "",
                            startWall = System.currentTimeMillis(),
                            startNs = now,
                            intervalMs = settings.interval,
                            targetFps = settings.target,
                        )
                    repo.dao.insert(item)
                    repo.metadata(item.id).writeText(repo.sessionJson(item).toString(2))
                    synchronized(lock) {
                        session = item
                        writer = BlockWriter(repo.file(item.id))
                        segments.clear()
                        writer?.append(
                            "E|$now|start|algorithm=${Statistics.ALGORITHM_VERSION}; game-layer PRESENT_FENCE; target=${settings.target}"
                        )
                        live.value.metrics.forEach {
                            writer?.append(
                                "D|${it.id}|${it.label}|${it.unit}|${it.source}|${it.parent}"
                            )
                        }
                        val fg =
                            live.value.currentPackage == pkg &&
                                (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                                    .isInteractive
                        if (fg) {
                            segments += Segment(now)
                            writer?.append("B|$now")
                            live.update {
                                it.copy(
                                    recording = "recording",
                                    sessionId = item.id,
                                    effectiveNs = 0,
                                    recordedApp = name,
                                    message = "正在记录 $name；切换应用时自动保存",
                                )
                            }
                            toast("已开始记录 · $name")
                        } else {
                            live.update {
                                it.copy(
                                    recording = "auto_paused",
                                    sessionId = item.id,
                                    effectiveNs = 0,
                                    recordedApp = name,
                                )
                            }
                            stopRecording()
                        }
                    }
                } catch (e: Exception) {
                    live.update { it.copy(recording = "idle", message = "无法建立记录：${e.message}") }
                    toast("记录启动失败")
                    reconcile()
                }
            }
    }

    fun pause() {
        synchronized(lock) {
            if (live.value.recording == "recording" || live.value.recording == "auto_paused")
                pauseLocked("manual_paused", "手动暂停", SystemClock.elapsedRealtimeNanos())
        }
    }

    private fun pauseLocked(state: String, reason: String, now: Long) {
        segments.lastOrNull { it.end == Long.MAX_VALUE }?.end = now
        writer?.append("P|$now|$reason")
        updateDuration()
        live.update { it.copy(recording = state, message = reason) }
    }

    fun resume() {
        synchronized(lock) {
            if (live.value.recording == "manual_paused") {
                if (live.value.latest?.foreground == true)
                    resumeLocked(SystemClock.elapsedRealtimeNanos())
                else live.update { it.copy(recording = "auto_paused", message = "返回目标游戏后继续") }
            }
        }
    }

    private fun resumeLocked(now: Long) {
        segments += Segment(now)
        writer?.append("B|$now")
        live.update { it.copy(recording = "recording", message = "记录继续") }
    }

    fun mark() {
        event(
            Event(
                SystemClock.elapsedRealtimeNanos(),
                "mark",
                "标记 ${java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.CHINA).format(java.util.Date())}",
            )
        )
    }

    fun stopRecording(
        status: String = "complete",
        atNs: Long = SystemClock.elapsedRealtimeNanos(),
    ): Job =
        synchronized(lock) {
            savingJob?.let {
                return@synchronized it
            }
            if (live.value.recording == "armed") {
                live.update { it.copy(recording = "idle", message = "已取消等待记录") }
                reconcile()
                return@synchronized scope.launch {}
            }
            val current = session ?: return@synchronized scope.launch {}
            segments.lastOrNull { it.end == Long.MAX_VALUE }?.end = atNs
            updateDuration()
            writer?.append("P|$atNs|结束记录")
            live.update { it.copy(recording = "saving", message = "正在结算尾帧并保存") }
            val item = current.copy(endNs = atNs, status = status)
            scope
                .launch(start = CoroutineStart.LAZY) {
                    delay(1500)
                    synchronized(lock) {
                        writer?.append("E|${item.endNs}|end|$status")
                        writer?.close()
                        writer = null
                        session = null
                        segments.clear()
                        live.update {
                            it.copy(
                                recording = "idle",
                                sessionId = null,
                                effectiveNs = 0,
                                recordedApp = "",
                                message = if (status == "complete") "记录已保存" else "已保存中断前的数据",
                            )
                        }
                    }
                    repo.dao.update(item)
                    repo.metadata(item.id).writeText(repo.sessionJson(item).toString(2))
                    toast(
                        if (status == "complete") "记录已保存 · ${item.appName}"
                        else "中断记录已保存 · ${item.appName}"
                    )
                    synchronized(lock) { savingJob = null }
                    reconcile()
                }
                .also {
                    savingJob = it
                    it.start()
                }
        }

    suspend fun exit() {
        preparingJob?.join()
        if (session != null || live.value.recording == "armed") stopRecording().join()
        live.update { it.copy(preview = false, small = false, osd = false) }
        lifecycle.withLock { stopCollector() }
    }
}
