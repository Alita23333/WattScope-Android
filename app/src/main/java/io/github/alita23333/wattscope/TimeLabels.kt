package io.github.alita23333.wattscope

import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

fun elapsedTime(seconds: Double, fractionalDigits: Int = 0): String {
    if (!seconds.isFinite()) return "—"
    val digits = fractionalDigits.coerceIn(0, 3)
    val scale = 10.0.pow(digits)
    val value =
        if (digits == 0) floor(seconds.coerceAtLeast(0.0))
        else round(seconds.coerceAtLeast(0.0) * scale) / scale
    val whole = floor(value).toLong()
    val hours = whole / 3600
    val minutes = whole / 60 % 60
    val remainder = value - (whole / 60) * 60
    val sec =
        if (digits == 0) (whole % 60).toString()
        else String.format(Locale.US, "%.${digits}f", remainder)
    return when {
        hours > 0 -> "${hours}h${minutes}m${sec}s"
        minutes > 0 -> "${minutes}m${sec}s"
        else -> "${sec}s"
    }
}
