package com.kazumaproject.hand_writting_save_img

import org.json.JSONObject

data class CtcVocab(val itos: List<String>) {
    val blank: Int = 0

    /**
     * CTC id (0=blank, 1..=characters) -> character string
     */
    fun idToChar(id: Int): String {
        if (id <= 0) return ""
        val idx = id - 1
        return if (idx in itos.indices) itos[idx] else ""
    }

    fun decodeGreedy(ids: IntArray): String {
        val sb = StringBuilder()
        var prev: Int? = null
        for (id in ids) {
            if (id == blank) {
                prev = id
                continue
            }
            if (prev != null && prev == id) continue
            val idx = id - 1 // 1.. => itos[0..]
            if (idx in itos.indices) sb.append(itos[idx])
            prev = id
        }
        return sb.toString()
    }

    companion object {
        fun fromJsonString(json: String): CtcVocab {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("itos")
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) list.add(arr.getString(i))
            return CtcVocab(list)
        }
    }
}
