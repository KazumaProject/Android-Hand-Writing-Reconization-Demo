package com.kazumaproject.hand_writting_save_img

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.core.graphics.createBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.kazumaproject.hand_writting_save_img.databinding.FragmentInferBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InferFragment : Fragment(R.layout.fragment_infer) {

    private var _binding: FragmentInferBinding? = null
    private val binding get() = _binding!!

    private lateinit var recognizer: HiraCtcRecognizer

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentInferBinding.bind(view)

        recognizer = HiraCtcRecognizer(requireContext())

        // ---- Stroke width init & SeekBar ----
        // 初期値を DrawingView に反映（ここが無いと太さが変わりません）
        val initialPx = binding.strokeSeekBarInfer.progress.toFloat().coerceAtLeast(1f)
        binding.drawingViewInfer.setStrokeWidthPx(initialPx)
        binding.strokeValueInfer.text = "${initialPx.toInt()} px"

        binding.strokeSeekBarInfer.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val px = progress.toFloat().coerceAtLeast(1f)
                binding.drawingViewInfer.setStrokeWidthPx(px)
                binding.strokeValueInfer.text = "${px.toInt()} px"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // ---- Infer ----
        binding.inferButton.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val bmp = exportForInfer(binding.drawingViewInfer)

                    val candidates = withContext(Dispatchers.Default) {
                        recognizer.inferTopK(
                            bitmap = bmp,
                            topK = 5,
                            beamWidth = 25,
                            perStepTop = 25
                        )
                    }

                    binding.resultText.text = formatCandidates(candidates)
                } catch (e: Exception) {
                    binding.resultText.text = "Error: ${e.message}"
                }
            }
        }

        // ---- Clear ----
        binding.clearInferButton.setOnClickListener {
            binding.drawingViewInfer.clearCanvas()
            binding.resultText.text = "(cleared)"
        }
    }

    private fun formatCandidates(candidates: List<CtcCandidate>): String {
        if (candidates.isEmpty()) return "No result"
        val sb = StringBuilder()
        sb.append("Top candidates:\n")
        candidates.forEachIndexed { i, c ->
            sb.append("${i + 1}) ${c.text}  ${"%.1f".format(c.percent)}%\n")
        }
        return sb.toString()
    }

    private fun exportForInfer(drawingView: DrawingView): Bitmap {
        val strokes = drawingView.exportStrokesBitmapTransparent()

        val out = createBitmap(strokes.width, strokes.height)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(strokes, 0f, 0f, null)
        return out
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
