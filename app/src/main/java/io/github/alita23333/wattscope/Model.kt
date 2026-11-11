package io.github.alita23333.wattscope

import java.util.Locale
import kotlin.math.ceil
import org.json.JSONObject

data class Metric(
    val id: String,
    val label: String,
    val unit: String,
    val source: String = "",
    val parent: String = "",
)

data class Hardware(
    val ns: Long,
    val foreground: Boolean,
    val charging: String,
    val values: Map<String, Double?>,
    val sources: Map<String, String> = emptyMap(),
    val issues: Map<String, MetricIssue> = emptyMap(),
) {
    companion object {
        fun parse(line: String): Hardware {
            val p = line.split('|', limit = 7)
            val j = JSONObject(p[4])
            val src = if (p.size > 5) JSONObject(p[5]) else JSONObject()
            val issues = if (p.size > 6) JSONObject(p[6]) else JSONObject()
            return Hardware(
                p[1].toLong(),
                p[2] == "1",
                p[3],
                j.keys().asSequence().associateWith {
                    if (j.isNull(it)) null else j.optDouble(it).takeIf(Double::isFinite)
                },
                src.keys().asSequence().associateWith { src.getString(it) },
                issues.keys().asSequence().associateWith { key ->
                    val issue = issues.getJSONObject(key)
                    MetricIssue(issue.getString("state"), issue.getString("reason"))
                },
            )
        }
    }
}

data class MetricIssue(val state: String, val reason: String)

data class Frame(val ns: Long, val number: Long, val layer: String, val generation: Int = 0)

data class Segment(val start: Long, var end: Long = Long.MAX_VALUE)

data class Event(val ns: Long, val kind: String, val message: String)

data class MetricHealth(
    val state: String,
    val lastSuccessNs: Long = 0,
    val failures: Long = 0,
    val received: Long = 0,
    val reason: String = "",
)

data class LiveState(
    val root: String = "尚未连接",
    val collector: Boolean = false,
    val preview: Boolean = false,
    val small: Boolean = false,
    val osd: Boolean = false,
    val controlPanelVisible: Boolean = false,
    val recording: String = "idle",
    val sessionId: String? = null,
    val effectiveNs: Long = 0,
    val latest: Hardware? = null,
    val fps: Double? = null,
    val frameMs: Double? = null,
    val metrics: List<Metric> = emptyList(),
    val health: Map<String, MetricHealth> = emptyMap(),
    val events: List<Event> = emptyList(),
    val currentPackage: String = "",
    val currentApp: String = "",
    val currentRecordable: Boolean = false,
    val recordedApp: String = "",
    val message: String = "开启记录小窗，切到应用后点一下开始记录",
) {
    val captureRequested: Boolean
        get() = preview || small || osd || recording != "idle"

    fun withMonitoring(enabled: Boolean): LiveState =
        if (enabled) copy(preview = true) else copy(preview = false, small = false, osd = false)
}

data class PowerStats(
    val average: Double?,
    val peak: Double?,
    val energyJ: Double?,
    val coverageSeconds: Double,
)

data class FrameBaseline(
    val budgetMs: Double? = null,
    val observedSeconds: Double = 0.0,
    val intervalCount: Int = 0,
) {
    val fps: Double?
        get() = budgetMs?.takeIf { it.isFinite() && it > 0 }?.let { 1000.0 / it }

    fun description(): String =
        if (fps != null)
            String.format(
                Locale.US,
                "Baseline %.1f FPS · Slow >%.2f ms · Severe >%.2f ms",
                fps,
                budgetMs!! * 1.5,
                budgetMs * 3,
            )
        else "基线不足 · 需累计 10 秒有效帧数据且至少 60 个帧间隔"
}

/** One baseline per recording. Pause/layer/reconnect boundaries never become frame intervals. */
class FrameCalibration {
    private val intervals = ArrayList<Double>()
    private var observedNs = 0L
    private var previous: Frame? = null

    fun boundary() {
        previous = null
    }

    fun add(frame: Frame) {
        if (observedNs >= WINDOW_NS && intervals.size >= MIN_INTERVALS) return
        val before = previous
        if (
            before != null && before.layer == frame.layer && before.generation == frame.generation
        ) {
            val dt = frame.ns - before.ns
            if (dt <= 0) return
            intervals += dt / 1e6
            observedNs += dt
        }
        previous = frame
    }

