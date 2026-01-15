package com.kazumaproject.hand_writting_save_img

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.createBitmap
import kotlin.math.abs

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Stroke(
        val path: Path,
        val strokeWidthPx: Float
    )

    private val strokes = ArrayList<Stroke>()
    private val redoStack = ArrayList<Stroke>()

    private var currentPath: Path? = null
    private var currentStrokeWidthPx: Float = 14f

    private var lastX = 0f
    private var lastY = 0f

    /**
     * 履歴（Undo/Redo可否）が変化したときに呼ばれるコールバック
     */
    var onHistoryChanged: (() -> Unit)? = null

    /**
     * ストローク確定（ACTION_UP）したときに呼ばれる（自動推論の起点）
     */
    var onStrokeCommitted: (() -> Unit)? = null

    /**
     * ストローク開始（ACTION_DOWN）したときに呼ばれる
     * 2画面入力で「相手側を確定する」トリガとして使う
     */
    var onStrokeStarted: (() -> Unit)? = null

    /**
     * 描画内容が変化した回数（自動推論の重複実行を防ぐ）
     */
    private var changeCounter: Long = 0
    fun getChangeCounter(): Long = changeCounter

    private fun bumpChange() {
        changeCounter++
        onHistoryChanged?.invoke()
    }

    private val paintTemplate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = currentStrokeWidthPx
    }

    // ---------------- Guide (restored) ----------------

    // デフォルトで表示（外枠＋中心十字）
    private var guideEnabled: Boolean = true
    private var guideShowCenterCross: Boolean = true
    private var guideShowBorder: Boolean = true

    // 例: グリッドも出したい場合に使う（0なら無効）
    private var guideGridStepPx: Int = 0

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun setGuideEnabled(enabled: Boolean) {
        guideEnabled = enabled
        invalidate()
    }

    fun setGuideCenterCrossEnabled(enabled: Boolean) {
        guideShowCenterCross = enabled
        invalidate()
    }

    fun setGuideBorderEnabled(enabled: Boolean) {
        guideShowBorder = enabled
        invalidate()
    }

    /**
     * stepPx <= 0 ならグリッド無効
     */
    fun setGuideGridStepPx(stepPx: Int) {
        guideGridStepPx = stepPx.coerceAtLeast(0)
        invalidate()
    }

    fun setGuideAlpha(alpha: Int) {
        guidePaint.alpha = alpha.coerceIn(0, 255)
        invalidate()
    }

    fun setGuideStrokeWidthPx(px: Float) {
        guidePaint.strokeWidth = px.coerceAtLeast(1f)
        invalidate()
    }

    // ------------------------------------------------

    fun setStrokeWidthPx(px: Float) {
        currentStrokeWidthPx = px.coerceAtLeast(1f)
        invalidate()
    }

    fun clearCanvas() {
        strokes.clear()
        redoStack.clear()
        currentPath = null
        invalidate()
        bumpChange()
    }

    fun canUndo(): Boolean = strokes.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        if (strokes.isEmpty()) return
        val s = strokes.removeAt(strokes.lastIndex)
        redoStack.add(s)
        invalidate()
        bumpChange()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val s = redoStack.removeAt(redoStack.lastIndex)
        strokes.add(s)
        invalidate()
        bumpChange()
    }

    /**
     * インク（ストローク）が存在するか
     * - 相手側を確定する条件に使う
     */
    fun hasInk(): Boolean {
        return strokes.isNotEmpty() || currentPath != null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (guideEnabled) {
            drawGuide(canvas)
        }

        for (s in strokes) {
            val p = Paint(paintTemplate).apply { strokeWidth = s.strokeWidthPx }
            canvas.drawPath(s.path, p)
        }

        val cp = currentPath
        if (cp != null) {
            val p = Paint(paintTemplate).apply { strokeWidth = currentStrokeWidthPx }
            canvas.drawPath(cp, p)
        }
    }

    private fun drawGuide(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        if (guideShowBorder) {
            val half = guidePaint.strokeWidth / 2f
            canvas.drawRect(half, half, w - half, h - half, guidePaint)
        }

        if (guideShowCenterCross) {
            val cx = w / 2f
            val cy = h / 2f
            canvas.drawLine(cx, 0f, cx, h, guidePaint)
            canvas.drawLine(0f, cy, w, cy, guidePaint)
        }

        val step = guideGridStepPx
        if (step > 0) {
            var x = step.toFloat()
            while (x < w) {
                canvas.drawLine(x, 0f, x, h, guidePaint)
                x += step
            }
            var y = step.toFloat()
            while (y < h) {
                canvas.drawLine(0f, y, w, y, guidePaint)
                y += step
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onStrokeStarted?.invoke()

                currentPath = Path().apply { moveTo(x, y) }
                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val cp = currentPath ?: return true
                val dx = abs(x - lastX)
                val dy = abs(y - lastY)
                if (dx >= 2f || dy >= 2f) {
                    val midX = (x + lastX) / 2f
                    val midY = (y + lastY) / 2f
                    cp.quadTo(lastX, lastY, midX, midY)
                    lastX = x
                    lastY = y
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cp = currentPath
                if (cp != null) {
                    cp.lineTo(x, y)
                    strokes.add(Stroke(cp, currentStrokeWidthPx))
                    redoStack.clear()

                    bumpChange()
                    onStrokeCommitted?.invoke()
                }
                currentPath = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    fun exportStrokesBitmapTransparent(borderPx: Int = 0): Bitmap {
        val w0 = width.coerceAtLeast(1)
        val h0 = height.coerceAtLeast(1)

        val b = borderPx.coerceAtLeast(0)
        val w = (w0 + b * 2).coerceAtLeast(1)
        val h = (h0 + b * 2).coerceAtLeast(1)

        val bmp = createBitmap(w, h)
        val canvas = Canvas(bmp)

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        canvas.save()
        canvas.translate(b.toFloat(), b.toFloat())

        // export にはガイドは描かない
        for (s in strokes) {
            val p = Paint(paintTemplate).apply { strokeWidth = s.strokeWidthPx }
            canvas.drawPath(s.path, p)
        }

        val cp = currentPath
        if (cp != null) {
            val p = Paint(paintTemplate).apply { strokeWidth = currentStrokeWidthPx }
            canvas.drawPath(cp, p)
        }

        canvas.restore()
        return bmp
    }
}
