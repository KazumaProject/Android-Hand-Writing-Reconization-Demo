package com.kazumaproject.hand_writting_save_img

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.createBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.kazumaproject.hand_writting_save_img.databinding.FragmentInferBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

class InferFragment : Fragment(R.layout.fragment_infer) {

    private var _binding: FragmentInferBinding? = null
    private val binding get() = _binding!!

    private lateinit var recognizer: HiraCtcRecognizer

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ---- auto infer (optional) ----
    private var autoInferJob: Job? = null
    private var lastInferredChangeCounter: Long = -1L

    // ---- preview ----
    private var lastPreviewBitmap: Bitmap? = null

    // SAF: 端末から .pt を選択
    private val pickPtLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onPickedPt(uri)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentInferBinding.bind(view)

        loadRecognizerFromPrefsOrDefault()

        // ---- Stroke width init & SeekBar (Preference復元) ----
        // SeekBar max は XML 側に依存するので、まず max を見た上で clamp する
        val savedStrokePx = prefs.getInt(KEY_STROKE_WIDTH_PX, DEFAULT_STROKE_WIDTH_PX)
        val maxStroke = binding.strokeSeekBarInfer.max.coerceAtLeast(1)
        val initialProgress = savedStrokePx.coerceIn(1, maxStroke)

        binding.strokeSeekBarInfer.progress = initialProgress
        binding.drawingViewInfer.setStrokeWidthPx(initialProgress.toFloat())
        binding.strokeValueInfer.text = "${initialProgress} px"
        //binding.drawingViewInfer.setGuideGridStepPx(64)

        binding.strokeSeekBarInfer.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val px = progress.coerceAtLeast(1)
                binding.drawingViewInfer.setStrokeWidthPx(px.toFloat())
                binding.strokeValueInfer.text = "${px} px"

