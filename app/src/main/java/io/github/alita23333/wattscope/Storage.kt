package io.github.alita23333.wattscope

import android.content.Context
import android.os.Build
import androidx.room.*
import java.io.*
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val packageName: String,
    val appName: String,
    val appVersion: String = "",
    val startWall: Long,
    val startNs: Long,
    val endNs: Long = 0,
    val status: String = "recording",
    val intervalMs: Int = 500,
    val targetFps: Int = 120,
    val favorite: Boolean = false,
    val note: String = "",
    val tags: String = "",
    val deleted: Boolean = false,
    val device: String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
)

@Entity(tableName = "ranges", indices = [Index("sessionId")])
data class RangeEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val name: String,
    val startNs: Long,
    val endNs: Long,
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE deleted=0 ORDER BY startWall DESC")
    fun watch(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id=:id") suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id=:id") fun observe(id: String): Flow<SessionEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(s: SessionEntity)

    @Update suspend fun update(s: SessionEntity)

    @Query(
        "UPDATE sessions SET status=:status,endNs=:lastNs WHERE id=:id AND status IN ('recording','auto_paused','manual_paused','saving')"
    )
    suspend fun checkpoint(id: String, lastNs: Long, status: String)

    @Query(
        "UPDATE sessions SET status='interrupted' WHERE status IN ('recording','paused','auto_paused','manual_paused','saving')"
    )
    suspend fun recover()

    @Query("SELECT * FROM ranges WHERE sessionId=:id ORDER BY startNs")
    fun ranges(id: String): Flow<List<RangeEntity>>

    @Query("SELECT * FROM ranges WHERE sessionId=:id ORDER BY startNs")
    suspend fun getRanges(id: String): List<RangeEntity>

    @Insert suspend fun insertRange(r: RangeEntity)

    @Query("DELETE FROM ranges WHERE id=:id") suspend fun deleteRange(id: String)
}

@Database(entities = [SessionEntity::class, RangeEntity::class], version = 1, exportSchema = false)
abstract class WattDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
}

class BlockWriter(file: File) : Closeable {
    private val stream = FileOutputStream(file, true)
    private val out = DataOutputStream(BufferedOutputStream(stream))
    private val pending = StringBuilder()
    private var last = System.nanoTime()

    @Synchronized
    fun append(line: String) {
        pending.append(line).append('\n')
        if (System.nanoTime() - last >= 1_000_000_000L) flush()
    }

    @Synchronized
    fun flush() {
        if (pending.isEmpty()) return
        val bytes = pending.toString().toByteArray(Charsets.UTF_8)
        out.writeInt(0x57534E31)
        out.writeInt(bytes.size)
        out.writeInt(CRC32().apply { update(bytes) }.value.toInt())
        out.write(bytes)
        out.flush()
        stream.fd.sync()
        pending.clear()
        last = System.nanoTime()
    }

    @Synchronized
    override fun close() {
        flush()
        out.close()
    }
}

object Blocks {
    fun read(file: File, onLine: (String) -> Unit): Boolean {
        if (!file.exists()) return false
        var intact = true
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            while (true) {
                val first = input.read()
                if (first < 0) break
                try {
                    val magic =
                        (first shl 24) or
                            (input.readUnsignedByte() shl 16) or
                            (input.readUnsignedByte() shl 8) or
                            input.readUnsignedByte()
                    val len = input.readInt()
                    val crc = input.readInt()
                    if (magic != 0x57534E31 || len !in 1..16_777_216) {
                        intact = false
                        break
                    }
                    val bytes = ByteArray(len)
                    input.readFully(bytes)
                    if (CRC32().apply { update(bytes) }.value.toInt() != crc) {
                        intact = false
                        break
                    }
                    bytes
                        .toString(Charsets.UTF_8)
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .forEach(onLine)
                } catch (_: EOFException) {
                    intact = false
                    break
                }
            }
        }
        return intact
    }
}