    fun result(): FrameBaseline {
        val ready = observedNs >= WINDOW_NS && intervals.size >= MIN_INTERVALS
        val median =
            if (ready) {
                val sorted = intervals.sorted()
                (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
            } else null
        return FrameBaseline(median, observedNs / 1e9, intervals.size)
    }

    companion object {
        const val WINDOW_NS = 10_000_000_000L
        const val MIN_INTERVALS = 60
    }
}

data class Summary(
    val seconds: Double = 0.0,
    val frameCount: Long = 0,
    val avgFps: Double? = null,
    val low1: Double? = null,
    val low5: Double? = null,
    val p95: Double? = null,
    val p99: Double? = null,
    val slow: Long? = null,
    val severe: Long? = null,
    val frameBaseline: FrameBaseline = FrameBaseline(),
    val systemJank: Long? = null,
    val systemJankTypes: Map<Long, Long> = emptyMap(),
    val powers: Map<String, PowerStats> = emptyMap(),
)

data class Series(val id: String, val points: List<Pair<Double, Double?>>) {
    fun visible(left: Double, right: Double): List<Pair<Double, Double?>> {
        fun bound(time: Double, upper: Boolean): Int {
            var lo = 0
            var hi = points.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (points[mid].first < time || upper && points[mid].first == time) lo = mid + 1
                else hi = mid
            }
            return lo
        }
        return points.subList(bound(left, false), bound(right, true))
    }

    fun nearest(time: Double): Pair<Double, Double?>? {
        if (points.isEmpty()) return null
        var lo = 0
        var hi = points.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (points[mid].first < time) lo = mid + 1 else hi = mid
        }
        val a = points.getOrNull(lo)
        val b = points.getOrNull(lo - 1)
        if (time < points.first().first || time > points.last().first) return null
        if (
            a != null &&
                b != null &&
                time > b.first &&
                time < a.first &&
                (a.second == null || b.second == null)
        )
            return time to null
        return if (a == null) b else if (b == null || a.first - time <= time - b.first) a else b
    }
}

object PlotData {
    /** Keep extrema and explicit missing markers; called after selecting the visible time span. */
    fun decimate(
        points: List<Pair<Double, Double?>>,
        target: Int = 1800,
    ): List<Pair<Double, Double?>> {
        if (points.size <= target) return points
        val bucket =
            kotlin.math.ceil(points.size.toDouble() / (target / 6).coerceAtLeast(1)).toInt()
        val result = ArrayList<Pair<Double, Double?>>()
        var start = 0
        while (start < points.size) {
            val end = minOf(points.size, start + bucket)
            val indices = sortedSetOf(start, end - 1)
            var minIndex = -1
            var maxIndex = -1
            var firstMissing = -1
            var lastMissing = -1
            for (i in start until end) {
                val value = points[i].second
                if (value == null || !value.isFinite()) {
                    if (firstMissing < 0) firstMissing = i
                    lastMissing = i
                    if (
                        i == start ||
                            points[i - 1].second != null ||
                            i == end - 1 ||
                            points[i + 1].second != null
                    )
                        indices += i
                } else {
                    if (minIndex < 0 || value < points[minIndex].second!!) minIndex = i
                    if (maxIndex < 0 || value > points[maxIndex].second!!) maxIndex = i
                }
            }
            listOf(minIndex, maxIndex, firstMissing, lastMissing)
                .filter { it >= 0 }
                .forEach { indices += it }
            indices.forEach { result += points[it] }
            start = end
        }
        return result
    }

    /** Insert a missing point at real gaps before any rendering reduction. */
    fun markGaps(
        points: List<Pair<Double, Double?>>,
        maximumStep: Double,
        boundaries: List<Double> = emptyList(),
    ): List<Pair<Double, Double?>> {
        val result = ArrayList<Pair<Double, Double?>>(points.size)
        var previous: Double? = null
        for (point in points) {
            val last = previous
            if (
                last != null &&
                    (point.first - last > maximumStep ||
                        boundaries.any { it > last && it <= point.first })
            )
                result += (last + point.first) / 2 to null
            result += point
            previous = point.first
        }
        return result
    }
}

