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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class InferFragment : Fragment(R.layout.fragment_infer) {

    private var _binding: FragmentInferBinding? = null
    private val binding get() = _binding!!

    private lateinit var recognizer: HiraCtcRecognizer

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // SAF: 端末から .pt を選択
    private val pickPtLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                onPickedPt(uri)
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentInferBinding.bind(view)

        // ---- recognizer init (prefs に選択モデルがあればそれ / なければ assets) ----
        loadRecognizerFromPrefsOrDefault()

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

        // ---- Choose model (.pt) ----
        binding.chooseModelButton.setOnClickListener {
            // MIME で pt を厳密指定できない事が多いので "*/*" で選ばせて検証する
            pickPtLauncher.launch(arrayOf("*/*"))
        }

        // ---- Reset to default ----
        binding.resetModelButton.setOnClickListener {
            prefs.edit().remove(KEY_MODEL_URI).apply()
            deleteUserModelFile()

            loadRecognizerDefault()
            updateModelStatusTextDefault("(reset)")
            binding.resultText.text = "(reset to default)"
        }

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
            // 無効ならフォールバック
            prefs.edit().remove(KEY_MODEL_URI).apply()
            deleteUserModelFile()

            loadRecognizerDefault()
            updateModelStatusTextDefault("(fallback)")
            binding.resultText.text = "Invalid pt -> fallback to default"
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
        // persist permission（取れないケースもあるので失敗しても続行）
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
            // 無効ならデフォルトへ
            prefs.edit().remove(KEY_MODEL_URI).apply()
            deleteUserModelFile()

            loadRecognizerDefault()
            updateModelStatusTextDefault("(fallback)")
            binding.resultText.text = "Invalid pt -> fallback to default"
        }
    }

    /**
     * Uri を internal にコピーして Module.load が通るかで検証し、
     * 通ればそのモデルで recognizer を差し替える。
     */
    private fun tryLoadRecognizerFromUri(uri: Uri): Boolean {
        return try {
            val localFile = copyUriToInternalFile(uri, USER_MODEL_FILENAME)

            recognizer = HiraCtcRecognizer(
                context = requireContext(),
                modelAssetName = DEFAULT_MODEL_ASSET, // modelFilePath が優先されるので実質未使用
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
        if (f.exists()) {
            runCatching { f.delete() }
        }
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

    companion object {
        private const val PREFS_NAME = "infer_prefs"
        private const val KEY_MODEL_URI = "infer_model_uri"

        private const val DEFAULT_MODEL_ASSET = "model_torchscript.pt"
        private const val DEFAULT_VOCAB_ASSET = "vocab.json"

        private const val USER_MODEL_FILENAME = "user_selected_model.pt"
    }
}
