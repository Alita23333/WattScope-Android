package io.github.alita23333.wattscope

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Only reachable through the non-exported service in debug/validation builds. */
object ValidationChecks {
    suspend fun record(app: WattApp, id: String): File {
        val repo = app.repository
        val report =
            JSONObject().put("recordId", id).put("startedWallMs", System.currentTimeMillis())
        val output = File(app.filesDir, "validation-record-$id.json")
        try {
            check(app.engine.live.value.recording == "idle") { "先结束正在进行的记录" }
            val session = checkNotNull(repo.dao.get(id)) { "找不到记录" }
            check(session.status in listOf("complete", "imported", "interrupted")) { "记录未结束" }
            val (fullSummary, segment) =
                repo.analyze(session).let { analysis ->
                    check(!analysis.damaged) { "记录存在损坏块" }
                    check(analysis.summary.frameCount > 0 && analysis.hardwareCount > 0) {
                        "该记录没有可验证的数据"
                    }
                    analysis.summary to analysis.segments.maxBy { it.end - it.start }
                }
            val start = segment.start + (segment.end - segment.start) / 4
            val end = segment.end - (segment.end - segment.start) / 4
            val selected = start..end
            if (repo.dao.getRanges(id).none { it.name == "验收区间" })
                repo.dao.insertRange(
                    RangeEntity(sessionId = id, name = "验收区间", startNs = start, endNs = end)
                )
            val selectedStats = repo.analyze(session, selected).summary
            val secondRange = segment.start..(segment.start + (segment.end - segment.start) / 3)
            if (repo.dao.getRanges(id).none { it.name == "验收区间 B" })
                repo.dao.insertRange(
                    RangeEntity(
                        sessionId = id,
                        name = "验收区间 B",
                        startNs = secondRange.first,
                        endNs = secondRange.last,
                    )
                )
            val csv = Transfer.csv(repo, session, selected)
            ZipFile(csv).use { zip ->
                val exported =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("summary.json")).bufferedReader().readText()
                    )
                check(Transfer.summaryFromJson(exported) == selectedStats) { "CSV 汇总与选区统计不同" }
            }
            report
                .put("csvSummaryMatches", true)
                .put("rangeSummary", Transfer.summaryJson(selectedStats))
            val image = Transfer.image(repo, session, selected)
            report.put("summaryImage", image.path).put("csv", csv.path)
            check(repo.cachedSummary(session) == fullSummary) { "历史摘要缓存与详情不同" }
            check(repo.cachedSummary(session) == fullSummary) { "缓存重新读取后结果不同" }
            report.put("cachedSummaryMatches", true)
            val comparison = Transfer.comparisonCsv(repo, session, selected, session, secondRange)
            fun nestedSummary(bytes: ByteArray): Summary {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name == "summary.json")
                            return Transfer.summaryFromJson(
                                JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                            )
                    }
                }
                error("找不到嵌套数据包的汇总")
            }
            ZipFile(comparison).use { zip ->
                check(
                    nestedSummary(
                        zip.getInputStream(zip.getEntry("A-data.zip")).use { it.readBytes() }
                    ) == selectedStats
                ) {
                    "对比导出覆盖了 A 区间"
                }
                check(
                    nestedSummary(
                        zip.getInputStream(zip.getEntry("B-data.zip")).use { it.readBytes() }
                    ) == repo.analyze(session, secondRange).summary
                ) {
                    "对比 B 区间统计不同"
                }
            }
            report
                .put("sameRecordRangeComparisonMatches", true)
                .put("comparisonCsv", comparison.path)
            // Repeated validation may reuse named ranges created under an older duration algorithm.
            // Verify what was actually exported rather than a newly computed quarter-span.
            val expectedRanges =
                repo.dao
                    .getRanges(id)
                    .map { Triple(it.name, it.startNs, it.endNs) }
                    .groupingBy { it }
                    .eachCount()
            val backup = Transfer.backup(repo, session)
            val importedId = backup.inputStream().use { Transfer.importRecord(repo, it) }
            val imported = checkNotNull(repo.dao.get(importedId))
            try {
                check(imported.id != session.id) { "导入覆盖了原记录 ID" }
                check(repo.analyze(imported).summary == fullSummary) { "重新导入后整段统计不同" }
                check(repo.analyze(imported, selected).summary == selectedStats) { "重新导入后选区统计不同" }
                check(
                    repo.dao
                        .getRanges(importedId)
                        .map { Triple(it.name, it.startNs, it.endNs) }
                        .groupingBy { it }
                        .eachCount() == expectedRanges
                ) {
                    "命名区间没有保留"
                }
                report
                    .put("importSummaryMatches", true)
                    .put("importRangeMatches", true)
                    .put("namedRangesPreserved", expectedRanges.values.sum())
                    .put("newIdUsed", true)
            } finally {
                repo.dao.update(imported.copy(deleted = true))
            }
            val tampered = File(app.cacheDir, "validation-tampered.zip")
            try {
                ZipFile(backup).use { zip ->
                    ZipOutputStream(tampered.outputStream()).use { out ->
                        zip.entries().asSequence().forEach { entry ->
                            out.putNextEntry(ZipEntry(entry.name))
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            if (entry.name == "record.wsn" && bytes.isNotEmpty())
                                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                            out.write(bytes)
                            out.closeEntry()
                        }
                    }
                }
                val rejected =
                    runCatching { tampered.inputStream().use { Transfer.importRecord(repo, it) } }
                        .isFailure
                check(rejected) { "损坏的备份没有被拒绝" }
                report.put("tamperedBackupRejected", true)
            } finally {
                tampered.delete()
            }
            val sha = MessageDigest.getInstance("SHA-256")
            repo.file(id).inputStream().use { input ->
                val b = ByteArray(65536)
                while (true) {
                    val n = input.read(b)
                    if (n < 0) break
                    sha.update(b, 0, n)
                }
            }
            report
                .put("rawSha256", sha.digest().joinToString("") { "%02x".format(it) })
                .put("fullSummary", Transfer.summaryJson(fullSummary))
                .put("backup", backup.path)
                .put("passed", true)
        } catch (error: Exception) {
            report.put("passed", false).put("error", error.stackTraceToString())
        }
        report.put("finishedWallMs", System.currentTimeMillis())
        output.writeText(report.toString(2))
        return output
    }
}