data class RecordedQuality(
    val valid: Long = 0,
    val missing: Long = 0,
    val lastSuccessNs: Long = 0,
    val readFailures: Long = 0,
    val unsupported: Long = 0,
    val reason: String = "",
)

data class Analysis(
    val summary: Summary,
    val series: List<Series>,
    val events: List<Event>,
    val segments: List<Segment>,
    val firstNs: Long,
    val lastNs: Long,
    val hardwareCount: Long,
    val damaged: Boolean = false,
    val quality: Map<String, RecordedQuality> = emptyMap(),
    val descriptors: Map<String, Metric> = emptyMap(),
)

object Metrics {
    // AOSP FrameTimelineEvent.JankType is a bitmask; multiple causes may share a frame.
    fun jankLabel(mask: Long): String {
        if (mask == 0L) return "未分类"
        if (mask == 1L) return "无系统 Jank"
        val names =
            linkedMapOf(
                2L to "SF 调度",
                4L to "预测误差",
                8L to "显示 HAL",
                16L to "SF CPU 超期",
                32L to "SF GPU 超期",
                64L to "应用超期",
                128L to "缓冲区堆积",
                256L to "未知原因",
                512L to "SF 堆积",
                1024L to "丢弃帧",
            )
        val known = names.filterKeys { mask and it != 0L }.values.toMutableList()
        val unknown = mask and 2047L.inv()
        if (unknown != 0L) known += "其他 0x${unknown.toString(16)}"
        return known.joinToString(" + ")
    }

    fun precise(id: String, value: Double?): String =
        if (value == null || !value.isFinite()) "—"
        else if (id == "fps" || id == "frame.ms") String.format(Locale.US, "%.2f", value)
        else format(id, value)

    fun cpuPower(metrics: List<Metric>, values: Map<String, Double?>): Double? {
        val parent = metrics.firstOrNull { it.id == "qpt.cpu" }
        if (parent != null) return values[parent.id]
        val rails = metrics.filter { it.id.startsWith("qpt.cpu-") && !it.id.endsWith(".energy") }
        if (
            rails.isEmpty() ||
                rails.map { it.parent }.distinct().size != 1 ||
                rails.any { values[it.id] == null }
        )
            return null
        return rails.sumOf { values[it.id]!! }
    }

    fun label(id: String): String =
        when (id) {
            "fps" -> "FPS"
            "frame.ms" -> "Frame time"
            "battery.power" -> "Battery power"
            "battery.current" -> "Battery current"
            "battery.voltage" -> "Battery voltage"
            "battery.capacity" -> "Battery level"
            "qpt.soc" -> "SoC power"
            "cpu.usage" -> "CPU usage"
            "gpu.usage" -> "GPU usage"
            "gpu.freq" -> "GPU freq"
            "temp.cpu" -> "CPU temp"
            "temp.gpu" -> "GPU temp"
            "temp.soc" -> "SoC temp"
            "temp.battery" -> "Battery temp"
            "ram.used" -> "RAM used"
            "ram.total" -> "RAM total"
            "ram.percent" -> "RAM usage"
            "ddr.freq" -> "DDR freq"
            else ->
                when {
                    id.startsWith("qpt.") ->
                        id.removePrefix("qpt.").removeSuffix(".energy") +
                            if (id.endsWith(".energy")) " energy" else " power"
                    id.startsWith("cpu.cluster") ->
                        "CPU cluster " +
                            id.removePrefix("cpu.cluster").substringBefore('.') +
                            if (id.endsWith(".freq")) " freq" else " usage"
                    id.startsWith("cpu.") ->
                        "CPU " +
                            id.removePrefix("cpu.").substringBefore('.') +
                            when {
                                id.endsWith(".freq") -> " freq"
                                id.endsWith(".online") -> " online"
                                else -> " usage"
                            }
                    else -> id
                }
        }

    fun unit(id: String) =
        when {
            id == "fps" -> "FPS"
            id == "frame.ms" -> "ms"
            id.endsWith(".energy") -> "J"
            id.startsWith("qpt.") || id == "battery.power" -> "W"
            id.endsWith(".freq") -> "MHz"
            id.startsWith("temp.") -> "°C"
            id == "battery.current" -> "A"
            id == "battery.voltage" -> "V"
            id == "ram.used" || id == "ram.total" -> "GiB"
            id.endsWith(".online") -> ""
            else -> "%"
        }

