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
     * MainActivity 側でボタン enable 更新に使う
     */
    var onHistoryChanged: (() -> Unit)? = null

    private fun notifyHistoryChanged() {
        onHistoryChanged?.invoke()
    }

    private val paintTemplate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = currentStrokeWidthPx
    }

    fun setStrokeWidthPx(px: Float) {
        currentStrokeWidthPx = px.coerceAtLeast(1f)
        invalidate()
    }

    fun clearCanvas() {
        strokes.clear()
        redoStack.clear()
        currentPath = null
        invalidate()
        notifyHistoryChanged()
    }

    fun canUndo(): Boolean = strokes.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        if (strokes.isEmpty()) return
        val s = strokes.removeAt(strokes.lastIndex)
        redoStack.add(s)
        invalidate()
        notifyHistoryChanged()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val s = redoStack.removeAt(redoStack.lastIndex)
        strokes.add(s)
        invalidate()
        notifyHistoryChanged()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 履歴ストローク描画
        for (s in strokes) {
            val p = Paint(paintTemplate).apply { strokeWidth = s.strokeWidthPx }
            canvas.drawPath(s.path, p)
        }

        // 描画中ストローク
        val cp = currentPath
        if (cp != null) {
            val p = Paint(paintTemplate).apply { strokeWidth = currentStrokeWidthPx }
            canvas.drawPath(cp, p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
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

                    // ここでストローク確定 → 履歴に追加
                    strokes.add(Stroke(cp, currentStrokeWidthPx))

                    // 新規ストロークが追加されたら redo は破棄
                    redoStack.clear()

                    // 重要: ここで履歴変化通知（Undo enable を更新できるようにする）
                    notifyHistoryChanged()
                }
                currentPath = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    /**
     * 現在の描画（ストロークのみ）を「透過背景」でBitmap化して返す。
     */
    fun exportStrokesBitmapTransparent(): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bmp = createBitmap(w, h)
        val canvas = Canvas(bmp)

        // 透明クリア
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // 履歴ストローク描画
        for (s in strokes) {
            val p = Paint(paintTemplate).apply { strokeWidth = s.strokeWidthPx }
            canvas.drawPath(s.path, p)
        }

        // 描画中ストローク（念のため）
        val cp = currentPath
        if (cp != null) {
            val p = Paint(paintTemplate).apply { strokeWidth = currentStrokeWidthPx }
            canvas.drawPath(cp, p)
        }

        return bmp
    }
}
