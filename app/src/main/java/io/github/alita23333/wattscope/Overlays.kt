package io.github.alita23333.wattscope

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.text.TextPaint
import android.text.TextUtils
import android.view.*
import kotlin.math.*

class Overlays(
    private val context: Context,
    private val engine: Engine,
    private val settings: Settings,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var small: Panel? = null
    private var osd: Panel? = null
    private var lastOrientation = context.resources.configuration.orientation
    private var lastRenderAt = 0L
    private var lastHardwareNs = 0L
    private var lastControl = ""

    private fun add(kind: String): Panel? {
        if (!AndroidSettings.canDrawOverlays(context)) {
            engine.notifyMessage("请允许在其他应用上层显示")
            return null
        }
        val p = Panel(kind)
        val orientation = context.resources.configuration.orientation
        val key = "$kind.$orientation"
        val lp =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
        lp.gravity = Gravity.TOP or if (kind == "small") Gravity.START else Gravity.LEFT
        lp.x = settings.int("$key.x", 24)
        lp.y = settings.int("$key.y", if (kind == "small") 220 else 330)
        if (kind == "osd") {
            p.measure(0, 0)
            p.placeOsd(lp)
        }
        return try {
            wm.addView(p, lp)
            p
        } catch (e: Exception) {
            engine.notifyMessage("悬浮窗无法显示：${e.message}")
            null
        }
    }

    fun render(state: LiveState) {
        val orientation = context.resources.configuration.orientation
        val now = SystemClock.elapsedRealtime()
        val control =
            "${state.small}:${state.osd}:${state.recording}:${state.collector}:${state.controlPanelVisible}:${state.currentPackage}:${settings.revision.value}:$orientation:${state.health[settings.smallMetric]?.state}"
        val ns = state.latest?.ns ?: 0L
        if (control == lastControl && (ns == lastHardwareNs || now - lastRenderAt < 500)) return
        lastControl = control
        lastRenderAt = now
        lastHardwareNs = ns
        if (orientation != lastOrientation) {
            removeSmall()
            removeOsd()
            lastOrientation = orientation
        }
        if (state.small && small == null) small = add("small")
        if (!state.small) removeSmall()
        if (state.osd && osd == null) osd = add("osd")
        if (!state.osd) removeOsd()
        small?.update(state)
        osd?.update(state)
    }

    private fun removeSmall() {
        small?.let { runCatching { wm.removeView(it) } }
        small = null
    }

    private fun removeOsd() {
        osd?.let { runCatching { wm.removeView(it) } }
        osd = null
    }

    fun close() {
        removeSmall()
        removeOsd()
    }

    private inner class Panel(val kind: String) : View(context) {
        private val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val valueTypeface = Typeface.create("monospace", Typeface.NORMAL)
        private var osdFont = 12f
        private var osdRows = emptyList<String>()
        private var state = LiveState()
        private var rawX = 0f
        private var rawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var lastClick = 0L
        private val orientation = context.resources.configuration.orientation
        private var revision = -1
        private var paintKey = ""
        private var scale = 1f
        private var widthDp = 80f
        private var heightDp = 42f

        fun update(s: LiveState) {
            state = s
            val parameters = layoutParams as WindowManager.LayoutParams
            val flags =
                if (s.controlPanelVisible)
                    parameters.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else parameters.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (flags != parameters.flags) {
                parameters.flags = flags
                runCatching { wm.updateViewLayout(this, parameters) }
            }
            val r = settings.revision.value
            if (r != revision) {
                revision = r
                requestLayout()
            }
            val ids = if (kind == "small") listOf(settings.smallMetric) else settings.osdMetrics
            val key =
                "$r:${s.recording}:${s.collector}:${s.latest?.charging}:${s.health[settings.smallMetric]?.state}:" +
                    ids.joinToString(";") { id -> Metrics.format(id, value(id)) }
            if (key == paintKey) return
            paintKey = key
            contentDescription =
                if (kind == "small")
                    "记录小窗 ${s.recording} ${Metrics.format(settings.smallMetric,value(settings.smallMetric))}"
                else "OSD 数据面板"
            invalidate()
        }

        private fun value(id: String) =
            if (!state.collector) null
            else
                when (id) {
                    "fps" -> state.fps
                    "frame.ms" -> state.frameMs
                    "battery.power" -> BatteryPower.display(state.latest)
                    else -> state.latest?.values?.get(id)
                }

        override fun onMeasure(w: Int, h: Int) {
            scale =
                if (kind == "small") settings.int("small.scale", 100) / 100f
                else settings.int("osd.scale", 100) / 100f
            if (kind == "small") {
                widthDp = 84f
                heightDp = 40f
            } else {
                osdRows = settings.osdMetrics.ifEmpty { listOf("fps") }
                osdFont = settings.int("osd.font", 12).toFloat()
                widthDp = max(136f, osdFont * 11.5f)
                heightDp = 12f + osdRows.size * (osdFont + 3f)
                val bounds = wm.currentWindowMetrics.bounds
                scale =
                    minOf(
                        scale,
                        bounds.width() / (widthDp * density),
                        bounds.height() / (heightDp * density),
                    )
            }
            setMeasuredDimension(
                (widthDp * density * scale).roundToInt(),
                (heightDp * density * scale).roundToInt(),
            )
        }

        fun placeOsd(lp: WindowManager.LayoutParams): Boolean {
            val bounds = wm.currentWindowMetrics.bounds
            val key = "$kind.$orientation"
            val defaultX = bounds.width() - measuredWidth - (16 * density).roundToInt()
            val x =
                settings.int("$key.x", defaultX).coerceIn(0, max(0, bounds.width() - measuredWidth))
            val y =
                settings.int("$key.y", 330).coerceIn(0, max(0, bounds.height() - measuredHeight))
            if (lp.x == x && lp.y == y) return false
            lp.x = x
            lp.y = y
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (kind != "osd") return
            post {
                if (!isAttachedToWindow) return@post
                val lp = layoutParams as WindowManager.LayoutParams
                if (placeOsd(lp)) wm.updateViewLayout(this, lp)
            }
        }

        private fun text(
            c: Canvas,
            s: String,
            x: Float,
            y: Float,
            size: Float,
            color: Int = Color.WHITE,
            bold: Boolean = false,
        ) {
            p.style = Paint.Style.FILL
            p.color = color
            p.textSize = size
            p.typeface =
                if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else Typeface.create("sans-serif", Typeface.NORMAL)
            c.drawText(s, x, y, p)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save()
            canvas.scale(density * scale, density * scale)
            val bg =
                if (kind == "small") Color.rgb(17, 25, 32)
                else settings.int("osd.background", Color.rgb(29, 38, 48))
            p.style = Paint.Style.FILL
            p.color = bg
            p.alpha =
                if (kind == "small") 230 else (settings.int("osd.opacity", 85) * 2.55f).toInt()
            canvas.drawRoundRect(
                0f,
                0f,
                widthDp,
                heightDp,
                if (kind == "small") 13f else 9f,
                if (kind == "small") 13f else 9f,
                p,
            )
            p.alpha = 255
            val accent = settings.int("osd.accent", Color.rgb(126, 205, 237))
            val muted = Color.rgb(178, 190, 202)
            if (kind == "small") {
                val color =
                    if (state.health[settings.smallMetric]?.state == "读取失败")
                        Color.rgb(206, 150, 255)
                    else
                        when (state.recording) {
                            "recording" -> Color.rgb(255, 110, 110)
                            "auto_paused",
                            "manual_paused" -> Color.rgb(255, 200, 100)
                            "armed",
                            "preparing",
                            "saving" -> Color.rgb(132, 180, 255)
                            else -> if (state.collector) Color.rgb(121, 221, 200) else Color.GRAY
                        }
                p.color = color
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f
                canvas.drawRoundRect(1f, 1f, widthDp - 1, heightDp - 1, 13f, 13f, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(9f, 20f, 2.5f, p)
                val id = settings.smallMetric
                val v = Metrics.format(id, value(id))
                text(canvas, v, 17f, 25f, 18f, Color.WHITE, true)
                p.textSize = 18f
                val tw = p.measureText(v)
                text(canvas, Metrics.unit(id), min(19f + tw, 65f), 25f, 9f, muted)
            } else {
                var baseline = 6f + osdFont
                for (id in osdRows) {
                    val value = value(id)
                    val unit = Metrics.unit(id)
                    val label = shortLabel(id)
                    val number = Metrics.format(id, value)
                    val reading = if (value == null || unit.isBlank()) number else "$number $unit"
                    p.textSize = osdFont
                    p.typeface = valueTypeface
                    p.color = if (value == null) muted else Color.WHITE
                    p.textAlign = Paint.Align.RIGHT
                    val right = widthDp - 8f
                    canvas.drawText(reading, right, baseline, p)
                    val labelWidth = (right - p.measureText(reading) - 22f).coerceAtLeast(0f)
                    p.textAlign = Paint.Align.LEFT
                    p.typeface = Typeface.DEFAULT
                    p.textSize = osdFont - 1
                    p.color = accent
                    val fitted = TextUtils.ellipsize(label, p, labelWidth, TextUtils.TruncateAt.END)
                    canvas.drawText(fitted.toString(), 8f, baseline, p)
                    baseline += osdFont + 3f
                }
            }
            canvas.restore()
        }

        private fun shortLabel(id: String) =
            when (id) {
                "fps" -> "FPS"
                "battery.power" -> if (state.latest?.charging == "Discharging") "PWR" else "BAT NET"
                "qpt.soc" -> "SoC"
                "temp.soc" -> "T·SoC"
                "temp.battery" -> "T·BAT"
                "temp.cpu" -> "T·CPU"
                "temp.gpu" -> "T·GPU"
                "ram.percent" -> "RAM"
                "ddr.freq" -> "DDR"
                "cpu.usage" -> "CPU"
                "gpu.usage" -> "GPU"
                "gpu.freq" -> "GPU MHz"
                "battery.capacity" -> "BAT"
                else ->
                    id.removePrefix("qpt.")
                        .replace("cpu.cluster", "C")
                        .replace("cpu.", "#")
                        .replace(".usage", " %")
                        .replace(".freq", " F")
            }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val lp = layoutParams as WindowManager.LayoutParams
            val locked = settings.bool("$kind.locked")
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = e.rawX
                    rawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - rawX
                    val dy = e.rawY - rawY
                    if (hypot(dx, dy) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
                    if (moved && !locked) {
                        val bounds = wm.currentWindowMetrics.bounds
                        val screenWidth =
                            if (kind == "osd") bounds.width()
                            else context.resources.displayMetrics.widthPixels
                        val screenHeight =
                            if (kind == "osd") bounds.height()
                            else context.resources.displayMetrics.heightPixels
                        lp.x = (startX + dx.toInt()).coerceIn(0, max(0, screenWidth - width))
                        lp.y = (startY + dy.toInt()).coerceIn(0, max(0, screenHeight - height))
                        runCatching { wm.updateViewLayout(this, lp) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved && !locked) {
                        settings.set("$kind.$orientation.x", lp.x)
                        settings.set("$kind.$orientation.y", lp.y)
                    } else if (
                        !moved && kind == "small" && SystemClock.elapsedRealtime() - lastClick > 500
                    ) {
                        lastClick = SystemClock.elapsedRealtime()
                        performClick()
                        engine.toggleRecord()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
