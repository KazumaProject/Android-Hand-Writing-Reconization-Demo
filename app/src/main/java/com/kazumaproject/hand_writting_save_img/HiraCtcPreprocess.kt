package com.kazumaproject.hand_writting_save_img

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.scale
import kotlin.math.roundToInt

data class PreprocessConfig(
    val targetH: Int = 32,
    val maxW: Int = 512,
    val blurRadius: Float = 0.6f,   // ここでは簡略化：必要なら RenderScript/ScriptIntrinsicBlur 等に置換
    val inkThresh: Int = 245        // Python と同じ
)

data class PreprocessResult(
    val chw: FloatArray,  // [1,1,H,W] をフラット化（実際には [H*W]）
    val h: Int,
    val w: Int,
    val validTimeSteps: Int
)

object HiraCtcPreprocess {

    /**
     * 入力 bitmap は「白背景＋黒ストローク」を想定。
     * 透過の場合は alpha==0 を白として扱います。
     */
    fun run(src: Bitmap, cfg: PreprocessConfig): PreprocessResult {
        // 1) インクbbox（余白ゼロ）
        val bbox = tightInkBBox(src, cfg.inkThresh)
            ?: throw IllegalStateException("Nothing drawn.")

        val cropped = Bitmap.createBitmap(src, bbox.left, bbox.top, bbox.width(), bbox.height())

        // 2) blur（ここでは省略実装：必要なら後で差し替え）
        val blurred = cropped // TODO: blur を入れたいならここで

        // 3) 高さを cfg.targetH に合わせてアスペクト維持リサイズ
        val resized = resizeKeepAspectToHeight(blurred, cfg.targetH)

        // 4) maxW 超過なら右を切る
        val clipped = if (resized.width > cfg.maxW) {
            Bitmap.createBitmap(resized, 0, 0, cfg.maxW, resized.height)
        } else resized

        // 5) float [0,1]（white=1.0, black=0.0）
        val h = clipped.height
        val w = clipped.width
        val floats = FloatArray(h * w)
        val pixels = IntArray(h * w)
        clipped.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val c = pixels[i]
            val a = Color.alpha(c)
            if (a == 0) {
                floats[i] = 1.0f
            } else {
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                // luminance
                val gray = (0.299f * r + 0.587f * g + 0.114f * b)
                floats[i] = (gray / 255.0f).coerceIn(0f, 1f)
            }
        }

        // Python: width_to_time_steps = valid_w_px // 4
        val tValid = maxOf(1, w / 4)

        return PreprocessResult(
            chw = floats,
            h = h,
            w = w,
            validTimeSteps = tValid
        )
    }

    private data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun width() = right - left + 1
        fun height() = bottom - top + 1
    }

    private fun tightInkBBox(bmp: Bitmap, inkThresh: Int): RectI? {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                val c = pixels[off + x]
                val a = Color.alpha(c)
                val gray = if (a == 0) {
                    255
                } else {
                    val r = Color.red(c);
                    val g = Color.green(c);
                    val b = Color.blue(c)
                    (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255)
                }
                if (gray < inkThresh) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0 || maxY < 0) return null
        return RectI(minX, minY, maxX, maxY)
    }

    private fun resizeKeepAspectToHeight(bmp: Bitmap, targetH: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (h <= 0) return bmp
        val scale = targetH.toFloat() / h.toFloat()
        val newW = maxOf(1, (w * scale).roundToInt())
        return bmp.scale(newW, targetH)
    }
}