class Repository(val context: Context) {
    private val analysisGate = Any()
    private val analysisCache = linkedMapOf<String, java.lang.ref.WeakReference<Analysis>>()
    val db = Room.databaseBuilder(context, WattDatabase::class.java, "wattscope.db").build()
    val dao = db.sessions()
    val folder = File(context.filesDir, "records").apply { mkdirs() }

    fun file(id: String) = File(folder, "$id.wsn")

    fun metadata(id: String) = File(folder, "$id.json")

    fun sessionJson(s: SessionEntity) =
        JSONObject().apply {
            put("formatVersion", 1)
            put("algorithmVersion", Statistics.ALGORITHM_VERSION)
            put("id", s.id)
            put("title", s.title)
            put("packageName", s.packageName)
            put("appName", s.appName)
            put("appVersion", s.appVersion)
            put("startWall", s.startWall)
            put("startNs", s.startNs)
            put("endNs", s.endNs)
            put("status", s.status)
            put("intervalMs", s.intervalMs)
            put("targetFps", s.targetFps)
            put("favorite", s.favorite)
            put("note", s.note)
            put("tags", s.tags)
            put("device", s.device)
        }

    fun sessionFromJson(j: JSONObject, newId: String = UUID.randomUUID().toString()) =
        SessionEntity(
            id = newId,
            title = j.getString("title"),
            packageName = j.getString("packageName"),
            appName = j.getString("appName"),
            appVersion = j.optString("appVersion"),
            startWall = j.getLong("startWall"),
            startNs = j.getLong("startNs"),
            endNs = j.getLong("endNs"),
            status = "imported",
            intervalMs = j.getInt("intervalMs"),
            targetFps = j.getInt("targetFps"),
            favorite = j.optBoolean("favorite"),
            note = j.optString("note"),
            tags = j.optString("tags"),
            device = j.optString("device"),
        )

    fun cachedSummary(s: SessionEntity): Summary =
        synchronized(analysisGate) {
            val raw = file(s.id)
            val cache = File(folder, "${s.id}.summary.json")
            val cached =
                runCatching {
                        val j = JSONObject(cache.readText())
                        check(
                            j.getLong("rawBytes") == raw.length() &&
                                j.getLong("modified") == raw.lastModified() &&
                                j.getLong("endNs") == s.endNs &&
                                j.getInt("algorithmVersion") == Statistics.ALGORITHM_VERSION
                        )
                        Transfer.summaryFromJson(j.getJSONObject("summary"))
                    }
                    .getOrNull()
            if (cached != null) return@synchronized cached
            val result = analyze(s, null).summary
            cache.writeText(
                JSONObject()
                    .put("rawBytes", raw.length())
                    .put("modified", raw.lastModified())
                    .put("endNs", s.endNs)
                    .put("algorithmVersion", Statistics.ALGORITHM_VERSION)
                    .put("summary", Transfer.summaryJson(result))
                    .toString()
            )
            result
        }

    fun analyze(s: SessionEntity, range: LongRange? = null): Analysis =
        synchronized(analysisGate) {
            val raw = file(s.id)
            val key =
                listOf(
                        s.id,
                        s.startNs,
                        s.endNs,
                        s.intervalMs,
                        s.targetFps,
                        raw.length(),
                        raw.lastModified(),
                        range?.first,
                        range?.last,
                        Statistics.ALGORITHM_VERSION,
                    )
                    .joinToString(":")
            analysisCache[key]?.get()?.let {
                return@synchronized it
            }
            val result = analyzeRecord(s, range)
            analysisCache[key] = java.lang.ref.WeakReference(result)
            while (analysisCache.size > 8) analysisCache.remove(analysisCache.keys.first())
            result
        }

