package com.kazumaproject.hand_writting_save_img

import android.content.Context
import android.graphics.Bitmap
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor

class HiraCtcRecognizer(
    context: Context,
    modelAssetName: String = "model_torchscript.pt",
    vocabAssetName: String = "vocab.json",
    private val cfg: PreprocessConfig = PreprocessConfig()
) {
    private val module: Module
    private val vocab: CtcVocab

    init {
        val modelPath = AssetUtil.assetFilePath(context, modelAssetName)
        module = Module.load(modelPath)

        val vocabJson =
            context.assets.open(vocabAssetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        vocab = CtcVocab.fromJsonString(vocabJson)
    }

    /**
     * 既存: greedy 1件
     */
    fun infer(bitmap: Bitmap): String {
        val prep = HiraCtcPreprocess.run(bitmap, cfg)

        val input = Tensor.fromBlob(
            prep.chw,
            longArrayOf(1, 1, prep.h.toLong(), prep.w.toLong())
        )

        val outTensor = module.forward(IValue.from(input)).toTensor()
        val shape = outTensor.shape()
        val out = outTensor.dataAsFloatArray

        val ids = greedyArgmaxIds(out, shape, prep.validTimeSteps)
        return vocab.decodeGreedy(ids)
    }

    /**
     * 追加: TopK + percent（上位候補内で正規化した相対%）
     */
    fun inferTopK(
        bitmap: Bitmap,
        topK: Int = 5,
        beamWidth: Int = 25,
        perStepTop: Int = 25
    ): List<CtcCandidate> {
        val prep = HiraCtcPreprocess.run(bitmap, cfg)

        val input = Tensor.fromBlob(
            prep.chw,
            longArrayOf(1, 1, prep.h.toLong(), prep.w.toLong())
        )

        val outTensor = module.forward(IValue.from(input)).toTensor()
        val shape = outTensor.shape()
        val data = outTensor.dataAsFloatArray

        val logProbs = extractLogProbsTV(data, shape, prep.validTimeSteps) // [T][V] Double
        val top = CtcBeamSearch.decodeTopK(
            logProbs = logProbs,
            idToChar = { id ->
                // blank(0) は空でOK
                if (id == 0) "" else vocab.idToChar(id)
            },
            topK = topK,
            beamWidth = beamWidth,
            perStepTop = perStepTop
        )

        val candidates = CtcBeamSearch.toPercents(top)
            .map { c ->
                // prefix beam search は文字列そのまま返すが、念のため空や重複を軽く整形
                val t = c.text
                CtcCandidate(text = t, percent = c.percent)
            }

        // 空文字だけ等が先頭に来た場合の見栄え調整（必要なら）
        return candidates.filter { it.text.isNotEmpty() }.ifEmpty { candidates }
    }

    /**
     * outTensor の shape を吸収して [T][V] の logProbs を取り出す
     * shape 優先順位は greedy と同様:
     *  - [T,1,V]
     *  - [1,T,V]
     *  - [T,V]
     */
    private fun extractLogProbsTV(
        data: FloatArray,
        shape: LongArray,
        validTimeSteps: Int
    ): Array<DoubleArray> {
        val dims = shape.size
        val (tDim, vDim, layout) = when {
            dims == 3 && shape[1] == 1L -> Triple(shape[0].toInt(), shape[2].toInt(), "T1V")
            dims == 3 && shape[0] == 1L -> Triple(shape[1].toInt(), shape[2].toInt(), "1TV")
            dims == 2 -> Triple(shape[0].toInt(), shape[1].toInt(), "TV")
            else -> throw IllegalStateException("Unexpected output shape: ${shape.joinToString()}")
        }

        val T = minOf(tDim, validTimeSteps)
        val V = vDim

        val out = Array(T) { DoubleArray(V) }

        for (t in 0 until T) {
            for (v in 0 until V) {
                val idx = when (layout) {
                    "T1V" -> (t * 1 * V) + v
                    "1TV" -> (0 * tDim * V) + (t * V) + v
                    "TV" -> (t * V) + v
                    else -> 0
                }
                out[t][v] = data[idx].toDouble()
            }
        }
        return out
    }

    private fun greedyArgmaxIds(
        data: FloatArray,
        shape: LongArray,
        validTimeSteps: Int
    ): IntArray {
        val dims = shape.size
        val (tDim, vDim, layout) = when {
            dims == 3 && shape[1] == 1L -> Triple(shape[0].toInt(), shape[2].toInt(), "T1V")
            dims == 3 && shape[0] == 1L -> Triple(shape[1].toInt(), shape[2].toInt(), "1TV")
            dims == 2 -> Triple(shape[0].toInt(), shape[1].toInt(), "TV")
            else -> throw IllegalStateException("Unexpected output shape: ${shape.joinToString()}")
        }

        val T = minOf(tDim, validTimeSteps)
        val ids = IntArray(T)

        for (t in 0 until T) {
            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY

            for (v in 0 until vDim) {
                val idx = when (layout) {
                    "T1V" -> (t * 1 * vDim) + v
                    "1TV" -> (0 * tDim * vDim) + (t * vDim) + v
                    "TV" -> (t * vDim) + v
                    else -> 0
                }
                val value = data[idx]
                if (value > bestVal) {
                    bestVal = value
                    bestIdx = v
                }
            }
            ids[t] = bestIdx
        }
        return ids
    }
}
