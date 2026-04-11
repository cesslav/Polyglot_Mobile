package com.example.polyglotapp

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser


class UnigramTokenizer(context: Context) {

    var bosId: Int = 0; private set
    var eosId: Int = 1; private set
    var padId: Int = 3; private set
    var unkId: Int = 2; private set

    private val idToToken: Array<String>
    private val vocabScore: Map<String, Double>
    private val tokenToId: Map<String, Int>

    init {
        val raw = context.assets.open("tokenizer/tokenizer.json").bufferedReader().readText()
        val root = JsonParser.parseString(raw).asJsonObject

        // Специальные токены из added_tokens
        root.getAsJsonArray("added_tokens")?.forEach { el ->
            val obj     = el.asJsonObject
            val id      = obj.get("id")?.asInt      ?: return@forEach
            val content = obj.get("content")?.asString ?: return@forEach
            when {
                content.contains("bos", ignoreCase = true) -> bosId = id
                content.contains("eos", ignoreCase = true) -> eosId = id
                content.contains("unk", ignoreCase = true) -> unkId = id
                content.contains("pad", ignoreCase = true) -> padId = id
            }
        }
        Log.d(TAG, "Special tokens: bos=$bosId eos=$eosId pad=$padId unk=$unkId")

        val vocabArr = root.getAsJsonObject("model")
            ?.getAsJsonArray("vocab")
            ?: throw IllegalStateException("Не найден массив model.vocab в tokenizer.json")

        val size    = vocabArr.size()
        val mToId   = HashMap<String, Int>(size)
        val mScore  = HashMap<String, Double>(size)
        val mId     = Array(size) { "" }

        vocabArr.forEachIndexed { id, el ->
            val pair  = el.asJsonArray
            val token = pair[0].asString
            val score = pair[1].asDouble
            mId[id]       = token
            mScore[token] = score
            mToId[token]  = id
        }

        idToToken  = mId
        vocabScore = mScore
        tokenToId  = mToId
        Log.d(TAG, "Vocab loaded: $size tokens")
    }

    private fun viterbi(text: String): List<String> {
        val n = text.length
        if (n == 0) return emptyList()

        val dpScore = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val dpFrom  = IntArray(n + 1) { -1 }
        dpScore[0]  = 0.0

        for (end in 1..n) {
            val startMin = maxOf(0, end - MAX_TOKEN_LEN)
            for (start in startMin until end) {
                if (dpScore[start] == Double.NEGATIVE_INFINITY) continue
                val sub   = text.substring(start, end)
                val score = vocabScore[sub] ?: continue
                val total = dpScore[start] + score
                if (total > dpScore[end]) {
                    dpScore[end] = total
                    dpFrom[end]  = start
                }
            }
        }

        if (dpScore[n] == Double.NEGATIVE_INFINITY) {
            return text.map { it.toString() }
        }

        val result = mutableListOf<String>()
        var pos = n
        while (pos > 0) {
            val start = dpFrom[pos]
            result.add(text.substring(start, pos))
            pos = start
        }
        result.reverse()
        return result
    }

    private fun pretokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return ("▁" + text.replace(" ", "▁"))
            .split("▁")
            .filter { it.isNotEmpty() }
            .map { "▁$it" }
    }

    fun encode(text: String, maxLength: Int = 256): LongArray {
        val tokens = ArrayList<Int>(maxLength)
        tokens.add(bosId)

        for (piece in pretokenize(text)) {
            for (sub in viterbi(piece)) {
                tokens.add(tokenToId[sub] ?: unkId)
            }
        }

        tokens.add(eosId)

        val out = LongArray(maxLength) { padId.toLong() }
        repeat(minOf(tokens.size, maxLength)) { i -> out[i] = tokens[i].toLong() }
        return out
    }

    fun decode(ids: LongArray, skipSpecial: Boolean = true): String {
        val skip = if (skipSpecial) setOf(bosId, eosId, padId) else emptySet()

        val sb = StringBuilder()
        for (id in ids) {
            val intId = id.toInt()
            if (intId in skip || intId < 0 || intId >= idToToken.size) continue
            sb.append(idToToken[intId])
        }

        // ▁ → пробел, убираем ведущий пробел
        return sb.toString().replace('▁', ' ').trim()
    }

    companion object {
        private const val TAG = "UnigramTokenizer"
        private const val MAX_TOKEN_LEN = 32
    }
}