    private fun analyzeRecord(s: SessionEntity, range: LongRange? = null): Analysis {
        val hw = ArrayList<Hardware>()
        val frames = ArrayList<Frame>()
        val events = ArrayList<Event>()
        val seg = ArrayList<Segment>()
        val jankTypes = mutableMapOf<Long, Long>()
        var last = s.startNs
        val quality = mutableMapOf<String, RecordedQuality>()
        val descriptors = mutableMapOf<String, Metric>()
        val layerNames = mutableMapOf<String, String>()
        val atoms = mutableMapOf<String, String>()
        fun atom(value: String) = atoms.getOrPut(value) { value }
        var frameGeneration = 0
        val calibration = FrameCalibration()
        var calibrationSegment = -1
        val intact =
            Blocks.read(file(s.id)) { line ->
                try {
                    val p = line.split('|', limit = 5)
                    p.getOrNull(1)?.toLongOrNull()?.let { last = maxOf(last, it) }
                    when (p[0]) {
                        "H" -> {
                            val parsed = Hardware.parse(line).forRecording()
                            val h =
                                parsed.copy(
                                    charging = atom(parsed.charging),
                                    values = parsed.values.mapKeys { atom(it.key) },
                                    sources =
                                        parsed.sources.entries.associate {
                                            atom(it.key) to atom(it.value)
                                        },
                                    issues =
                                        parsed.issues.entries.associate {
                                            atom(it.key) to
                                                MetricIssue(
                                                    atom(it.value.state),
                                                    atom(it.value.reason),
                                                )
                                        },
                                )
                            last = maxOf(last, h.ns)
                            if (range == null || h.ns in range) {
                                hw += h
                                h.values.forEach { (id, v) ->
                                    val q = quality[id] ?: RecordedQuality()
                                    quality[id] =
                                        if (v == null)
                                            q.copy(
                                                missing = q.missing + 1,
                                                readFailures =
                                                    q.readFailures +
                                                        if (h.issues[id]?.state == "read_failed") 1
                                                        else 0,
                                                unsupported =
                                                    q.unsupported +
                                                        if (h.issues[id]?.state == "unsupported") 1
                                                        else 0,
                                                reason = h.issues[id]?.reason ?: q.reason,
                                            )
                                        else q.copy(valid = q.valid + 1, lastSuccessNs = h.ns)
                                }
                            }
                        }
                        "D" -> {
                            val d = line.split('|', limit = 6)
                            if (d.size >= 5)
                                descriptors[d[1]] =
                                    Metric(d[1], d[2], d[3], d[4], d.getOrElse(5) { "" })
                        }
                        "F" -> {
                            val t = p[1].toLong()
                            last = maxOf(last, t)
                            val frame =
                                Frame(
                                    t,
                                    p[2].toLong(),
                                    p.getOrElse(3) { "" }.let { layerNames.getOrPut(it) { it } },
                                    frameGeneration,
                                )
                            // Perfetto may deliver frames after P; use presentation time, not row
                            // order.
                            val segment = seg.indexOfLast { t >= it.start && t <= it.end }
                            if (segment >= 0) {
                                if (segment != calibrationSegment) calibration.boundary()
                                calibrationSegment = segment
                                calibration.add(frame)
                            }
                            if (range == null || t in range) frames += frame
                        }
                        "B" -> {
                            seg += Segment(p[1].toLong())
                        }
                        "P" -> {
                            seg.lastOrNull { it.end == Long.MAX_VALUE }?.end = p[1].toLong()
                            events += Event(p[1].toLong(), "pause", p.getOrElse(2) { "" })
                        }
                        "E" -> {
                            events += Event(p[1].toLong(), p[2], p.getOrElse(3) { "" })
                            if (p[2] in listOf("collector", "layer", "frame_error"))
                                frameGeneration++
                        }
                        "J" -> {
                            val t = p[1].toLong()
                            if (range == null || t in range) {
                                val type = p[2].toLong()
                                jankTypes[type] = (jankTypes[type] ?: 0L) + 1
                            }
                        }
                    }
                } catch (_: Exception) {
                    events += Event(last, "decode_error", "有一行数据无法解析")
                }
            }
        val recordEnd =
            Statistics.recordEnd(
                s.endNs,
                last,
                s.status == "interrupted" || seg.any { it.end == Long.MAX_VALUE },
            )
        seg.forEach { if (it.end == Long.MAX_VALUE) it.end = recordEnd }
        val start = maxOf(s.startNs, range?.first ?: s.startNs)
        val end = minOf(recordEnd, range?.last ?: recordEnd)
        val selected =
            seg.map { Segment(maxOf(it.start, start), minOf(it.end, end)) }
                .filter { it.end > it.start }
        val powerIds =
            hw.flatMap { it.values.keys }
                .toSet()
                .filter {
                    it == "battery.power" || it.startsWith("qpt.") && !it.endsWith(".energy")
                }
        val powers =
            powerIds.associateWith { id ->
                val parts =
                    selected.map { g ->
                        Statistics.power(hw.filter { it.ns in g.start..g.end }, id, s.intervalMs)
                    }
                val seconds = parts.sumOf { it.coverageSeconds }
                val e = parts.sumOf { it.energyJ ?: 0.0 }
                PowerStats(
                    if (seconds > 0) e / seconds else null,
                    parts.mapNotNull { it.peak }.maxOrNull(),
                    if (seconds > 0) e else null,
                    seconds,
                )
            }
        val frameGroups =
            selected.flatMap { g ->
                val groups = ArrayList<MutableList<Long>>()
                var previousLayer: String? = null
                var previousGeneration = -1
                frames
                    .filter { it.ns in g.start..g.end }
                    .sortedBy { it.ns }
                    .forEach { f ->
                        if (previousLayer != f.layer || previousGeneration != f.generation) {
                            groups += ArrayList<Long>()
                            previousLayer = f.layer
                            previousGeneration = f.generation
                        }
                        groups.last().add(f.ns)
                    }
                groups
            }
        val stats =
            Statistics.frameStats(frameGroups, calibration.result())
                .copy(
                    seconds = selected.sumOf { it.end - it.start } / 1e9,
                    powers = powers,
                    systemJank = Statistics.systemJank(jankTypes),
                    systemJankTypes = jankTypes,
                )
        val keys =
            hw.flatMap { it.values.keys }
                .distinct()
                .filterNot { it.endsWith(".energy") || it.endsWith(".online") }
        val boundaries = selected.drop(1).map { (it.start - s.startNs) / 1e9 }
        val hardwareCount = hw.size.toLong()
        val series =
            keys
                .map { id ->
                    Series(
                        id,
                        PlotData.markGaps(
                            hw.map { (it.ns - s.startNs) / 1e9 to it.values[id] },
                            s.intervalMs / 1000.0 * 2.5,
                            boundaries,
                        ),
                    )
                }
                .toMutableList()
        hw.clear()
        val fpsPoints =
            frameGroups
                .flatMap { group ->
                    val bins = group.groupBy { (it - s.startNs) / 500_000_000L }.toSortedMap()
                    val points =
                        bins.map { (bin, values) ->
                            bin * .5 to
                                if (values.size > 1)
                                    (values.size - 1) * 1e9 / (values.max() - values.min())
                                else null
                        }
                    if (points.isEmpty()) emptyList()
                    else listOf(points.first().first to null) + points
                }
                .sortedBy { it.first }
        series += Series("fps", PlotData.markGaps(fpsPoints, 1.25, boundaries))
        series +=
            Series(
                "frame.ms",
                frames
                    .sortedBy { it.ns }
                    .zipWithNext()
                    .map { (a, b) ->
                        val valid =
                            a.layer == b.layer &&
                                a.generation == b.generation &&
                                selected.any { a.ns >= it.start && b.ns <= it.end }
                        (b.ns - s.startNs) / 1e9 to if (valid) (b.ns - a.ns) / 1e6 else null
                    },
            )
        return Analysis(
            stats,
            series,
            events,
            selected,
            start,
            end,
            hardwareCount,
            !intact,
            quality,
            descriptors,
        )
    }
}
