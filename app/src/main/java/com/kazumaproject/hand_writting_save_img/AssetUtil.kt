package com.kazumaproject.hand_writting_save_img

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object AssetUtil {
    fun assetFilePath(context: Context, assetName: String): String {
        val outFile = File(context.filesDir, assetName)
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath

        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                }
                output.flush()
            }
        }
        return outFile.absolutePath
    }
}
