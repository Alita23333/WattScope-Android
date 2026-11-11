package io.github.alita23333.wattscope

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.File
import kotlin.math.*

object Backgrounds {
    fun import(context: Context, uri: Uri, settings: Settings) {
        val original = File(context.filesDir, "background.original")
        val pending = File(context.filesDir, "background.pending")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                pending.outputStream().use { out ->
                    val b = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        val n = input.read(b)
                        if (n < 0) break
                        size += n
                        check(size <= 40 * 1024 * 1024) { "图片超过 40 MiB" }
                        out.write(b, 0, n)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(pending.path, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
            process(context, settings, pending)
            check(pending.renameTo(original)) { "无法保存背景原图" }
        } finally {
            pending.delete()
        }
    }

    fun process(
        context: Context,
        settings: Settings,
        source: File = File(context.filesDir, "background.original"),
    ) {
        if (!source.exists()) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = max(1, max(bounds.outWidth, bounds.outHeight) / 1800)
            }
        val original = BitmapFactory.decodeFile(source.path, options) ?: error("图片解码失败")
        val ratio =
            context.resources.displayMetrics.widthPixels.toFloat() /
                context.resources.displayMetrics.heightPixels
        val zoom = settings.int("background.zoom", 100) / 100f
        var w = original.width.toFloat()
        var h = w / ratio
        if (h > original.height) {
            h = original.height.toFloat()
            w = h * ratio
        }
        w /= zoom
        h /= zoom
        val x =
            ((original.width - w) * settings.int("background.x", 50) / 100f)
                .toInt()
                .coerceAtLeast(0)
        val y =
            ((original.height - h) * settings.int("background.y", 50) / 100f)
                .toInt()
                .coerceAtLeast(0)
        val crop =
            Bitmap.createBitmap(
                original,
                x,
                y,
                w.toInt().coerceAtLeast(1),
                h.toInt().coerceAtLeast(1),
            )
        val bitmap =
            Bitmap.createScaledBitmap(crop, 540, (540 / ratio).toInt().coerceAtLeast(1), true)
                .copy(Bitmap.Config.ARGB_8888, true)
        val blur = settings.int("background.blur", 8)
        if (blur > 0) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            repeat(3) { boxBlur(pixels, bitmap.width, bitmap.height, max(1, blur / 2)) }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        Canvas(bitmap)
            .drawColor(Color.argb((settings.int("background.dim", 50) * 2.55).toInt(), 0, 0, 0))
        val file = File(context.filesDir, "background-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val previous = settings.background
        settings.background = file.path
        if (previous.isNotBlank() && File(previous).parentFile == context.filesDir)
            File(previous).delete()
        bitmap.recycle()
        if (crop !== original) crop.recycle()
        original.recycle()
    }

    private fun boxBlur(p: IntArray, w: Int, h: Int, r: Int) {
        val q = IntArray(p.size)
        for (y in 0 until h) {
            var rr = 0
            var gg = 0
            var bb = 0
            fun add(x: Int, sign: Int) {
                val c = p[y * w + x.coerceIn(0, w - 1)]
                rr += ((c shr 16) and 255) * sign
                gg += ((c shr 8) and 255) * sign
                bb += (c and 255) * sign
            }
            for (x in -r..r) add(x, 1)
            for (x in 0 until w) {
                q[y * w + x] = Color.rgb(rr / (2 * r + 1), gg / (2 * r + 1), bb / (2 * r + 1))
                add(x - r, -1)
                add(x + r + 1, 1)
            }
        }
        for (x in 0 until w) {
            var rr = 0
            var gg = 0
            var bb = 0
            fun add(y: Int, sign: Int) {
                val c = q[y.coerceIn(0, h - 1) * w + x]
                rr += ((c shr 16) and 255) * sign
                gg += ((c shr 8) and 255) * sign
                bb += (c and 255) * sign
            }
            for (y in -r..r) add(y, 1)
            for (y in 0 until h) {
                p[y * w + x] = Color.rgb(rr / (2 * r + 1), gg / (2 * r + 1), bb / (2 * r + 1))
                add(y - r, -1)
                add(y + r + 1, 1)
            }
        }
    }
}