                // ここで都度保存（スライド中も保存したい場合）
                // ユーザーが以前そうしていた可能性があるため、ここで保存しておく
                saveStrokeWidthPx(px)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // 念のため停止時にも保存
                val px = seekBar.progress.coerceAtLeast(1)
                saveStrokeWidthPx(px)
            }
        })

        // ---- Preview switch ----
        binding.previewSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.previewImageViewInfer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                clearPreview()
            }
        }
        // 初期状態（XMLのcheckedに従う）
        binding.previewImageViewInfer.visibility =
            if (binding.previewSwitch.isChecked) View.VISIBLE else View.GONE

        // ---- Choose model (.pt) ----
        binding.chooseModelButton.setOnClickListener {
            pickPtLauncher.launch(arrayOf("*/*"))
        }

        // ---- Reset to default ----
        binding.resetModelButton.setOnClickListener {
            prefs.edit().remove(KEY_MODEL_URI).apply()
            deleteUserModelFile()

            loadRecognizerDefault()
            updateModelStatusTextDefault("(reset)")
            binding.resultText.text = "(reset to default)"
            clearPreview()
        }

        // ---- Auto infer switch (optional) ----
        binding.autoInferSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                autoInferJob?.cancel()
                binding.resultText.text = "(auto infer off)"
            } else {
                scheduleAutoInfer("switch_on")
            }
        }

        // DrawingViewからの通知（ストローク確定）
        binding.drawingViewInfer.onStrokeCommitted = {
            if (binding.autoInferSwitch.isChecked) {
                scheduleAutoInfer("stroke_committed")
            }
        }

        // ---- Manual Infer ----
        binding.inferButton.setOnClickListener {
            lifecycleScope.launch { runInferAndShow() }
        }

        // ---- Clear ----
        binding.clearInferButton.setOnClickListener {
            binding.drawingViewInfer.clearCanvas()
            autoInferJob?.cancel()
            lastInferredChangeCounter = -1L
            binding.resultText.text = "(cleared)"
            clearPreview()
        }
    }

    private fun saveStrokeWidthPx(px: Int) {
        prefs.edit().putInt(KEY_STROKE_WIDTH_PX, px.coerceAtLeast(1)).apply()
    }

    private fun scheduleAutoInfer(reason: String) {
        autoInferJob?.cancel()
        autoInferJob = lifecycleScope.launch {
            delay(250) // debounce
            val cc = binding.drawingViewInfer.getChangeCounter()
            if (cc == lastInferredChangeCounter) return@launch

            runInferAndShow()
            lastInferredChangeCounter = cc
        }
    }

    private data class InferUiResult(
        val text: String,
        val preview: Bitmap?
    )

    private suspend fun runInferAndShow() {
        try {
            val strokeWidthPx = binding.strokeSeekBarInfer.progress.toFloat().coerceAtLeast(1f)
            val whiteBmpForSegAndSingle = exportForInfer(binding.drawingViewInfer, strokeWidthPx)

            val splitMode = binding.splitInferSwitch.isChecked
            val wantPreview = binding.previewSwitch.isChecked

            val uiResult = withContext(Dispatchers.Default) {
                if (!splitMode) {
                    runSingleInfer(whiteBmpForSegAndSingle, wantPreview)
                } else {
                    runSplitInfer(whiteBmpForSegAndSingle, wantPreview)
                }
            }

            binding.resultText.text = uiResult.text
            if (wantPreview) {
                setPreview(uiResult.preview)
            } else {
                clearPreview()
            }
        } catch (e: Exception) {
            binding.resultText.text = "Error: ${e.message}"
            clearPreview()
        }
    }

    private fun runSingleInfer(whiteBmp: Bitmap, wantPreview: Boolean): InferUiResult {
        val normalized = BitmapPreprocessor.tightCenterSquare(
            srcWhiteBg = whiteBmp,
            inkThresh = 245,
            innerPadPx = 8,
            outerMarginPx = 24,
            minSidePx = 96
        )

        val candidates = recognizer.inferTopK(
            bitmap = normalized,
            topK = 5,
            beamWidth = 25,
            perStepTop = 25
        )

        val text = formatCandidatesSingle(candidates)
        val preview = if (wantPreview) normalized else null
        return InferUiResult(text = text, preview = preview)
    }

    private fun runSplitInfer(whiteBmp: Bitmap, wantPreview: Boolean): InferUiResult {
        val cfg = InkSegmenter.SegConfig(
            inkThresh = 245,
            minArea = 25,
            clusterPadPx = 10,
            maxChars = 16,
            wideBoxSplitRatio = 1.35f,
            projectionMinGapPx = 4,
            projectionWindowPx = 3
        )

        val rects = InkSegmenter.segment(whiteBmp, cfg)
        if (rects.isEmpty()) {
            return InferUiResult(text = "No ink detected.", preview = null)
        }

        val perChar = ArrayList<List<CtcCandidate>>(rects.size)
        val normalizedCharsForPreview = ArrayList<Bitmap>(rects.size)
        val top1 = StringBuilder()

        for (r in rects) {
            val cropped = cropWithPad(whiteBmp, r, pad = 10)

            val normalized = BitmapPreprocessor.tightCenterSquare(
                srcWhiteBg = cropped,
                inkThresh = cfg.inkThresh,
                innerPadPx = 6,
                outerMarginPx = 18,
                minSidePx = 72
            )

            val candidates = recognizer.inferTopK(
                bitmap = normalized,
                topK = 5,
                beamWidth = 25,
                perStepTop = 25
            )

            val best = candidates.firstOrNull()?.text.orEmpty()
            if (best.isNotEmpty()) top1.append(best)
            perChar.add(candidates)

            if (wantPreview) {
                normalizedCharsForPreview.add(normalized)
            }
        }

        val sb = StringBuilder()
        sb.append("Mode: split\n")
        sb.append("Detected chars: ${rects.size}\n")
        sb.append("Predicted: ").append(if (top1.isNotEmpty()) top1.toString() else "(empty)")
            .append("\n\n")

        sb.append("Per-char TopK:\n")
        for (i in perChar.indices) {
            sb.append("[").append(i + 1).append("]\n")
            sb.append(formatCandidatesList(perChar[i]))
            sb.append("\n")
        }

        val preview = if (wantPreview) {
            val cols = min(4, max(1, normalizedCharsForPreview.size))
            BitmapPreprocessor.composeGrid(
                images = normalizedCharsForPreview,
                cellSizePx = 128,
                cols = cols,
                padPx = 10
            )
        } else null

        return InferUiResult(text = sb.toString().trimEnd(), preview = preview)
    }

    private fun cropWithPad(src: Bitmap, r: Rect, pad: Int): Bitmap {
        val left = (r.left - pad).coerceAtLeast(0)
        val top = (r.top - pad).coerceAtLeast(0)
        val right = (r.right + pad).coerceAtMost(src.width)
        val bottom = (r.bottom + pad).coerceAtMost(src.height)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(src, left, top, w, h)
    }

    private fun formatCandidatesSingle(candidates: List<CtcCandidate>): String {
        if (candidates.isEmpty()) return "No result"
        val sb = StringBuilder()
        sb.append("Mode: single\n")
        sb.append("Top candidates:\n")
        candidates.forEachIndexed { i, c ->
            sb.append("${i + 1}) ${c.text}  ${"%.1f".format(c.percent)}%\n")
        }
        return sb.toString().trimEnd()
    }

    private fun formatCandidatesList(candidates: List<CtcCandidate>): String {
        if (candidates.isEmpty()) return "  (no result)\n"
        val sb = StringBuilder()
        candidates.forEachIndexed { i, c ->
            sb.append("  ${i + 1}) ${c.text}  ${"%.1f".format(c.percent)}%\n")
        }
        return sb.toString()
    }

    private fun loadRecognizerFromPrefsOrDefault() {
        val uriStr = prefs.getString(KEY_MODEL_URI, null)
        if (uriStr.isNullOrBlank()) {
            loadRecognizerDefault()
            updateModelStatusTextDefault()
            return
        }

        val uri = Uri.parse(uriStr)
        val ok = tryLoadRecognizerFromUri(uri)

        if (!ok) {
            prefs.edit().remove(KEY_MODEL_URI).apply()
            deleteUserModelFile()

            loadRecognizerDefault()
            updateModelStatusTextDefault("(fallback)")
            binding.resultText.text = "Invalid pt -> fallback to default"
            clearPreview()
        }
    }

    private fun loadRecognizerDefault() {
        recognizer = HiraCtcRecognizer(
            context = requireContext(),
            modelAssetName = DEFAULT_MODEL_ASSET,
            modelFilePath = null,
            vocabAssetName = DEFAULT_VOCAB_ASSET
        )
    }

    private fun onPickedPt(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }

        val ok = tryLoadRecognizerFromUri(uri)

        if (ok) {
            prefs.edit().putString(KEY_MODEL_URI, uri.toString()).apply()

            val name = getDisplayName(requireContext().contentResolver, uri) ?: "selected.pt"
            binding.modelStatusText.text = "Model: device/$name"
            binding.resultText.text = "(model loaded)"
        } else {
            prefs.edit().remove(KEY_MODEL_URI).apply()
            deleteUserModelFile()

            loadRecognizerDefault()
            updateModelStatusTextDefault("(fallback)")
            binding.resultText.text = "Invalid pt -> fallback to default"
            clearPreview()
        }
    }

    private fun tryLoadRecognizerFromUri(uri: Uri): Boolean {
        return try {
            val localFile = copyUriToInternalFile(uri, USER_MODEL_FILENAME)

            recognizer = HiraCtcRecognizer(
                context = requireContext(),
                modelAssetName = DEFAULT_MODEL_ASSET,
                modelFilePath = localFile.absolutePath,
                vocabAssetName = DEFAULT_VOCAB_ASSET
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyUriToInternalFile(uri: Uri, filename: String): File {
        val dir = File(requireContext().filesDir, "models")
        if (!dir.exists()) dir.mkdirs()

        val outFile = File(dir, filename)

        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot open uri input stream")

        return outFile
    }

    private fun deleteUserModelFile() {
        val f = File(File(requireContext().filesDir, "models"), USER_MODEL_FILENAME)
        if (f.exists()) runCatching { f.delete() }
    }

    private fun getDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateModelStatusTextDefault(suffix: String = "") {
        val s = if (suffix.isBlank()) "" else " $suffix"
        binding.modelStatusText.text = "Model: assets/$DEFAULT_MODEL_ASSET$s"
    }

    /**
     * - 描画が端でクリップされる問題を防ぐため、ストロークのエクスポート時点で外枠(border)を付ける
     * - 背景は白に統一して返す（InkSegmenter/Recognizer前提）
     */
    private fun exportForInfer(drawingView: DrawingView, strokeWidthPx: Float): Bitmap {
        val border = max(24, (strokeWidthPx * 2.2f).toInt())
        val strokes = drawingView.exportStrokesBitmapTransparent(borderPx = border)

        val out = createBitmap(strokes.width, strokes.height)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(strokes, 0f, 0f, null)
        return out
    }

    private fun setPreview(bmp: Bitmap?) {
        lastPreviewBitmap?.let { old ->
            if (!old.isRecycled) runCatching { old.recycle() }
        }
        lastPreviewBitmap = bmp
        binding.previewImageViewInfer.setImageBitmap(bmp)
    }

    private fun clearPreview() {
        setPreview(null)
    }

    override fun onDestroyView() {
        clearPreview()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val PREFS_NAME = "infer_prefs"
        private const val KEY_MODEL_URI = "infer_model_uri"

        // 追加：線の太さを保存
        private const val KEY_STROKE_WIDTH_PX = "infer_stroke_width_px"
        private const val DEFAULT_STROKE_WIDTH_PX = 14

        private const val DEFAULT_MODEL_ASSET = "model_torchscript.pt"
        private const val DEFAULT_VOCAB_ASSET = "vocab.json"

        private const val USER_MODEL_FILENAME = "user_selected_model.pt"
    }
}
