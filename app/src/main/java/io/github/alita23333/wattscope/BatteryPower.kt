package io.github.alita23333.wattscope

import kotlin.math.abs
import org.json.JSONObject

object BatteryPower {
    const val POLICY = "record-discharge-positive-v1"

    fun display(sample: Hardware?): Double? {
        val raw = sample?.values?.get("battery.power")?.takeIf { it.isFinite() } ?: return null
        if (raw == 0.0) return 0.0
        return when (sample.charging) {
            "Charging" -> abs(raw)
            "Discharging" -> -abs(raw)
            else -> -raw
        }
    }

    fun recorded(sample: Hardware): Double? =
        when (sample.charging) {
            "Discharging" -> sample.values["battery.power"]?.takeIf { it.isFinite() }?.let(::abs)
            "Charging",
            "Full",
            "Not charging" -> 0.0
            else -> null
        }
}

fun Hardware.forRecording(): Hardware {
    if ("battery.power" !in values) return this
    val value = BatteryPower.recorded(this)
    val source = sources["battery.power"].orEmpty()
    val origin =
        if (BatteryPower.POLICY in source) source
        else "$source; ${BatteryPower.POLICY}; raw=${values["battery.power"] ?: "null"}"
    val updatedIssues = issues.toMutableMap()
    if (value != null) updatedIssues.remove("battery.power")
    else if (charging !in listOf("Discharging", "Charging", "Full", "Not charging"))
        updatedIssues["battery.power"] = MetricIssue("missing", "未知电池状态")
    return copy(
        values = values + ("battery.power" to value),
        sources = sources + ("battery.power" to origin),
        issues = updatedIssues,
    )
}

fun Hardware.recordLine(): String {
    val sample = forRecording()
    val data =
        JSONObject().apply {
            sample.values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
    val issues =
        JSONObject().apply {
            sample.issues.forEach { (key, value) ->
                put(key, JSONObject().put("state", value.state).put("reason", value.reason))
            }
        }
    return "H|${sample.ns}|${if (sample.foreground) 1 else 0}|${sample.charging}|$data|${JSONObject(sample.sources)}|$issues"
}
