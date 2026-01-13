package com.kazumaproject.hand_writting_save_img

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.graphics.createBitmap
import androidx.fragment.app.Fragment
import com.kazumaproject.hand_writting_save_img.databinding.FragmentSaveBinding
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SaveFragment : Fragment(R.layout.fragment_save) {

    private var _binding: FragmentSaveBinding? = null
    private val binding get() = _binding!!

    // ---- SharedPreferences ----
    private val prefs: SharedPreferences by lazy {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = FragmentSaveBinding.bind(view)

        // DrawingView 側から履歴変化通知を受けてボタン状態を更新
        binding.drawingView.onHistoryChanged = {
            updateUndoRedoEnabled()
        }

        // ---- Pen width: restore -> SeekBar -> DrawingView + label ----
        val savedWidthPx = prefs.getInt(KEY_PEN_WIDTH_PX, DEFAULT_PEN_WIDTH_PX)
            .coerceIn(MIN_PEN_WIDTH_PX, MAX_PEN_WIDTH_PX)

        binding.strokeSeekBar.max = MAX_PEN_WIDTH_PX
        binding.strokeSeekBar.progress = savedWidthPx

        applyPenWidth(savedWidthPx)

        binding.strokeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val widthPx = progress.coerceAtLeast(MIN_PEN_WIDTH_PX)
                applyPenWidth(widthPx)

                // save (毎回保存でOK。気になるなら onStopTrackingTouch で保存でも可)
                prefs.edit().putInt(KEY_PEN_WIDTH_PX, widthPx).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val widthPx = seekBar.progress.coerceAtLeast(MIN_PEN_WIDTH_PX)
                prefs.edit().putInt(KEY_PEN_WIDTH_PX, widthPx).apply()
            }
        })

        binding.clearButton.setOnClickListener {
            binding.drawingView.clearCanvas()
            // labelEditText はクリアしない（要件通り）
        }

        binding.undoButton.setOnClickListener {
            binding.drawingView.undo()
        }

        binding.redoButton.setOnClickListener {
            binding.drawingView.redo()
        }

        binding.saveButton.setOnClickListener {
            val rawLabel = binding.labelEditText.text?.toString().orEmpty().trim()
            if (rawLabel.isEmpty()) {
                Toast.makeText(requireContext(), "Label を入力してください", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }

            try {
                // 1) ストロークのみ（透過背景）で取り出す
                val strokesBmp = binding.drawingView.exportStrokesBitmapTransparent()

                // 2) トリミング（オンなら）
                val trimOnSave = binding.trimSwitch.isChecked
                val trimmedStrokesBmp = if (trimOnSave) {
                    cropToNonTransparentBounds(strokesBmp, padPx = 8)
                } else {
                    strokesBmp
                }

                // 3) 透過/白背景合成
                val transparent = binding.transparentSwitch.isChecked
                val finalBmp = if (transparent) {
                    trimmedStrokesBmp
                } else {
                    compositeOnWhiteBackground(trimmedStrokesBmp)
                }

                // 4) MediaStore 保存
                saveBitmapToMediaStorePictures(
                    bitmap = finalBmp,
                    label = rawLabel
                )

                // 5) 保存後：手書きだけクリア（EditTextは残す）
                binding.drawingView.clearCanvas()

                //Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {

            }
        }

        updateUndoRedoEnabled()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun updateUndoRedoEnabled() {
        binding.undoButton.isEnabled = binding.drawingView.canUndo()
        binding.redoButton.isEnabled = binding.drawingView.canRedo()
    }

    private fun applyPenWidth(widthPxInt: Int) {
        val widthPx = widthPxInt.toFloat().coerceAtLeast(1f)
        binding.drawingView.setStrokeWidthPx(widthPx)
        binding.penWidthValueText.text = "Pen width: ${widthPxInt} px"
    }

    /**
     * ギャラリーに見える Pictures/label_<label>/ 配下へ PNG 保存する
     * 戻り値: 保存した画像の Uri
     *
     * Android 10+ では通常WRITE権限不要
     */
    @Throws(IOException::class)
    private fun saveBitmapToMediaStorePictures(
        bitmap: Bitmap,
        label: String
    ): Uri {
        val safeLabelForFolder = sanitizeForPathPart(label, maxLen = 60)
        val safeLabelForFilename = sanitizeForFileName(label, maxLen = 40)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val displayName = "${safeLabelForFilename}_${timestamp}.png"

        val resolver = requireContext().contentResolver
        val collection: Uri =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val appFolder = sanitizeForPathPart(getString(R.string.app_name), maxLen = 40)
        val relativePath =
            "${Environment.DIRECTORY_PICTURES}/$appFolder/label_$safeLabelForFolder"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri =
            resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (!ok) throw IOException("Bitmap compress failed")
            } ?: throw IOException("OutputStream open failed")

            val finalizeValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, finalizeValues, null, null)

            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * 透過Bitmap上の「非透過ピクセル」領域を検出して、余白をpadPx分だけ残してトリミング
     */
    private fun cropToNonTransparentBounds(src: Bitmap, padPx: Int = 0): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val c = pixels[rowOffset + x]
                val a = (c ushr 24) and 0xFF
                if (a != 0) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < 0 || maxY < 0) {
            throw IllegalStateException("Nothing drawn.")
        }

        minX = (minX - padPx).coerceAtLeast(0)
        minY = (minY - padPx).coerceAtLeast(0)
        maxX = (maxX + padPx).coerceAtMost(w - 1)
        maxY = (maxY + padPx).coerceAtMost(h - 1)

        val outW = (maxX - minX + 1).coerceAtLeast(1)
        val outH = (maxY - minY + 1).coerceAtLeast(1)

        return Bitmap.createBitmap(src, minX, minY, outW, outH)
    }

    /**
     * 透過背景のストロークBitmapを白背景に合成
     */
    private fun compositeOnWhiteBackground(strokesTransparent: Bitmap): Bitmap {
        val out = createBitmap(
            strokesTransparent.width.coerceAtLeast(1),
            strokesTransparent.height.coerceAtLeast(1)
        )
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(strokesTransparent, 0f, 0f, null)
        return out
    }

    private fun sanitizeForPathPart(input: String, maxLen: Int): String {
        val s = input.trim()
            .replace("/", "_")
            .replace("\\", "_")
            .replace(":", "_")
            .replace("*", "_")
            .replace("?", "_")
            .replace("\"", "_")
            .replace("<", "_")
            .replace(">", "_")
            .replace("|", "_")
            .replace(Regex("\\s+"), "_")
            .ifEmpty { "unknown" }

        return if (s.length > maxLen) s.substring(0, maxLen) else s
    }

    private fun sanitizeForFileName(input: String, maxLen: Int): String {
        val s = input.trim()
            .replace("/", "_")
            .replace("\\", "_")
            .replace(":", "_")
            .replace("*", "_")
            .replace("?", "_")
            .replace("\"", "_")
            .replace("<", "_")
            .replace(">", "_")
            .replace("|", "_")
            .replace(Regex("\\s+"), "_")
            .ifEmpty { "label" }

        return if (s.length > maxLen) s.substring(0, maxLen) else s
    }

    companion object {
        private const val PREFS_NAME = "hand_writting_prefs"
        private const val KEY_PEN_WIDTH_PX = "pen_width_px"

        private const val MIN_PEN_WIDTH_PX = 1
        private const val MAX_PEN_WIDTH_PX = 60
        private const val DEFAULT_PEN_WIDTH_PX = 32
    }
}
