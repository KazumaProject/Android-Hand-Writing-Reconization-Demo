package com.kazumaproject.hand_writting_save_img

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

private const val NEG_INF = -1e30

private data class BeamState(
    var pb: Double,   // log P(prefix ends with blank)
    var pnb: Double   // log P(prefix ends with non-blank)
)

object CtcBeamSearch {

    private fun logAddExp(a: Double, b: Double): Double {
        if (a <= NEG_INF) return b
        if (b <= NEG_INF) return a
        val m = max(a, b)
        return m + ln(exp(a - m) + exp(b - m))
    }

    private fun logSumExp(a: Double, b: Double): Double = logAddExp(a, b)

    private fun topIndices(arr: DoubleArray, k: Int): IntArray {
        // V ~ 80-100 程度ならこの実装で十分
        return arr.indices
            .sortedByDescending { arr[it] }
            .take(k)
            .toIntArray()
    }

    /**
     * @param logProbs [T][V] (CTC log_probs; log-softmax済みを想定)
     * @param idToChar non-blank id(1..) -> char, blank(0) は "" を返してOK
     * @return 上位 topK を percent 付きで返す（上位内で正規化）
     */
    fun decodeTopK(
        logProbs: Array<DoubleArray>,
        idToChar: (Int) -> String,
        topK: Int = 5,
        beamWidth: Int = 25,
        perStepTop: Int = 25
    ): List<Pair<String, Double>> {
        require(logProbs.isNotEmpty())
        val T = logProbs.size
        val V = logProbs[0].size

        var beams = HashMap<String, BeamState>().apply {
            this[""] = BeamState(pb = 0.0, pnb = NEG_INF)
        }

        for (t in 0 until T) {
            val lp = logProbs[t]
            val stepTopIds = topIndices(lp, minOf(perStepTop, V))

            val next = HashMap<String, BeamState>()

            fun getState(key: String): BeamState {
                return next.getOrPut(key) { BeamState(pb = NEG_INF, pnb = NEG_INF) }
            }

            for ((prefix, st) in beams) {
                val pb = st.pb
                val pnb = st.pnb
                val pTotal = logSumExp(pb, pnb)

                for (c in stepTopIds) {
                    val p = lp[c]

                    if (c == 0) {
                        // blank -> stay same prefix
                        val ns = getState(prefix)
                        ns.pb = logAddExp(ns.pb, pTotal + p)
                        continue
                    }

                    val ch = idToChar(c)
                    val last = if (prefix.isEmpty()) "" else prefix.substring(prefix.length - 1)

                    if (last == ch) {
                        // Extend with same char: only from pb
                        val nsNew = getState(prefix + ch)
                        nsNew.pnb = logAddExp(nsNew.pnb, pb + p)

                        // Repeat without blank: keep prefix from pnb
                        val nsSame = getState(prefix)
                        nsSame.pnb = logAddExp(nsSame.pnb, pnb + p)
                    } else {
                        // normal extend from total
                        val ns = getState(prefix + ch)
                        ns.pnb = logAddExp(ns.pnb, pTotal + p)
                    }
                }
            }

            // prune by total score
            val prunedKeys = next.entries
                .map { it.key to logSumExp(it.value.pb, it.value.pnb) }
                .sortedByDescending { it.second }
                .take(beamWidth)
                .map { it.first }
                .toSet()

            val pruned = HashMap<String, BeamState>(prunedKeys.size)
            for (k in prunedKeys) pruned[k] = next[k]!!
            beams = pruned
        }

        // final sort
        val finals = beams.entries
            .map { it.key to logSumExp(it.value.pb, it.value.pnb) }
            .sortedByDescending { it.second }
            .take(maxOf(1, topK))

        return finals
    }

    fun toPercents(top: List<Pair<String, Double>>): List<CtcCandidate> {
        if (top.isEmpty()) return emptyList()

        val maxLog = top.maxOf { it.second }
        val exps = top.map { exp(it.second - maxLog) }
        val sum = exps.sum().coerceAtLeast(1e-12)

        return top.mapIndexed { i, (text, _) ->
            val pct = 100.0 * (exps[i] / sum)
            CtcCandidate(text = text, percent = pct)
        }
    }
}
