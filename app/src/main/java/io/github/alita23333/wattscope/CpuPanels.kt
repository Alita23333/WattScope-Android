package io.github.alita23333.wattscope

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

@Composable
private fun clusterColors(clusters: List<CpuCluster>): Map<Int, Color> {
    val light = MaterialTheme.colorScheme.background.luminance() > .5f
    val cool =
        if (light) listOf(0xFF2672DE, 0xFF00826B, 0xFF377E91)
        else listOf(0xFF78ACFF, 0xFF63D5B4, 0xFF70D0E6)
    val warm =
        if (light) listOf(0xFFB96511, 0xFFCD4053, 0xFF8351BA)
        else listOf(0xFFFFB363, 0xFFFF879A, 0xFFC19AFF)
    var coolIndex = 0
    var warmIndex = 0
    return clusters.associate { cluster ->
        val color =
            if (cluster.cores.size in 1..2) warm[warmIndex++ % warm.size]
            else cool[coolIndex++ % cool.size]
        cluster.policy to Color(color)
    }
}

@Composable
fun CpuClusterCharts(analysis: Analysis, viewport: Viewport, onCursor: (Double) -> Unit) {
    val clusters =
        remember(analysis.descriptors) { CpuTopology.discover(analysis.descriptors.values) }
    val colors = clusterColors(clusters)
    Column(verticalArrangement = Arrangement.spacedBy(pageSpacing())) {
        val frequencies =
            clusters.mapNotNull { cluster ->
                analysis.series
                    .firstOrNull { it.id == cluster.frequencyId }
                    ?.let { TraceLine(it, cluster.label, colors.getValue(cluster.policy)) }
            }
        if (frequencies.isNotEmpty())
            Panel {
                TraceChart(
                    frequencies.first().series,
                    viewport,
                    additional = frequencies.drop(1),
                    title = "CPU freq · MHz",
                    primaryLabel = frequencies.first().label,
                    primaryColor = frequencies.first().color,
                    onCursor = onCursor,
                )
            }
        val cores =
            remember(analysis.series) {
                analysis.series
                    .filter { Regex("cpu\\.\\d+\\.usage").matches(it.id) }
                    .sortedBy { it.id.removePrefix("cpu.").substringBefore('.').toInt() }
            }
        val usage =
            cores.map { series ->
                val core = series.id.removePrefix("cpu.").substringBefore('.').toInt()
                val cluster = clusters.firstOrNull { core in it.cores }
                TraceLine(
                    series,
                    "CPU ${series.id.removePrefix("cpu.").substringBefore('.')}",
                    cluster?.let { colors[it.policy] } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        if (usage.isNotEmpty())
            Panel {
                TraceChart(
                    usage.first().series,
                    viewport,
                    additional = usage.drop(1),
                    title = "CPU usage · %",
                    primaryLabel = usage.first().label,
                    primaryColor = usage.first().color,
                    onCursor = onCursor,
                )
            }
        else {
            analysis.series
                .firstOrNull { it.id == "cpu.usage" }
                ?.let { Panel { TraceChart(it, viewport, onCursor = onCursor) } }
        }
    }
}

@Composable
fun CpuLiveRows(metrics: List<Metric>, values: Map<String, Double?>) {
    val clusters = remember(metrics) { CpuTopology.discover(metrics) }
    val colors = clusterColors(clusters)
    clusters.forEach { cluster ->
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "●",
                color = colors.getValue(cluster.policy),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(cluster.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(
                "${Metrics.format(cluster.frequencyId, values[cluster.frequencyId])} MHz",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${Metrics.format(cluster.usageId, values[cluster.usageId])}%",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
