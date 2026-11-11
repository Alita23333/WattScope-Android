package io.github.alita23333.wattscope

import androidx.compose.runtime.Composable

@Composable
fun GpuChart(analysis: Analysis, viewport: Viewport, onCursor: (Double) -> Unit) {
    val frequency = analysis.series.firstOrNull { it.id == "gpu.freq" }
    val usage = analysis.series.firstOrNull { it.id == "gpu.usage" }
    if (frequency != null && usage != null)
        Panel {
            TraceChart(
                frequency,
                viewport,
                second = usage,
                title = "GPU freq / usage",
                primaryLabel = "GPU freq",
                secondaryLabel = "GPU usage",
                dualAxis = true,
                onCursor = onCursor,
            )
        }
    else
        (frequency ?: usage)?.let { series ->
            Panel { TraceChart(series, viewport, onCursor = onCursor) }
        }
}
