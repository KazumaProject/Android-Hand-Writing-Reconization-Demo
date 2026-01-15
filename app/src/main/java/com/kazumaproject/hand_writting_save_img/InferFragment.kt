package com.kazumaproject.hand_writting_save_img

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

class InferFragment : Fragment(R.layout.fragment_infer) {

    private var _binding: FragmentInferBinding? = null
    private val binding get() = _binding!!

    private lateinit var recognizer: HiraCtcRecognizer

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ---- preview ----
    private var lastPreviewBitmap: Bitmap? = null

    // ---- active side ----
    private var activeSide: DualDrawingComposerView.Side = DualDrawingComposerView.Side.A

    // ---- composed strings ----
    private val committed = StringBuilder()
    private var pendingA: String = ""
    private var pendingB: String = ""

    // ---- jobs ----
    private var inferJobA: Job? = null
    private var inferJobB: Job? = null
    private var commitOnSwitchJob: Job? = null

    // SAF: 端末から .pt を選択
    private val pickPtLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onPickedPt(uri)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentInferBinding.bind(view)

        loadRecognizerFromPrefsOrDefault()
        updateComposedText()

        // ---- Stroke width init & SeekBar (Preference復元) ----
        val savedStrokePx = prefs.getInt(KEY_STROKE_WIDTH_PX, DEFAULT_STROKE_WIDTH_PX)
        val maxStroke = binding.strokeSeekBarInfer.max.coerceAtLeast(1)
        val initialProgress = savedStrokePx.coerceIn(1, maxStroke)

        binding.strokeSeekBarInfer.progress = initialProgress
        binding.dualDrawingViewInfer.setStrokeWidthPx(initialProgress.toFloat())
        binding.strokeValueInfer.text = "${initialProgress} px"

