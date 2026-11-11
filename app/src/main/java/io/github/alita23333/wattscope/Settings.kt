package io.github.alita23333.wattscope

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class Settings(context: Context) {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val revision = MutableStateFlow(0)

    fun text(k: String, default: String = "") = prefs.getString(k, default) ?: default

    fun int(k: String, default: Int) = prefs.getInt(k, default)

    fun bool(k: String, default: Boolean = false) = prefs.getBoolean(k, default)

    fun set(k: String, v: String) {
        prefs.edit().putString(k, v).apply()
        revision.update { it + 1 }
    }

    fun set(k: String, v: Int) {
        prefs.edit().putInt(k, v).apply()
        revision.update { it + 1 }
    }

    fun set(k: String, v: Boolean) {
        prefs.edit().putBoolean(k, v).apply()
        revision.update { it + 1 }
    }

    var game: String
        get() = text("game")
        set(v) {
            set("game", v)
        }

    var gameName: String
        get() = text("gameName", "未选择游戏")
        set(v) {
            set("gameName", v)
        }

    var interval: Int
        get() = int("interval", 500)
        set(v) {
            set("interval", v)
        }

    var target: Int
        get() = int("target", 120)
        set(v) {
            set("target", v)
        }

    var smallMetric: String
        get() = text("smallMetric", "fps")
        set(v) {
            set("smallMetric", v)
        }

    var osdTemplate: String
        get() = text("osdTemplate", "gauges")
        set(v) {
            set("osdTemplate", v)
        }

    var osdMetrics: List<String>
        get() =
            text("osdMetrics", Metrics.defaultOsd.joinToString(",")).split(',').filter {
                it.isNotBlank()
            }
        set(v) {
            set("osdMetrics", v.joinToString(","))
        }

    var dark: Boolean
        get() = bool("dark", true)
        set(v) {
            set("dark", v)
        }

    var background: String
        get() = text("background")
        set(v) {
            set("background", v)
        }
}
