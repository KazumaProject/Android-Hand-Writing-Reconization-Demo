package com.kazumaproject.hand_writting_save_img

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.scale

object BitmapPreprocessor {

    /**
     * 白背景 + 黒インク前提で、インクのタイトbboxを見つける。
     * 戻りRectは right/bottom 排他的。
     */
    fun findInkBounds(srcWhiteBg: Bitmap, inkThresh: Int = 245): Rect? {
        val w = srcWhiteBg.width
        val h = srcWhiteBg.height
        if (w <= 1 || h <= 1) return null

        val pixels = IntArray(w * h)
        srcWhiteBg.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                val c = pixels[off + x]
                val a = Color.alpha(c)
                val g = if (a == 0) 255 else {
                    val r = Color.red(c)
                    val gg = Color.green(c)
                    val b = Color.blue(c)
                    (0.299f * r + 0.587f * gg + 0.114f * b).toInt().coerceIn(0, 255)
                }
                if (g < inkThresh) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < 0 || maxY < 0) return null
        return Rect(minX, minY, maxX + 1, maxY + 1)
    }

    /**
     * インクbboxを (innerPadPx) で拡張して切り出し、その後
     * 正方形キャンバスへ中央寄せ + outerMarginPx を付けて返す。
     */
    fun tightCenterSquare(
        srcWhiteBg: Bitmap,
        inkThresh: Int = 245,
        innerPadPx: Int = 8,
        outerMarginPx: Int = 24,
        minSidePx: Int = 96
    ): Bitmap {
        val w = srcWhiteBg.width
        val h = srcWhiteBg.height
        val bounds = findInkBounds(srcWhiteBg, inkThresh)

        // インクなしなら、そのまま返す
        if (bounds == null) return srcWhiteBg

        val pad = innerPadPx.coerceAtLeast(0)
        val l = (bounds.left - pad).coerceAtLeast(0)
        val t = (bounds.top - pad).coerceAtLeast(0)
        val r = (bounds.right + pad).coerceAtMost(w)
        val b = (bounds.bottom + pad).coerceAtMost(h)

        val cw = (r - l).coerceAtLeast(1)
        val ch = (b - t).coerceAtLeast(1)

        val cropped = Bitmap.createBitmap(srcWhiteBg, l, t, cw, ch)

        val margin = outerMarginPx.coerceAtLeast(0)
        val side = max(minSidePx.coerceAtLeast(1), max(cw, ch) + margin * 2)

        val out = createBitmap(side, side)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val dx = ((side - cw) / 2f)
        val dy = ((side - ch) / 2f)

        canvas.drawBitmap(cropped, dx, dy, null)
        return out
    }

    /**
     * 複数の正規化画像をグリッドに合成して返す（プレビュー用）
     *
     * - images: 各Bitmap（サイズバラバラでもOK）
     * - cellSizePx: 各セルに収まるように等比で縮小して中央寄せ
     * - cols: 列数
     * - padPx: セル間・外枠の余白
     */
    fun composeGrid(
        images: List<Bitmap>,
        cellSizePx: Int = 128,
        cols: Int = 4,
        padPx: Int = 10
    ): Bitmap? {
        if (images.isEmpty()) return null

        val safeCols = cols.coerceAtLeast(1)
        val rows = ceil(images.size / safeCols.toDouble()).toInt().coerceAtLeast(1)

        val cell = cellSizePx.coerceAtLeast(8)
        val pad = padPx.coerceAtLeast(0)

        val outW = pad + safeCols * (cell + pad)
        val outH = pad + rows * (cell + pad)

        val out = createBitmap(outW, outH)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        for (i in images.indices) {
            val r = i / safeCols
            val c = i % safeCols

            val x0 = pad + c * (cell + pad)
            val y0 = pad + r * (cell + pad)

            val src = images[i]
            val sw = src.width.coerceAtLeast(1)
            val sh = src.height.coerceAtLeast(1)

            val scale = min(cell.toFloat() / sw.toFloat(), cell.toFloat() / sh.toFloat())
            val dw = max(1, (sw * scale).toInt())
            val dh = max(1, (sh * scale).toInt())

            val dx = x0 + (cell - dw) / 2
            val dy = y0 + (cell - dh) / 2

            val scaled = if (dw == sw && dh == sh) {
                src
            } else {
                src.scale(dw, dh)
            }

            canvas.drawBitmap(scaled, dx.toFloat(), dy.toFloat(), paint)

            // scaledが新規生成なら破棄（元と同一参照は破棄しない）
            if (scaled !== src) {
                runCatching { scaled.recycle() }
            }
        }

        return out
    }
}
