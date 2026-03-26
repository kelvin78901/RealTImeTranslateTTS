package com.example.myapplication1.translation

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * On-device translation using ONNX Runtime with downloadable models.
 * Supports MarianMT (Opus-MT, SentencePiece Viterbi tokenizer) and NLLB (BPE tokenizer).
 * Decoding: beam search (configurable beam width) or greedy (beamSize=1).
 */
class OnDeviceTranslation(
    private val context: Context,
    private val model: OnDeviceTranslationModel
) : TranslationEngine() {

    companion object {
        private const val TAG = "OnDeviceTrans"
        private const val MAX_LENGTH = 128
        private const val LENGTH_PENALTY_ALPHA = 0.6f  // Google GNMT length normalization
        private var ortEnv: OrtEnvironment? = null

        private fun getEnv(): OrtEnvironment {
            if (ortEnv == null) ortEnv = OrtEnvironment.getEnvironment()
            return ortEnv!!
        }
    }

    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    // ==================== Tokenizer models ====================

    private sealed class TokenizerModel {
        /** SentencePiece Unigram: vocab is list of [token, score] (from tokenizer.json) */
        class Unigram(
            val pieces: List<Pair<String, Float>>,
            val tokenToId: Map<String, Int>,
            val idToToken: Map<Int, String>,
            val unkId: Int
        ) : TokenizerModel()

        /** BPE tokenizer (for NLLB, from tokenizer.json) */
        class Bpe(
            val vocab: Map<String, Long>,
            val idToToken: Map<Long, String>,
            val merges: List<Pair<String, String>>
        ) : TokenizerModel()

        /**
         * MarianMT with SentencePiece .spm model + vocab.json.
         * Uses Viterbi algorithm with piece scores from source.spm.
         * ID mapping comes from vocab.json (shared source/target vocab).
         */
        class SpmVocab(
            val vocab: Map<String, Long>,         // vocab.json: token string → ONNX model ID
            val idToToken: Map<Long, String>,      // reverse for detokenization
            val unkId: Long,
            val spmScores: Map<String, Float>,     // source.spm: piece → log-prob score
            val maxPieceLen: Int                   // max piece string length (search optimization)
        ) : TokenizerModel()
    }

    private var tokenizer: TokenizerModel? = null
    private var bosTokenId: Long = -1
    private var eosTokenId: Long = -1
    private var padTokenId: Long = -1
    private var decoderStartTokenId: Long = -1
    private var forcedBosTokenId: Long = -1
    @Volatile private var initialized = false
    var initError: String? = null
        private set

    // Cached decoder input names (detected once at init)
    private var decoderEncoderOutputName: String = "encoder_hidden_states"
    private var decoderAttMaskName: String? = null

    // ==================== Initialization ====================

    fun init(): Boolean {
        val dir = model.modelDir(context)
        if (!model.isDownloaded(context)) {
            initError = "模型未下载: ${model.name}"
            Log.e(TAG, initError!!)
            return false
        }

        try {
            val env = getEnv()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
            }

            Log.i(TAG, "Loading encoder...")
            encoderSession = env.createSession(File(dir, "encoder.onnx").absolutePath, opts)
            Log.i(TAG, "Encoder loaded. Inputs: ${encoderSession!!.inputNames}")

            Log.i(TAG, "Loading decoder...")
            decoderSession = env.createSession(File(dir, "decoder.onnx").absolutePath, opts)
            val decInputNames = decoderSession!!.inputNames
            Log.i(TAG, "Decoder loaded. Inputs: $decInputNames")

            // Cache decoder input name detection
            decoderEncoderOutputName = decInputNames.firstOrNull {
                it.contains("encoder") && (it.contains("hidden") || it.contains("last"))
            } ?: "encoder_hidden_states"
            decoderAttMaskName = decInputNames.firstOrNull {
                it.contains("encoder") && it.contains("attention_mask")
            }

            // Load tokenizer
            if (model.useVocabJson) {
                val spmFile = File(dir, "source.spm")
                loadSpmVocab(File(dir, "vocab.json"), if (spmFile.exists()) spmFile else null)
            } else {
                loadTokenizer(File(dir, "tokenizer.json"))
            }

            // Load config for special tokens
            loadConfig(File(dir, "config.json"))

            // For NLLB: set forced_bos_token_id to target language token
            if (model.tgtLangToken.isNotBlank()) {
                val tok = tokenizer
                when (tok) {
                    is TokenizerModel.Bpe -> {
                        val tgtId = tok.vocab[model.tgtLangToken]
                        if (tgtId != null) {
                            forcedBosTokenId = tgtId
                            Log.i(TAG, "NLLB target lang: ${model.tgtLangToken} → $tgtId")
                        }
                    }
                    else -> {}
                }
            }

            initialized = true
            initError = null
            Log.i(TAG, "Model ${model.label} initialized (beamSize=${model.defaultBeamSize})")
            return true
        } catch (e: Throwable) {
            initError = "初始化失败: ${e.message}"
            Log.e(TAG, initError!!, e)
            close()
            return false
        }
    }

    // ==================== Tokenizer loading ====================

    private fun loadTokenizer(file: File) {
        val json = JSONObject(file.readText())
        val modelObj = json.optJSONObject("model") ?: json
        val type = modelObj.optString("type", "BPE")
        when (type) {
            "Unigram" -> loadUnigramTokenizer(modelObj, json)
            "BPE" -> loadBpeTokenizer(modelObj)
            else -> throw Exception("Unsupported tokenizer type: $type")
        }
    }

    private fun loadUnigramTokenizer(modelObj: JSONObject, rootJson: JSONObject) {
        val vocabArr = modelObj.optJSONArray("vocab")
            ?: throw Exception("Unigram tokenizer missing vocab array")
        val unkId = modelObj.optInt("unk_id", 1)
        val pieces = mutableListOf<Pair<String, Float>>()
        val tokenToId = mutableMapOf<String, Int>()
        val idToToken = mutableMapOf<Int, String>()
        for (i in 0 until vocabArr.length()) {
            val item = vocabArr.getJSONArray(i)
            val token = item.getString(0)
            val score = item.getDouble(1).toFloat()
            pieces.add(token to score)
            tokenToId[token] = i
            idToToken[i] = token
        }
        val addedTokens = rootJson.optJSONArray("added_tokens")
        if (addedTokens != null) {
            for (i in 0 until addedTokens.length()) {
                val obj = addedTokens.getJSONObject(i)
                val content = obj.getString("content")
                val id = obj.getInt("id")
                if (!tokenToId.containsKey(content)) {
                    tokenToId[content] = id
                    idToToken[id] = content
                }
            }
        }
        tokenizer = TokenizerModel.Unigram(pieces, tokenToId, idToToken, unkId)
        Log.i(TAG, "Unigram tokenizer loaded: ${pieces.size} pieces")
    }

    private fun loadBpeTokenizer(modelObj: JSONObject) {
        val vocabObj = modelObj.optJSONObject("vocab")
            ?: throw Exception("BPE tokenizer missing vocab dict")
        val vocab = mutableMapOf<String, Long>()
        for (key in vocabObj.keys()) {
            vocab[key] = vocabObj.getLong(key)
        }
        val idToToken = vocab.entries.associate { it.value to it.key }
        val mergesArr = modelObj.optJSONArray("merges")
        val merges = mutableListOf<Pair<String, String>>()
        if (mergesArr != null) {
            for (i in 0 until mergesArr.length()) {
                val parts = mergesArr.getString(i).split(" ", limit = 2)
                if (parts.size == 2) merges.add(parts[0] to parts[1])
            }
        }
        tokenizer = TokenizerModel.Bpe(vocab, idToToken, merges)
        Log.i(TAG, "BPE tokenizer loaded: ${vocab.size} tokens, ${merges.size} merges")
    }

    /**
     * Load MarianMT tokenizer from vocab.json + optional source.spm.
     * If source.spm exists, piece scores enable Viterbi tokenization.
     * Otherwise falls back to greedy longest-match on vocab.json.
     */
    private fun loadSpmVocab(vocabFile: File, spmFile: File?) {
        val json = JSONObject(vocabFile.readText())
        val vocab = mutableMapOf<String, Long>()
        for (key in json.keys()) vocab[key] = json.getLong(key)
        val idToToken = vocab.entries.associate { it.value to it.key }
        val unkId = vocab["<unk>"] ?: 1L

        val spmScores = mutableMapOf<String, Float>()
        var maxPieceLen = 32
        if (spmFile != null) {
            val pieces = SpmParser.parse(spmFile.readBytes())
            for (p in pieces) {
                if (p.type == SpmParser.SpmPiece.TYPE_NORMAL ||
                    p.type == SpmParser.SpmPiece.TYPE_USER_DEFINED) {
                    spmScores[p.piece] = p.score
                    if (p.piece.length > maxPieceLen) maxPieceLen = p.piece.length
                }
            }
            Log.i(TAG, "SpmVocab loaded: ${vocab.size} vocab IDs, ${spmScores.size} spm pieces, maxLen=$maxPieceLen")
        } else {
            Log.i(TAG, "SpmVocab loaded (no .spm, greedy fallback): ${vocab.size} tokens")
        }

        tokenizer = TokenizerModel.SpmVocab(vocab, idToToken, unkId, spmScores, maxPieceLen)
    }

    private fun loadConfig(file: File) {
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            bosTokenId = json.optLong("bos_token_id", -1)
            eosTokenId = json.optLong("eos_token_id", -1)
            padTokenId = json.optLong("pad_token_id", if (eosTokenId >= 0) eosTokenId else 0)
            decoderStartTokenId = json.optLong("decoder_start_token_id", if (padTokenId >= 0) padTokenId else 0)
            Log.i(TAG, "Config: bos=$bosTokenId, eos=$eosTokenId, pad=$padTokenId, decoder_start=$decoderStartTokenId")
        } catch (e: Throwable) {
            Log.w(TAG, "Config parse error: ${e.message}")
        }
    }

    // ==================== Translate ====================

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        if (!initialized) throw Exception("Model not initialized" + (initError?.let { ": $it" } ?: ""))

        val inputIds = tokenize(text)
        if (inputIds.isEmpty()) throw Exception("Tokenization produced empty result")
        Log.d(TAG, "Input tokens: ${inputIds.size} ids")

        val encoderOutput = runEncoder(inputIds)

        val beamSize = model.defaultBeamSize
        val outputIds = if (beamSize > 1) {
            beamDecode(encoderOutput, inputIds.size, beamSize)
        } else {
            greedyDecode(encoderOutput, inputIds.size)
        }

        Log.d(TAG, "Output tokens: ${outputIds.size} ids")
        detokenize(outputIds)
    }

    // ==================== Tokenization ====================

    private fun tokenize(text: String): LongArray {
        return when (val tok = tokenizer ?: throw Exception("Tokenizer not loaded")) {
            is TokenizerModel.Unigram -> tokenizeUnigram(text, tok)
            is TokenizerModel.Bpe -> tokenizeBpe(text, tok)
            is TokenizerModel.SpmVocab -> tokenizeSpm(text, tok)
        }
    }

    /** Unigram tokenization using greedy longest-match (for tokenizer.json Unigram models). */
    private fun tokenizeUnigram(text: String, tok: TokenizerModel.Unigram): LongArray {
        val normalized = "▁" + text.trim().replace(" ", "▁")
        val ids = mutableListOf<Long>()
        var pos = 0
        while (pos < normalized.length) {
            var bestLen = 0
            var bestId = tok.unkId
            val maxLen = minOf(normalized.length - pos, 32)
            for (len in maxLen downTo 1) {
                val id = tok.tokenToId[normalized.substring(pos, pos + len)]
                if (id != null) { bestLen = len; bestId = id; break }
            }
            if (bestLen == 0) bestLen = 1
            ids.add(bestId.toLong())
            pos += bestLen
        }
        if (eosTokenId >= 0) ids.add(eosTokenId)
        return ids.toLongArray()
    }

    /**
     * SentencePiece tokenization for MarianMT.
     * Uses Viterbi algorithm with .spm piece scores for optimal segmentation.
     * Falls back to greedy longest-match if no .spm scores are loaded.
     * After segmentation, maps piece strings to vocab.json IDs.
     */
    private fun tokenizeSpm(text: String, tok: TokenizerModel.SpmVocab): LongArray {
        val processed = "▁" + text.trim().replace(" ", "▁")
        val pieces: List<String>

        if (tok.spmScores.isNotEmpty()) {
            // Viterbi optimal segmentation
            pieces = viterbiSegment(processed, tok)
        } else {
            // Greedy fallback (no .spm available)
            pieces = greedySegment(processed, tok)
        }

        // Map piece strings → vocab.json IDs
        val ids = pieces.map { piece -> tok.vocab[piece] ?: tok.unkId }.toMutableList()
        if (eosTokenId >= 0) ids.add(eosTokenId)
        return ids.toLongArray()
    }

    /**
     * Viterbi algorithm: find the segmentation with maximum total log-probability.
     * Time complexity: O(n * maxPieceLen) where n = text length.
     */
    private fun viterbiSegment(text: String, tok: TokenizerModel.SpmVocab): List<String> {
        val n = text.length
        // bestScore[i] = best (maximum) cumulative log-prob to reach position i
        val bestScore = FloatArray(n + 1) { if (it == 0) 0f else Float.NEGATIVE_INFINITY }
        val bestStart = IntArray(n + 1) { -1 }

        for (end in 1..n) {
            val maxLen = minOf(end, tok.maxPieceLen)
            for (len in 1..maxLen) {
                val start = end - len
                if (bestScore[start] == Float.NEGATIVE_INFINITY) continue
                val substr = text.substring(start, end)
                val score = tok.spmScores[substr] ?: continue
                val total = bestScore[start] + score
                if (total > bestScore[end]) {
                    bestScore[end] = total
                    bestStart[end] = start
                }
            }
            // Fallback for characters not in any piece: consume 1 char with unk penalty
            if (bestScore[end] == Float.NEGATIVE_INFINITY && bestScore[end - 1] > Float.NEGATIVE_INFINITY) {
                bestScore[end] = bestScore[end - 1] - 100f
                bestStart[end] = end - 1
            }
        }

        // Backtrack to recover pieces
        val result = mutableListOf<String>()
        var pos = n
        while (pos > 0) {
            val start = bestStart[pos]
            if (start < 0) { pos--; continue }
            result.add(0, text.substring(start, pos))
            pos = start
        }
        return result
    }

    /** Greedy longest-match segmentation (fallback when no .spm scores available). */
    private fun greedySegment(text: String, tok: TokenizerModel.SpmVocab): List<String> {
        val result = mutableListOf<String>()
        var pos = 0
        while (pos < text.length) {
            var bestLen = 0
            val maxLen = minOf(text.length - pos, tok.maxPieceLen)
            for (len in maxLen downTo 1) {
                if (tok.vocab.containsKey(text.substring(pos, pos + len))) {
                    bestLen = len
                    break
                }
            }
            if (bestLen == 0) bestLen = 1
            result.add(text.substring(pos, pos + bestLen))
            pos += bestLen
        }
        return result
    }

    /** BPE tokenization for NLLB. */
    private fun tokenizeBpe(text: String, tok: TokenizerModel.Bpe): LongArray {
        val ids = mutableListOf<Long>()
        if (model.srcLangToken.isNotBlank()) {
            tok.vocab[model.srcLangToken]?.let { ids.add(it) }
        }
        val words = text.trim().split(Regex("\\s+"))
        for ((wi, word) in words.withIndex()) {
            val processedWord = if (wi == 0 && model.srcLangToken.isBlank()) word else "▁$word"
            val exactId = tok.vocab[processedWord]
            if (exactId != null) { ids.add(exactId); continue }
            ids.addAll(bpeEncode(processedWord, tok))
        }
        if (eosTokenId >= 0) ids.add(eosTokenId)
        return ids.toLongArray()
    }

    private fun bpeEncode(word: String, tok: TokenizerModel.Bpe): List<Long> {
        if (word.isEmpty()) return emptyList()
        var tokens = word.map { it.toString() }.toMutableList()
        for ((first, second) in tok.merges) {
            var i = 0
            while (i < tokens.size - 1) {
                if (tokens[i] == first && tokens[i + 1] == second) {
                    tokens[i] = first + second
                    tokens.removeAt(i + 1)
                } else {
                    i++
                }
            }
        }
        return tokens.mapNotNull { token ->
            tok.vocab[token] ?: tok.vocab["▁$token"] ?: run {
                Log.w(TAG, "Unknown BPE token: $token")
                tok.vocab["<unk>"]
            }
        }
    }

    // ==================== Encoder ====================

    private fun runEncoder(inputIds: LongArray): OnnxTensor {
        val env = getEnv()
        val seqLen = inputIds.size.toLong()
        val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen))
        val attentionMask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(inputIds.size) { 1L }), longArrayOf(1, seqLen))
        val inputs = mapOf("input_ids" to inputIdsTensor, "attention_mask" to attentionMask)
        val result = encoderSession!!.run(inputs)
        val output = result[0] as OnnxTensor
        inputIdsTensor.close()
        attentionMask.close()
        return output
    }

    // ==================== Decoder: Greedy ====================

    private fun greedyDecode(encoderOutput: OnnxTensor, srcLen: Int): LongArray {
        val env = getEnv()
        val startIds = buildStartIds()
        val outputIds = startIds.toMutableList()

        val encoderAttention = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(srcLen) { 1L }), longArrayOf(1, srcLen.toLong())
        )

        try {
            for (step in 0 until MAX_LENGTH) {
                val lastLogits = runDecoderStep(env, outputIds, encoderOutput, encoderAttention)
                val vocabSize = lastLogits.size

                // Argmax
                var maxVal = Float.NEGATIVE_INFINITY
                var maxIdx = 0L
                for (i in 0 until vocabSize) {
                    if (lastLogits[i] > maxVal) { maxVal = lastLogits[i]; maxIdx = i.toLong() }
                }

                if (maxIdx == eosTokenId) break
                outputIds.add(maxIdx)
            }
        } finally {
            encoderAttention.close()
            encoderOutput.close()
        }

        val skipCount = startIds.size
        return if (outputIds.size > skipCount) outputIds.drop(skipCount).toLongArray() else longArrayOf()
    }

    // ==================== Decoder: Beam Search ====================

    private data class Beam(val tokens: List<Long>, val score: Float)

    /**
     * Beam search decoder with length normalization.
     * Runs beamSize separate decoder calls per step (no KV cache).
     */
    private fun beamDecode(encoderOutput: OnnxTensor, srcLen: Int, beamSize: Int): LongArray {
        val env = getEnv()
        val startIds = buildStartIds()
        val startLen = startIds.size

        val encoderAttention = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(srcLen) { 1L }), longArrayOf(1, srcLen.toLong())
        )

        var activeBeams = mutableListOf(Beam(startIds, 0f))
        val completedBeams = mutableListOf<Beam>()

        try {
            for (step in 0 until MAX_LENGTH) {
                val allCandidates = mutableListOf<Beam>()

                for (beam in activeBeams) {
                    val lastLogits = runDecoderStep(env, beam.tokens, encoderOutput, encoderAttention)
                    val logProbs = logSoftmax(lastLogits)

                    // Suppress pad token generation
                    if (padTokenId in 0 until logProbs.size) {
                        logProbs[padTokenId.toInt()] = Float.NEGATIVE_INFINITY
                    }

                    // Get top-K candidates for this beam
                    val topK = topKIndices(logProbs, beamSize * 2)

                    for (idx in topK) {
                        val newScore = beam.score + logProbs[idx]
                        if (idx.toLong() == eosTokenId) {
                            completedBeams.add(Beam(beam.tokens, newScore))
                        } else {
                            allCandidates.add(Beam(beam.tokens + idx.toLong(), newScore))
                        }
                    }
                }

                // Keep top beamSize candidates
                allCandidates.sortByDescending { it.score }
                activeBeams = allCandidates.take(beamSize).toMutableList()

                if (activeBeams.isEmpty()) break

                // Early stopping: if best completed beam (length-normalized) beats best active
                if (completedBeams.isNotEmpty()) {
                    val bestCompletedNorm = completedBeams.maxOf { lengthNormScore(it, startLen) }
                    val bestActiveNorm = lengthNormScore(activeBeams[0], startLen)
                    if (bestCompletedNorm > bestActiveNorm) break
                }
            }
        } finally {
            encoderAttention.close()
            encoderOutput.close()
        }

        // Also consider remaining active beams
        completedBeams.addAll(activeBeams)
        if (completedBeams.isEmpty()) return longArrayOf()

        // Select best beam with length normalization
        val best = completedBeams.maxByOrNull { lengthNormScore(it, startLen) }!!
        return if (best.tokens.size > startLen) best.tokens.drop(startLen).toLongArray() else longArrayOf()
    }

    /** Google GNMT length normalization: score / ((5 + len)/(5 + 1))^α */
    private fun lengthNormScore(beam: Beam, startLen: Int): Float {
        val genLen = max(1, beam.tokens.size - startLen)
        val penalty = ((5.0 + genLen) / 6.0).let { Math.pow(it, LENGTH_PENALTY_ALPHA.toDouble()) }.toFloat()
        return beam.score / penalty
    }

    // ==================== Decoder helpers ====================

    private fun buildStartIds(): List<Long> {
        val ids = mutableListOf(decoderStartTokenId)
        if (forcedBosTokenId >= 0) ids.add(forcedBosTokenId)
        return ids
    }

    /**
     * Run one decoder step and return raw logits for the LAST position only.
     * The caller is responsible for closing encoderOutput and encoderAttention.
     */
    private fun runDecoderStep(
        env: OrtEnvironment,
        tokensSoFar: List<Long>,
        encoderOutput: OnnxTensor,
        encoderAttention: OnnxTensor
    ): FloatArray {
        val decoderInputIds = OnnxTensor.createTensor(
            env, LongBuffer.wrap(tokensSoFar.toLongArray()), longArrayOf(1, tokensSoFar.size.toLong())
        )
        val inputs = mutableMapOf<String, OnnxTensor>()
        inputs["input_ids"] = decoderInputIds
        inputs[decoderEncoderOutputName] = encoderOutput
        if (decoderAttMaskName != null) inputs[decoderAttMaskName!!] = encoderAttention

        val result = decoderSession!!.run(inputs)
        val logitsTensor = result[0] as OnnxTensor

        val logitsData = logitsTensor.floatBuffer
        val vocabSize = logitsTensor.info.shape.last().toInt()
        val lastPosOffset = (tokensSoFar.size - 1) * vocabSize

        // Extract last-position logits into a float array
        val lastLogits = FloatArray(vocabSize)
        for (i in 0 until vocabSize) {
            lastLogits[i] = logitsData.get(lastPosOffset + i)
        }

        decoderInputIds.close()
        logitsTensor.close()
        result.close()

        return lastLogits
    }

    /** In-place log-softmax: logits[i] = logits[i] - log(sum(exp(logits))) */
    private fun logSoftmax(logits: FloatArray): FloatArray {
        var maxVal = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > maxVal) maxVal = v

        var sumExp = 0.0
        for (v in logits) sumExp += exp((v - maxVal).toDouble())
        val logSumExp = maxVal + ln(sumExp).toFloat()

        for (i in logits.indices) logits[i] -= logSumExp
        return logits
    }

    /** Get indices of top-K values in a float array (single-pass, O(n*k)). */
    private fun topKIndices(scores: FloatArray, k: Int): IntArray {
        val topIdx = IntArray(k) { -1 }
        val topVal = FloatArray(k) { Float.NEGATIVE_INFINITY }

        for (i in scores.indices) {
            if (scores[i] > topVal[k - 1]) {
                // Insert into sorted position
                var j = k - 1
                while (j > 0 && scores[i] > topVal[j - 1]) {
                    topVal[j] = topVal[j - 1]
                    topIdx[j] = topIdx[j - 1]
                    j--
                }
                topVal[j] = scores[i]
                topIdx[j] = i
            }
        }
        return topIdx.filter { it >= 0 }.toIntArray()
    }

    // ==================== Detokenization ====================

    private fun detokenize(tokenIds: LongArray): String {
        val tok = tokenizer ?: return ""
        val tokens = when (tok) {
            is TokenizerModel.Unigram -> tokenIds.map { tok.idToToken[it.toInt()] ?: "" }
            is TokenizerModel.Bpe -> tokenIds.map { tok.idToToken[it] ?: "" }
            is TokenizerModel.SpmVocab -> tokenIds.map { tok.idToToken[it] ?: "" }
        }
        return tokens.joinToString("")
            .replace("▁", " ")
            .replace("Ġ", " ")
            .replace("<pad>", "")
            .replace("</s>", "")
            .replace("<s>", "")
            .replace("<unk>", "")
            .trim()
    }

    // ==================== Lifecycle ====================

    override fun close() {
        try { encoderSession?.close() } catch (_: Throwable) {}
        try { decoderSession?.close() } catch (_: Throwable) {}
        encoderSession = null
        decoderSession = null
        initialized = false
    }
}
