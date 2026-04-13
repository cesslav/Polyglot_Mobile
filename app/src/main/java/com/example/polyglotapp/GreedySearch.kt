package com.example.polyglotapp

object GreedySearch {

    fun search(
        model: OnnxTransformer,
        memory: FloatArray,
        srcLen: Int,
        modelDim: Int,
        maxLen: Int = 128,
        bosId: Long = 0L,
        eosId: Long = 1L
    ): LongArray {

        val tokens = LongArray(maxLen)
        tokens[0] = bosId
        var curLen = 1

        repeat(maxLen - 1) {
            val logits = model.decode(
                tokens.copyOf(curLen),
                memory,
                srcLen,
                modelDim
            )

            val vocabSize = logits.size / curLen
            val start = (curLen - 1) * vocabSize
            var maxIdx = 0
            var maxVal = logits[start]

            for (i in 1 until vocabSize) {
                val v = logits[start + i]
                if (v > maxVal) {
                    maxVal = v
                    maxIdx = i
                }
            }

            tokens[curLen] = maxIdx.toLong()
            curLen++

            if (maxIdx.toLong() == eosId) {
                return tokens.copyOf(curLen)
            }
        }

        return tokens.copyOf(curLen)
    }
}