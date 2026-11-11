package io.github.alita23333.wattscope

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.*

data class Viewport(val left: Double = 0.0, val right: Double = 1.0, val cursor: Double? = null)

data class TraceLine(val series: Series, val label: String, val color: Color)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TraceChart(
    series: Series,
    viewport: Viewport,
    modifier: Modifier = Modifier,
    second: Series? = null,
    additional: List<TraceLine> = emptyList(),
    title: String? = null,
    primaryLabel: String? = null,
    primaryColor: Color? = null,
    secondaryLabel: String? = null,
    dualAxis: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
    onCursor: ((Double) -> Unit)? = null,
) {
    val light = MaterialTheme.colorScheme.background.luminance() > .5f
    val color =
        when {
            series.id == "fps" -> if (light) Color(0xFF176DDA) else Color(0xFF91BFFF)
            series.id.startsWith("temp") -> if (light) Color(0xFFB85B0C) else Color(0xFFFFCA8A)
            series.id.startsWith("qpt") || series.id == "battery.power" ->
                if (light) Color(0xFF008B72) else Color(0xFF79DDC8)
            else -> if (light) Color(0xFF8752BC) else Color(0xFFCEB6F5)
        }
    val lines =
        listOf(
            TraceLine(
                series,
                primaryLabel ?: if (second != null) "A" else Metrics.label(series.id),
                primaryColor ?: color,
            )
        ) +
            listOfNotNull(
                second?.let {
                    TraceLine(
                        it,
                        secondaryLabel ?: "B",
                        if (light) Color(0xFFBA5B18) else Color(0xFFFFB787),
                    )
                }
            ) +
            additional
    val visible =
        remember(lines, viewport.left, viewport.right) {
            lines.map { PlotData.decimate(it.series.visible(viewport.left, viewport.right)) }
        }
    val separateAxes = dualAxis && second != null
    val vals =
        visible
            .filterIndexed { index, _ -> !separateAxes || index != 1 }
            .asSequence()
            .flatten()
            .mapNotNull { it.second }
            .filter { it.isFinite() }
            .toList()
    var low = vals.minOrNull() ?: 0.0
    var high = vals.maxOrNull() ?: 1.0
    if (!series.id.startsWith("temp")) low = min(0.0, low)
    if (high - low < .001) high = low + 1
    val margin = (high - low) * .12
    high += margin
    low -= if (low < 0) margin else 0.0
    val secondaryValues =
        if (separateAxes) visible[1].mapNotNull { it.second }.filter { it.isFinite() }
        else emptyList()
    val secondaryLow = 0.0
    val secondaryHigh =
        if (Metrics.unit(second?.id ?: "") == "%") 100.0
        else ((secondaryValues.maxOrNull() ?: 1.0).coerceAtLeast(.001) * 1.12)
    val rightPadding = if (separateAxes) 48.dp else 8.dp
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = tickColor.copy(alpha = .16f)
    val span = (viewport.right - viewport.left).coerceAtLeast(.001)
    val density = LocalDensity.current
    val plotLeft = with(density) { 48.dp.toPx() }
    val plotRightPadding = with(density) { rightPadding.toPx() }
    val latestViewport by rememberUpdatedState(viewport)
    val latestOnCursor by rememberUpdatedState(onCursor)
    val cursor = viewport.cursor?.takeIf { it in viewport.left..viewport.right }
    fun inspect(x: Float, width: Int) {
        val v = latestViewport
        val fraction =
            ((x - plotLeft) / (width - plotLeft - plotRightPadding).coerceAtLeast(1f)).coerceIn(
                0f,
                1f,
            )
        latestOnCursor?.invoke(v.left + fraction * (v.right - v.left))
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                title ?: "${Metrics.label(series.id)} · ${Metrics.unit(series.id)}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            actions?.invoke()
        }
        if (lines.size > 1)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                lines.forEach { line ->
                    Text(
                        "● ${line.label}" +
                            if (separateAxes) " (${Metrics.unit(line.series.id)})" else "",
                        color = line.color,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        if (separateAxes)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Left · ${Metrics.unit(series.id)}",
                    color = lines[0].color,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "Right · ${Metrics.unit(second!!.id)}",
                    color = lines[1].color,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        Canvas(
            Modifier.fillMaxWidth()
                .height(164.dp)
                .semantics {
                    contentDescription =
                        "${title ?: Metrics.label(series.id)}，时间 ${elapsedTime(viewport.left)} 至 ${elapsedTime(viewport.right)}" +
                            (cursor?.let { "，${elapsedTime(it, 2)}" } ?: "")
                }
                .pointerInput(series.id, separateAxes, density, onCursor != null) {
                    if (onCursor != null) detectTapGestures(onTap = { inspect(it.x, size.width) })
                }
                .pointerInput(series.id, separateAxes, density, onCursor != null) {
                    if (onCursor != null)
                        detectHorizontalDragGestures(
                            onDragStart = { inspect(it.x, size.width) },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                inspect(change.position.x, size.width)
                            },
                        )
                }
        ) {
            val left = 48.dp.toPx()
            val right = size.width - rightPadding.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 25.dp.toPx()
            val paint =
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color =
                        android.graphics.Color.argb(
                            190,
                            (tickColor.red * 255).toInt(),
                            (tickColor.green * 255).toInt(),
                            (tickColor.blue * 255).toInt(),
                        )
                    textSize = 10.dp.toPx()
                }
            for (i in 0..3) {
                val y = top + (bottom - top) * i / 3
                drawLine(gridColor, Offset(left, y), Offset(right, y))
                drawContext.canvas.nativeCanvas.drawText(
                    "%.1f".format(high - (high - low) * i / 3),
                    0f,
                    y + 4.dp.toPx(),
                    paint,
                )
                if (separateAxes) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "%.0f".format(secondaryHigh - (secondaryHigh - secondaryLow) * i / 3),
                        right + 6.dp.toPx(),
                        y + 4.dp.toPx(),
                        paint,
                    )
                }
            }
            val ticks = if (viewport.right >= 3600) 2 else if (viewport.right >= 60) 3 else 4
            for (i in 0..ticks) {
                val x = left + (right - left) * i / ticks
                drawLine(gridColor, Offset(x, top), Offset(x, bottom))
                val label = elapsedTime(viewport.left + span * i / ticks)
                val width = paint.measureText(label)
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    (x - width / 2).coerceIn(left, (size.width - width).coerceAtLeast(left)),
                    size.height - 4.dp.toPx(),
                    paint,
                )
            }
            fun draw(
                points: List<Pair<Double, Double?>>,
                c: Color,
                axisLow: Double,
                axisHigh: Double,
            ) {
                val path = Path()
                var linked = false
                for ((t, v) in points) {
                    if (v == null || !v.isFinite()) {
                        linked = false
                        continue
                    }
                    val x = left + ((t - viewport.left) / span * (right - left)).toFloat()
                    val y =
                        bottom - ((v - axisLow) / (axisHigh - axisLow) * (bottom - top)).toFloat()
                    if (!linked) path.moveTo(x, y) else path.lineTo(x, y)
                    linked = true
                }
                drawPath(path, c, style = Stroke(.8.dp.toPx()))
            }
            lines.forEachIndexed { index, line ->
                draw(
                    visible[index],
                    line.color,
                    if (separateAxes && index == 1) secondaryLow else low,
                    if (separateAxes && index == 1) secondaryHigh else high,
                )
            }
            cursor?.let { t ->
                val x = left + ((t - viewport.left) / span * (right - left)).toFloat()
                drawLine(
                    tickColor.copy(alpha = .7f),
                    Offset(x, top),
                    Offset(x, bottom),
                    .8.dp.toPx(),
                )
            }
        }
        cursor?.let { t ->
            Text(elapsedTime(t, 2), style = MaterialTheme.typography.labelSmall, color = tickColor)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                lines.forEach { line ->
                    Text(
                        "${if (lines.size > 1) line.label + " · " else ""}${Metrics.format(line.series.id, line.series.nearest(t)?.second)} ${Metrics.unit(line.series.id)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = line.color,
                    )
                }
            }
        }
    }
}
