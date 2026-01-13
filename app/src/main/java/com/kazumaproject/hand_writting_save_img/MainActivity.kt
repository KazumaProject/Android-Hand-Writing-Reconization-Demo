package com.kazumaproject.hand_writting_save_img

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.kazumaproject.hand_writting_save_img.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private enum class Screen {
        SAVE, INFER
    }

    private var current: Screen = Screen.SAVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Insets: system bars + IME
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)

            v.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bottom
            )
            insets
        }

        // 初期表示
        if (savedInstanceState == null) {
            showScreen(Screen.SAVE, addToBackStack = false)
        } else {
            // 画面回転などで復元される場合、FragmentManager が面倒を見ます。
            // ボタン状態だけ合わせます。
            current = if (supportFragmentManager.findFragmentByTag(TAG_INFER) != null
                && supportFragmentManager.findFragmentById(R.id.fragmentContainer) is InferFragment
            ) {
                Screen.INFER
            } else {
                Screen.SAVE
            }
            updateToggleUi(current)
        }

        binding.toSaveButton.setOnClickListener {
            if (current != Screen.SAVE) showScreen(Screen.SAVE, addToBackStack = false)
        }
        binding.toInferButton.setOnClickListener {
            if (current != Screen.INFER) showScreen(Screen.INFER, addToBackStack = false)
        }

        updateToggleUi(current)
    }

    private fun showScreen(screen: Screen, addToBackStack: Boolean) {
        val fragment = when (screen) {
            Screen.SAVE -> SaveFragment()
            Screen.INFER -> InferFragment()
        }
        val tag = when (screen) {
            Screen.SAVE -> TAG_SAVE
            Screen.INFER -> TAG_INFER
        }

        val tx = supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment, tag)

        if (addToBackStack) {
            tx.addToBackStack(tag)
        }

        tx.commit()

        current = screen
        updateToggleUi(current)
    }

    private fun updateToggleUi(screen: Screen) {
        val saveSelected = (screen == Screen.SAVE)
        val inferSelected = (screen == Screen.INFER)

        binding.toSaveButton.isEnabled = !saveSelected
        binding.toInferButton.isEnabled = !inferSelected

        binding.toSaveButton.isSelected = saveSelected
        binding.toInferButton.isSelected = inferSelected
    }

    companion object {
        private const val TAG_SAVE = "screen_save"
        private const val TAG_INFER = "screen_infer"
    }
}