    fun format(id: String, v: Double?): String {
        if (v == null || !v.isFinite()) return "—"
        val digits =
            when (unit(id)) {
                "W",
                "J",
                "GiB",
                "A",
                "V" -> 2
                "MHz",
                "%",
                "FPS" -> 0
                else -> 1
            }
        return String.format(Locale.US, "%.${digits}f", v)
    }

    val defaultOsd =
        listOf(
            "fps",
            "battery.power",
            "qpt.soc",
            "temp.soc",
            "temp.battery",
            "ram.percent",
            "ddr.freq",
        )
}

object Statistics {
    const val ALGORITHM_VERSION = 5

    fun recordEnd(recordedEnd: Long, lastCommitted: Long, interrupted: Boolean): Long =
        if (interrupted || recordedEnd <= 0) maxOf(recordedEnd, lastCommitted) else recordedEnd

    fun systemJank(types: Map<Long, Long>): Long? =
        if (types.keys.none { it != 0L }) null
        else types.filterKeys { it and 1L.inv() != 0L }.values.sum()

    fun calibrate(times: List<List<Long>>): FrameBaseline {
        val calibration = FrameCalibration()
        times.forEach { group ->
            calibration.boundary()
            group.distinct().sorted().forEach { calibration.add(Frame(it, 0, "")) }
        }
        return calibration.result()
    }

    fun frameStats(times: List<List<Long>>, baseline: FrameBaseline = calibrate(times)): Summary {
        val deltas = ArrayList<Double>()
        var count = 0L
        times.forEach { input ->
            val t = input.distinct().sorted()
            count += t.size
            for (i in 1 until t.size) if (t[i] > t[i - 1]) deltas += (t[i] - t[i - 1]) / 1e6
        }
        if (deltas.isEmpty()) return Summary(frameCount = count, frameBaseline = baseline)
        val sorted = deltas.toDoubleArray().apply { sort() }
        fun low(p: Double): Double {
            val n = ceil(sorted.size * p).toInt().coerceAtLeast(1)
            var sum = 0.0
            for (i in sorted.size - n until sorted.size) sum += sorted[i]
            return 1000.0 * n / sum
        }
        fun quantile(p: Double) =
            sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(sorted.indices)]
        val budget = baseline.budgetMs?.takeIf { it.isFinite() && it > 0 }
        return Summary(
            frameCount = count,
            avgFps = 1000.0 / deltas.average(),
            low1 = low(.01),
            low5 = low(.05),
            p95 = quantile(.95),
            p99 = quantile(.99),
            slow = budget?.let { b -> deltas.count { it > b * 1.5 }.toLong() },
            severe = budget?.let { b -> deltas.count { it > b * 3 }.toLong() },
            frameBaseline = baseline,
        )
    }

    fun power(samples: List<Hardware>, id: String, intervalMs: Int): PowerStats {
        var energy = 0.0
        var seconds = 0.0
        var peak: Double? = null
        samples.forEach { h ->
            (if (id == "battery.power") BatteryPower.recorded(h) else h.values[id])
                ?.takeIf { it >= 0 && (id != "battery.power" || h.charging == "Discharging") }
                ?.let { peak = peak?.coerceAtLeast(it) ?: it }
        }
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = (b.ns - a.ns) / 1e9
            if (dt <= 0 || dt > intervalMs / 1000.0 * 2.5) continue
            if (
                id == "battery.power" &&
                    (a.charging != "Discharging" || b.charging != "Discharging")
            )
                continue
            val ea = a.values["$id.energy"]
            val eb = b.values["$id.energy"]
            val e =
                if (id.startsWith("qpt.") && ea != null && eb != null) {
                    if (ea < 0 || eb < ea) continue
                    eb - ea
                } else {
                    val p =
                        (if (id == "battery.power") BatteryPower.recorded(a) else a.values[id])
                            ?: continue
                    val q =
                        (if (id == "battery.power") BatteryPower.recorded(b) else b.values[id])
                            ?: continue
                    if (p < 0 || q < 0) continue
                    (p + q) * .5 * dt
                }
            energy += e
            seconds += dt
        }
        return PowerStats(
            if (seconds > 0) energy / seconds else null,
            peak,
            if (seconds > 0) energy else null,
            seconds,
        )
    }
}
