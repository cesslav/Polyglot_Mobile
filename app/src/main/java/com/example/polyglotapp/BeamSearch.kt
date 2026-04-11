package com.example.polyglotapp


object BeamSearch {

    private data class Beam(val tokens: LongArray, val score: Float)

    fun search(
        model: OnnxTransformer,
        memory: FloatArray,
        srcLen: Int,
        modelDim: Int,
        beamSize: Int = 4,
        maxLen: Int = 128,
        bosId: Long = 0L,
        eosId: Long = 1L
    ): LongArray {

        var beams: List<Beam> = listOf(Beam(longArrayOf(bosId), 0f))

        repeat(maxLen) step@{
            if (beams.all { it.tokens.last() == eosId }) return@step

            val candidates = ArrayList<Beam>(beamSize * (beamSize + 1))

            for (beam in beams) {
                if (beam.tokens.last() == eosId) {
                    candidates.add(beam)
                    continue
                }

                val logits   = model.decode(beam.tokens, memory, srcLen, modelDim)
                val tgtLen   = beam.tokens.size
                val vocabSize = logits.size / tgtLen

                val start     = (tgtLen - 1) * vocabSize
                val lastLogits = logits.copyOfRange(start, start + vocabSize)
                val logProbs  = logSoftmax(lastLogits)

                logProbs.indices
                    .sortedByDescending { logProbs[it] }
                    .take(beamSize)
                    .forEach { nextToken ->
                        val newTokens = beam.tokens.copyOf(beam.tokens.size + 1)
                        newTokens[newTokens.size - 1] = nextToken.toLong()
                        candidates.add(Beam(newTokens, beam.score + logProbs[nextToken]))
                    }
            }

            beams = candidates.sortedByDescending { it.score }.take(beamSize)
        }

        return beams.first().tokens
    }

    private fun logSoftmax(logits: FloatArray): FloatArray {
        val max = logits.max() ?: 0f
        var sumExp = 0.0
        val exp = FloatArray(logits.size) { i ->
            Math.exp((logits[i] - max).toDouble()).toFloat().also { sumExp += it }
        }
        val logSumExp = Math.log(sumExp).toFloat()
        return FloatArray(logits.size) { i -> logits[i] - max - logSumExp }
    }
}