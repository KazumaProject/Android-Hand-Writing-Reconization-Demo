package com.kazumaproject.hand_writting_save_img

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.kazumaproject.hand_writting_save_img.databinding.ViewDualDrawingBinding

class DualDrawingComposerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val vb: ViewDualDrawingBinding =
        ViewDualDrawingBinding.inflate(LayoutInflater.from(context), this, true)

    val viewA: DrawingView get() = vb.drawingViewA
    val viewB: DrawingView get() = vb.drawingViewB

    enum class Side { A, B }

    /**
     * どちらで書き始めたか（ACTION_DOWN）
     */
    var onStrokeStarted: ((Side) -> Unit)? = null

    /**
     * どちらでストローク確定したか（ACTION_UP）
     */
    var onStrokeCommitted: ((Side) -> Unit)? = null

    init {
        orientation = VERTICAL

        vb.drawingViewA.onStrokeStarted = { onStrokeStarted?.invoke(Side.A) }
        vb.drawingViewB.onStrokeStarted = { onStrokeStarted?.invoke(Side.B) }

        vb.drawingViewA.onStrokeCommitted = { onStrokeCommitted?.invoke(Side.A) }
        vb.drawingViewB.onStrokeCommitted = { onStrokeCommitted?.invoke(Side.B) }
    }

    fun setStrokeWidthPx(px: Float) {
        vb.drawingViewA.setStrokeWidthPx(px)
        vb.drawingViewB.setStrokeWidthPx(px)
    }

    fun clearBoth() {
        vb.drawingViewA.clearCanvas()
        vb.drawingViewB.clearCanvas()
    }
}