        binding.strokeSeekBarInfer.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val px = progress.coerceAtLeast(1)
                binding.dualDrawingViewInfer.setStrokeWidthPx(px.toFloat())
                binding.strokeValueInfer.text = "${px} px"
                saveStrokeWidthPx(px)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val px = seekBar.progress.coerceAtLeast(1)
                saveStrokeWidthPx(px)
            }
        })

        // ---- Preview switch ----
        binding.previewSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.previewImageViewInfer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) clearPreview()
        }
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

        // ---- Dual view callbacks ----
        binding.dualDrawingViewInfer.onStrokeStarted = { side ->
            activeSide = side
            val other = otherSide(side)

            // 「別のDrawingViewに指が触れたタイミング」= ACTION_DOWN
            // -> 反対側の pending（検知中文字）を確定して committed に移す
            commitOnSwitchJob?.cancel()
            commitOnSwitchJob = lifecycleScope.launch {
                commitSideIfPendingExists(other, reason = "touch_switch_to_${side.name}")
            }
        }

        binding.dualDrawingViewInfer.onStrokeCommitted = { side ->
            // ストローク確定で推論（自動）
            scheduleInferForPending(side, reason = "stroke_committed")
        }

        // ---- Backspace（最後の1文字削除）----
        binding.deleteLastButton.setOnClickListener {
            deleteLastFromComposed()
        }

        // ---- Clear ----
        binding.clearInferButton.setOnClickListener {
            binding.dualDrawingViewInfer.clearBoth()

            inferJobA?.cancel()
            inferJobB?.cancel()
            commitOnSwitchJob?.cancel()

            committed.clear()
            pendingA = ""
            pendingB = ""
            updateComposedText()

            binding.resultText.text = "(cleared)"
            clearPreview()
        }
    }

    private fun otherSide(side: DualDrawingComposerView.Side): DualDrawingComposerView.Side {
        return if (side == DualDrawingComposerView.Side.A) {
            DualDrawingComposerView.Side.B
        } else {
            DualDrawingComposerView.Side.A
        }
    }

    private fun updateComposedText() {
        // 未確定は [] で表示（見た目を変えたくないなら [] を外してください）
        val pendingDisplay = buildString {
            if (pendingA.isNotBlank()) append("[").append(pendingA).append("]")
            if (pendingB.isNotBlank()) append("[").append(pendingB).append("]")
        }

        val full = committed.toString() + pendingDisplay
        binding.composedText.text = if (full.isBlank()) "(composed)" else full
    }

    private fun saveStrokeWidthPx(px: Int) {
        prefs.edit().putInt(KEY_STROKE_WIDTH_PX, px.coerceAtLeast(1)).apply()
    }

    private fun drawingViewFor(side: DualDrawingComposerView.Side): DrawingView {
        return if (side == DualDrawingComposerView.Side.A) {
            binding.dualDrawingViewInfer.viewA
        } else {
            binding.dualDrawingViewInfer.viewB
        }
    }

    /**
     * composedText の「最後の1文字」を削除する。
     * 優先順位: pendingB -> pendingA -> committed
     * pending を消す場合は、その side のキャンバスもクリアする（取り消しとして自然）
     */
    private fun deleteLastFromComposed() {
        // まず pending を優先して削除
        if (pendingB.isNotBlank()) {
            pendingB = ""
            drawingViewFor(DualDrawingComposerView.Side.B).clearCanvas()
            inferJobB?.cancel()
            updateComposedText()
            binding.resultText.text = "Deleted: pendingB"
            clearPreview()
            return
        }

        if (pendingA.isNotBlank()) {
            pendingA = ""
            drawingViewFor(DualDrawingComposerView.Side.A).clearCanvas()
            inferJobA?.cancel()
            updateComposedText()
            binding.resultText.text = "Deleted: pendingA"
            clearPreview()
            return
        }

        // committed の最後のコードポイントを削除（サロゲート対応）
        if (committed.isNotEmpty()) {
            removeLastCodePoint(committed)
            updateComposedText()
            binding.resultText.text = "Deleted: committed last char"
            // committed は履歴なのでプレビューはそのままでも良いが、混乱防止で消す
            clearPreview()
            return
        }

        // 何もない
        binding.resultText.text = "(nothing to delete)"
        clearPreview()
    }

    private fun removeLastCodePoint(sb: StringBuilder) {
        if (sb.isEmpty()) return
        val end = sb.length
        val cp = Character.codePointBefore(sb, end)
        val removeLen = Character.charCount(cp)
        sb.delete(end - removeLen, end)
    }

    /**
     * 反対側タッチ時に「pending があればそれを確定」する。
     * pending が無いがインクがある場合は、即推論して確定しても良い（安全策）
     */
    private suspend fun commitSideIfPendingExists(
        sideToCommit: DualDrawingComposerView.Side,
        reason: String
    ) {
        val dv = drawingViewFor(sideToCommit)

        val pending = if (sideToCommit == DualDrawingComposerView.Side.A) pendingA else pendingB

        if (pending.isBlank()) {
            // pending がまだ無いが ink はあるケース（推論が間に合っていない等）
            // -> ここで即推論して pending を作ってから確定
            if (dv.hasInk()) {
                inferPendingNow(sideToCommit, reason = "commit_fallback_infer")
            }
        }

        val finalPending =
            if (sideToCommit == DualDrawingComposerView.Side.A) pendingA else pendingB
        if (finalPending.isBlank()) return

        // 確定
        committed.append(finalPending)

        // pending を消す
        if (sideToCommit == DualDrawingComposerView.Side.A) {
            pendingA = ""
        } else {
            pendingB = ""
        }

        // 確定した側はクリア
        dv.clearCanvas()

        updateComposedText()

        binding.resultText.text =
            "Committed: $finalPending (side=${sideToCommit.name}, reason=$reason)"
    }

    /**
     * stroke committed 後に pending を更新（debounce）
     */
    private fun scheduleInferForPending(side: DualDrawingComposerView.Side, reason: String) {
        if (side == DualDrawingComposerView.Side.A) {
            inferJobA?.cancel()
            inferJobA = lifecycleScope.launch {
                delay(180)
                inferPendingNow(side, reason)
            }
        } else {
            inferJobB?.cancel()
            inferJobB = lifecycleScope.launch {
                delay(180)
                inferPendingNow(side, reason)
            }
        }
    }

    private suspend fun inferPendingNow(side: DualDrawingComposerView.Side, reason: String) {
        val dv = drawingViewFor(side)
        if (!dv.hasInk()) {
            // インクが無いなら pending を消す
            if (side == DualDrawingComposerView.Side.A) pendingA = "" else pendingB = ""
            updateComposedText()
            return
        }

        try {
            val strokeWidthPx = binding.strokeSeekBarInfer.progress.toFloat().coerceAtLeast(1f)
            val whiteBmp = exportForInfer(dv, strokeWidthPx)

            val wantPreview = binding.previewSwitch.isChecked

            val uiResult = withContext(Dispatchers.Default) {
                runSingleInfer(whiteBmp, wantPreview)
            }

            val detected = extractDetectedText(uiResult.text)

            if (side == DualDrawingComposerView.Side.A) {
                pendingA = detected
            } else {
                pendingB = detected
            }

            updateComposedText()

            // 参考表示（必要なら削除可）
            binding.resultText.text = buildString {
                append("Detected (pending) side=").append(side.name)
                    .append(" reason=").append(reason).append("\n")
                append("pending=").append(if (detected.isBlank()) "(empty)" else detected)
                    .append("\n\n")
                append(uiResult.text)
            }.trimEnd()

            if (wantPreview) setPreview(uiResult.preview) else clearPreview()

        } catch (e: Exception) {
            binding.resultText.text = "Error: ${e.message}"
            clearPreview()
        }
    }

    private data class InferUiResult(
        val text: String,
        val preview: Bitmap?
    )

    /**
     * “検知されている文字” として pending に入れる文字を抽出
     * - single: 1位候補
     */
    private fun extractDetectedText(resultText: String): String {
        val top1 = Regex("""^1\)\s*([^\s]+)\s+""", RegexOption.MULTILINE)
            .find(resultText)?.groupValues?.getOrNull(1)?.trim()

        return top1.orEmpty()
    }

    // ---------------- infer code ----------------

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

        private const val KEY_STROKE_WIDTH_PX = "infer_stroke_width_px"
        private const val DEFAULT_STROKE_WIDTH_PX = 14

        private const val DEFAULT_MODEL_ASSET = "model_torchscript.pt"
        private const val DEFAULT_VOCAB_ASSET = "vocab.json"

        private const val USER_MODEL_FILENAME = "user_selected_model.pt"
    }
}
