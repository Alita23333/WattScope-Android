package io.github.alita23333.wattscope

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import java.io.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

object Transfer {
    private fun output(repo: Repository, name: String) =
        File(repo.context.cacheDir, "exports")
            .apply { mkdirs() }
            .let {
                File(
                    it,
                    name.substringBeforeLast('.') +
                        "-" +
                        UUID.randomUUID().toString().take(8) +
                        "." +
                        name.substringAfterLast('.'),
                )
            }

    private fun hash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val b = ByteArray(65536)
            while (true) {
                val n = input.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun backup(repo: Repository, s: SessionEntity): File {
        check(s.status in listOf("complete", "interrupted", "imported")) { "请先结束记录" }
        val ranges = repo.dao.getRanges(s.id)
        val meta =
            repo.sessionJson(s).apply {
                put("sha256", hash(repo.file(s.id)))
                put(
                    "ranges",
                    JSONArray().apply {
                        ranges.forEach {
                            put(
                                JSONObject()
                                    .put("name", it.name)
                                    .put("startNs", it.startNs)
                                    .put("endNs", it.endNs)
                            )
                        }
                    },
                )
            }
        val f = output(repo, "WattScope-${s.id.take(8)}.wsn.zip")
        ZipOutputStream(BufferedOutputStream(f.outputStream())).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(meta.toString(2).toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("record.wsn"))
            repo.file(s.id).inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
        return f
    }

    suspend fun importRecord(repo: Repository, input: InputStream): String {
        val staging = File(repo.context.cacheDir, "import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var total = 0L
            val seen = mutableSetOf<String>()
            ZipInputStream(BufferedInputStream(input)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    check(e.name in listOf("manifest.json", "record.wsn") && seen.add(e.name)) {
                        "记录包包含未知或重复项目"
                    }
                    val f = File(staging, e.name)
                    f.outputStream().use { out ->
                        val b = ByteArray(65536)
                        while (true) {
                            val n = z.read(b)
                            if (n < 0) break
                            total += n
                            check(total <= 1024L * 1024 * 1024) { "记录包超过 1 GiB 上限" }
                            out.write(b, 0, n)
                        }
                    }
                    z.closeEntry()
                }
            }
            val metaFile = File(staging, "manifest.json")
            check(metaFile.length() in 1..1_048_576) { "元信息无效" }
            val meta = JSONObject(metaFile.readText())
            check(
                meta.getInt("formatVersion") == 1 &&
                    meta.getInt("algorithmVersion") in 1..Statistics.ALGORITHM_VERSION
            ) {
                "不支持的记录格式或算法版本"
            }
            val raw = File(staging, "record.wsn")
            check(raw.isFile && hash(raw) == meta.getString("sha256")) { "记录内容校验失败" }
            check(Blocks.read(raw) {}) { "记录块损坏或不完整" }
            val s = repo.sessionFromJson(meta)
            check(
                s.endNs >= s.startNs &&
                    s.intervalMs in listOf(100, 200, 500, 1000) &&
                    s.targetFps in 1..1000
            ) {
                "记录参数无效"
            }
            raw.copyTo(repo.file(s.id), overwrite = false)
            repo
                .metadata(s.id)
                .writeText(
                    repo
                        .sessionJson(s)
                        .put("importedFrom", meta.getString("id"))
                        .put("sourceAlgorithmVersion", meta.getInt("algorithmVersion"))
                        .toString(2)
                )
            try {
                repo.db.withTransaction {
                    repo.dao.insert(s)
                    val ranges = meta.optJSONArray("ranges") ?: JSONArray()
                    for (i in 0 until ranges.length()) {
                        val r = ranges.getJSONObject(i)
                        val a = r.getLong("startNs")
                        val b = r.getLong("endNs")
                        check(a >= s.startNs && b <= s.endNs && b > a) { "区间无效" }
                        repo.dao.insertRange(
                            RangeEntity(
                                sessionId = s.id,
                                name = r.getString("name"),
                                startNs = a,
                                endNs = b,
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                repo.file(s.id).delete()
                repo.metadata(s.id).delete()
                throw e
            }
            return s.id
        } finally {
            staging.listFiles()?.forEach { it.delete() }
            staging.delete()
        }
    }

    fun csv(repo: Repository, s: SessionEntity, range: LongRange? = null): File {
        val a = repo.analyze(s, range)
        val metricSources = a.descriptors.mapValues { it.value.source }
        fun effective(t: Long) =
            a.segments.sumOf { g -> maxOf(0L, minOf(g.end, t) - g.start) } / 1e9
        val tmp = File(repo.context.cacheDir, "csv-${UUID.randomUUID()}").apply { mkdirs() }
        val hw = File(tmp, "hardware.csv")
        val frames = File(tmp, "frames.csv")
        val events = File(tmp, "events.csv")
        fun cell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        hw.bufferedWriter().use { h ->
            frames.bufferedWriter().use { f ->
                events.bufferedWriter().use { e ->
                    h.write(
                        "boottime_ns,elapsed_seconds,effective_elapsed_seconds,charging,metric_id,value,unit,source,quality,reason\n"
                    )
                    f.write(
                        "boottime_ns,elapsed_seconds,effective_elapsed_seconds,frame_number,layer_id,interval_ms\n"
                    )
                    e.write("boottime_ns,kind,message\n")
                    var previousFrameNs: Long? = null
                    var previousLayer = ""
                    Blocks.read(repo.file(s.id)) { line ->
                        val p = line.split('|', limit = 6)
                        val t = p.getOrNull(1)?.toLongOrNull()
                        if (
                            p[0] in listOf("B", "P") ||
                                p[0] == "E" &&
                                    p.getOrNull(2) in listOf("collector", "layer", "frame_error")
                        )
                            previousFrameNs = null
                        if (t != null && (range == null || t in range)) {
                            when (p[0]) {
                                "H" -> {
                                    val a = Hardware.parse(line).forRecording()
                                    val activeElapsed = effective(a.ns)
                                    a.values.forEach { (id, v) ->
                                        h.write(
                                            "${a.ns},${(a.ns-s.startNs)/1e9},${activeElapsed},${cell(a.charging)},${cell(id)},${v?:""},${cell(Metrics.unit(id))},${cell(listOfNotNull(metricSources[id], a.sources[id]).distinct().joinToString(" · "))},${if(v==null)a.issues[id]?.state?:"missing" else "valid"},${cell(a.issues[id]?.reason?:"")}\n"
                                        )
                                    }
                                }
                                "F" -> {
                                    val layer = p.getOrElse(3) { "" }
                                    val previous = previousFrameNs
                                    val interval =
                                        if (
                                            previous != null &&
                                                previousLayer == layer &&
                                                t > previous &&
                                                a.segments.any {
                                                    previous >= it.start && t <= it.end
                                                }
                                        )
                                            (t - previous) / 1e6
                                        else null
                                    f.write(
                                        "$t,${(t-s.startNs)/1e9},${effective(t)},${p[2]},${cell(layer)},${interval?:""}\n"
                                    )
                                    previousFrameNs = t
                                    previousLayer = layer
                                }
                                "E" -> e.write("$t,${cell(p[2])},${cell(p.getOrElse(3){""})}\n")
                                "J" ->
                                    e.write(
                                        "$t,${cell("system_jank")},${cell("mask=${p[2]}; ${Metrics.jankLabel(p[2].toLong())}")}\n"
                                    )
                                else ->
                                    e.write(
                                        "$t,${cell(p[0])},${cell(p.drop(2).joinToString("|"))}\n"
                                    )
                            }
                        }
                    }
                }
            }
        }
        File(tmp, "metrics.csv").bufferedWriter().use { out ->
            out.write("metric_id,label,unit,source,parent\n")
            a.descriptors.values.forEach { metric ->
                out.write(
                    listOf(
                            metric.id,
                            Metrics.label(metric.id),
                            metric.unit,
                            metric.source,
                            metric.parent,
                        )
                        .joinToString(",") { cell(it) } + "\n"
                )
            }
        }
        File(tmp, "summary.json")
            .writeText(
                summaryJson(a.summary)
                    .put("clock", "CLOCK_BOOTTIME")
                    .put("rawDamaged", a.damaged)
                    .put(
                        "activeSegments",
                        JSONArray().apply {
                            a.segments.forEach {
                                put(JSONObject().put("startNs", it.start).put("endNs", it.end))
                            }
                        },
                    )
                    .put("session", repo.sessionJson(s))
                    .put("rangeStartNs", a.firstNs)
                    .put("rangeEndNs", a.lastNs)
                    .toString(2)
            )
        val out = output(repo, "WattScope-${s.id.take(8)}-csv.zip")
        ZipOutputStream(out.outputStream()).use { z ->
            tmp.listFiles()!!.forEach { file ->
                z.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        tmp.listFiles()?.forEach { it.delete() }
        tmp.delete()
        return out
    }

    fun summaryFromJson(j: JSONObject): Summary {
        fun value(key: String): Double? =
            if (j.isNull(key)) null else j.optDouble(key).takeIf { it.isFinite() }
        val powers = j.optJSONObject("power") ?: JSONObject()
        val calibration = j.optJSONObject("frameBaseline")
        return Summary(
            seconds = j.getDouble("seconds"),
            frameCount = j.getLong("frameCount"),
            avgFps = value("avgFps"),
            low1 = value("low1"),
            low5 = value("low5"),
            p95 = value("p95Ms"),
            p99 = value("p99Ms"),
            slow = if (j.isNull("slowFrames")) null else j.getLong("slowFrames"),
            severe = if (j.isNull("severeFrames")) null else j.getLong("severeFrames"),
            frameBaseline =
                FrameBaseline(
                    calibration?.let {
                        if (it.isNull("budgetMs")) null else it.getDouble("budgetMs")
                    },
                    calibration?.optDouble("observedSeconds", 0.0) ?: 0.0,
                    calibration?.optInt("intervalCount", 0) ?: 0,
                ),
            systemJank = if (j.isNull("systemJank")) null else j.getLong("systemJank"),
            systemJankTypes =
                j.optJSONObject("systemJankTypes")?.let { types ->
                    types.keys().asSequence().associate { it.toLong() to types.getLong(it) }
                } ?: emptyMap(),
            powers =
                powers.keys().asSequence().associateWith { key ->
                    val p = powers.getJSONObject(key)
                    fun optional(name: String) =
                        if (p.isNull(name)) null else p.optDouble(name).takeIf { it.isFinite() }
                    PowerStats(
                        optional("averageW"),
                        optional("peakW"),
                        optional("energyJ"),
                        p.getDouble("coverageSeconds"),
                    )
                },
        )
    }

    fun summaryJson(s: Summary) =
        JSONObject().apply {
            put("algorithmVersion", Statistics.ALGORITHM_VERSION)
            put("batteryPowerPolicy", BatteryPower.POLICY)
            put("seconds", s.seconds)
            put("frameCount", s.frameCount)
            put("avgFps", s.avgFps)
            put("low1", s.low1)
            put("low5", s.low5)
            put("p95Ms", s.p95)
            put("p99Ms", s.p99)
            put("slowFrames", s.slow ?: JSONObject.NULL)
            put("severeFrames", s.severe ?: JSONObject.NULL)
            put(
                "frameBaseline",
                JSONObject()
                    .put("method", "median-first-10s-v1")
                    .put("status", if (s.frameBaseline.fps != null) "ready" else "insufficient")
                    .put("windowSeconds", FrameCalibration.WINDOW_NS / 1e9)
                    .put("normalFps", s.frameBaseline.fps ?: JSONObject.NULL)
                    .put("budgetMs", s.frameBaseline.budgetMs ?: JSONObject.NULL)
                    .put("observedSeconds", s.frameBaseline.observedSeconds)
                    .put("intervalCount", s.frameBaseline.intervalCount),
            )
            put("systemJank", s.systemJank)
            put(
                "systemJankTypes",
                JSONObject().apply {
                    s.systemJankTypes.forEach { (mask, count) -> put(mask.toString(), count) }
                },
            )
            put(
                "power",
                JSONObject().apply {
                    s.powers.forEach { (k, v) ->
                        put(
                            k,
                            JSONObject()
                                .put("averageW", v.average)
                                .put("peakW", v.peak)
                                .put("energyJ", v.energyJ)
                                .put("coverageSeconds", v.coverageSeconds),
                        )
                    }
                },
            )
        }

    fun image(repo: Repository, s: SessionEntity, range: LongRange? = null): File {
        val a = repo.analyze(s, range)
        val bitmap = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(Color.rgb(14, 20, 29))
        fun text(t: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE) {
            p.color = color
            p.textSize = size
            p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            c.drawText(t, x, y, p)
        }
        val accent = Color.rgb(121, 221, 200)
        text("WATT / SCOPE NEXT", 60f, 80f, 28f, accent)
        text(s.title.take(28), 60f, 155f, 48f)
        text(s.device.take(70), 60f, 205f, 22f, Color.LTGRAY)
        text(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(s.startWall)) +
                " · 有效 %.1f 分钟".format(a.summary.seconds / 60),
            60f,
            245f,
            24f,
            Color.LTGRAY,
        )
        val items =
            listOf(
                "AVG FPS" to Metrics.precise("fps", a.summary.avgFps),
                "1% LOW" to Metrics.precise("fps", a.summary.low1),
                "5% LOW" to Metrics.precise("fps", a.summary.low5),
                "P99 ms" to Metrics.precise("frame.ms", a.summary.p99),
                "SoC AVG W" to Metrics.format("qpt.soc", a.summary.powers["qpt.soc"]?.average),
                "Battery AVG W" to
                    Metrics.format("battery.power", a.summary.powers["battery.power"]?.average),
                "Slow frames" to (a.summary.slow?.toString() ?: "基线不足"),
                "Duration (s)" to "%.1f".format(a.summary.seconds),
            )
        items.forEachIndexed { i, (label, value) ->
            val x = 60f + (i % 4) * 285
            val y = 320f + (i / 4) * 125
            text(label, x, y, 22f, Color.LTGRAY)
            text(value, x, y + 57, 44f, accent)
        }
        var top = 610f
        for (id in listOf("fps", "qpt.soc", "battery.power", "temp.soc")) {
            val series = a.series.firstOrNull { it.id == id }
            text(Metrics.label(id) + " (${Metrics.unit(id)})", 60f, top, 25f)
            val pts = PlotData.decimate(series?.points.orEmpty())
            val values = pts.mapNotNull { it.second }
            if (values.isNotEmpty()) {
                val max = (values.maxOrNull() ?: 1.0).coerceAtLeast(.1) * 1.1
                val min =
                    if (id.startsWith("temp")) (values.minOrNull() ?: 0.0) - 2
                    else minOf(0.0, (values.minOrNull() ?: 0.0) * 1.1)
                val x0 = (a.firstNs - s.startNs) / 1e9
                val span = ((a.lastNs - a.firstNs) / 1e9).coerceAtLeast(.001)
                var prev: Pair<Float, Float>? = null
                p.color = accent
                p.strokeWidth = 1.5f
                for ((t, v) in pts) {
                    if (v == null || t < x0 || t > x0 + span) {
                        prev = null
                        continue
                    }
                    val x = 60f + ((t - x0) / span * 1080).toFloat()
                    val y = top + 190 - ((v - min) / (max - min) * 155).toFloat()
                    prev?.let { c.drawLine(it.first, it.second, x, y, p) }
                    prev = x to y
                }
                text("%.2f".format(max), 61f, top + 40, 18f, Color.GRAY)
                text("%.2f".format(min), 61f, top + 190, 18f, Color.GRAY)
                text(elapsedTime(x0), 60f, top + 215, 18f, Color.GRAY)
                val endLabel = elapsedTime(x0 + span)
                p.textSize = 18f
                text(endLabel, 1140f - p.measureText(endLabel), top + 215, 18f, Color.GRAY)
            } else text("无有效数据", 60f, top + 90, 26f, Color.GRAY)
            top += 260
        }
        text("目标游戏图层呈现帧率 · Low = 最慢帧间隔均值的倒数", 60f, 1690f, 21f, Color.LTGRAY)
        val f = output(repo, "WattScope-${s.id.take(8)}.png")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return f
    }

    fun comparisonCsv(
        repo: Repository,
        first: SessionEntity,
        rangeA: LongRange?,
        second: SessionEntity,
        rangeB: LongRange?,
    ): File {
        val a = repo.analyze(first, rangeA)
        val b = repo.analyze(second, rangeB)
        fun metadata(s: SessionEntity, range: LongRange?, analysis: Analysis) =
            repo
                .sessionJson(s)
                .put("selection", if (range == null) "full" else "range")
                .put("selectedStartNs", analysis.firstNs)
                .put("selectedEndNs", analysis.lastNs)
                .put("summary", summaryJson(analysis.summary))
                .put(
                    "activeSegments",
                    JSONArray().apply {
                        analysis.segments.forEach {
                            put(JSONObject().put("startNs", it.start).put("endNs", it.end))
                        }
                    },
                )
        val manifest =
            JSONObject()
                .put("formatVersion", 1)
                .put("algorithmVersion", Statistics.ALGORITHM_VERSION)
                .put("clock", "CLOCK_BOOTTIME")
                .put("alignment", "effective elapsed time within active segments")
                .put("A", metadata(first, rangeA, a))
                .put("B", metadata(second, rangeB, b))
        val rows =
            mutableListOf(
                Triple("avgFps", a.summary.avgFps to b.summary.avgFps, "FPS"),
                Triple("low1", a.summary.low1 to b.summary.low1, "FPS"),
                Triple("low5", a.summary.low5 to b.summary.low5, "FPS"),
                Triple("p95", a.summary.p95 to b.summary.p95, "ms"),
                Triple("p99", a.summary.p99 to b.summary.p99, "ms"),
            )
        (a.summary.powers.keys + b.summary.powers.keys).sorted().forEach { key ->
            rows +=
                Triple(key, a.summary.powers[key]?.average to b.summary.powers[key]?.average, "W")
        }
        val comparison = buildString {
            append("metric,A,B,B_minus_A,unit\n")
            rows.forEach { (key, pair, unit) ->
                val delta =
                    if (pair.first != null && pair.second != null) pair.second!! - pair.first!!
                    else null
                append("$key,${pair.first?:""},${pair.second?:""},${delta?:""},$unit\n")
            }
        }
        val result = output(repo, "WattScope-compare-${first.id.take(8)}-${second.id.take(8)}.zip")
        ZipOutputStream(result.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("comparison.json"))
            zip.write(manifest.toString(2).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("comparison.csv"))
            zip.write(comparison.toByteArray())
            zip.closeEntry()
            listOf(Triple(first, rangeA, "A-data.zip"), Triple(second, rangeB, "B-data.zip"))
                .forEach { (s, range, name) ->
                    // Copy before generating the next archive: A and B can be ranges of the same
                    // record.
                    val file = csv(repo, s, range)
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        return result
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val mime = if (file.extension == "png") "image/png" else "application/zip"
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "导出记录",
            )
        )
    }
}
