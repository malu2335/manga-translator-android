package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/**
 * 日文OCR识别器，基于PaddleOCR日文识别模型。
 * 需要配合EnglishLineDetector使用Multilingual_PP-OCRv3_det_infer.onnx进行文字行检测。
 */
class JapanesePpOcr(
    private val context: Context,
    private val threadProfile: OnnxThreadProfile = OnnxThreadProfile.LIGHT,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) : OcrEngine {
    private val env = OnnxRuntimeSupport.environment()
    private val session: OrtSession = createSession("japan_PP-OCRv3_mobile_rec_infer.onnx")
    private val charset: List<String> = readCharset()
    private val inputName: String = session.inputInfo.keys.first()

    override fun recognize(bitmap: Bitmap): String {
        return recognizeWithScore(bitmap).text
    }

    fun recognizeWithScore(bitmap: Bitmap): OcrResult {
        val preprocessed = preprocess(bitmap)
        preprocessed.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val output = outputs[0]
                val outputShape = (output.info as TensorInfo).shape
                val decoded = ctcDecodeWithScore(output.value, outputShape)
                if (settingsStore.loadModelIoLogging()) {
                    AppLogger.log(
                        "JapanesePpOcr",
                        "Input ${bitmap.width}x${bitmap.height}, output: ${decoded.text}"
                    )
                }
                return decoded
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        val imgH = 48
        val imgW = 320

        val h = bitmap.height
        val w = bitmap.width
        val ratio = w.toFloat() / h.toFloat()
        var targetW = (imgH * ratio).toInt()
        targetW = targetW.coerceAtMost(imgW)

        val resized = bitmap.scale(targetW, imgH)

        val input = FloatArray(3 * imgH * imgW)
        for (y in 0 until imgH) {
            for (x in 0 until targetW) {
                val pixel = resized[x, y]
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                val bNorm = (b - 0.5f) / 0.5f
                val gNorm = (g - 0.5f) / 0.5f
                val rNorm = (r - 0.5f) / 0.5f
                val base = y * imgW + x
                input[base] = bNorm
                input[base + imgH * imgW] = gNorm
                input[base + 2 * imgH * imgW] = rNorm
            }
        }

        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, imgH.toLong(), imgW.toLong())
        )
    }

    private fun ctcDecodeWithScore(raw: Any, shape: LongArray): OcrResult {
        val batch = raw as? Array<*> ?: return OcrResult("", 0f)
        val first = batch.firstOrNull() as? Array<*> ?: return OcrResult("", 0f)
        val firstVec = first.firstOrNull()
        if (firstVec !is FloatArray) return OcrResult("", 0f)

        val result = StringBuilder()
        val confs = ArrayList<Float>()
        var prevIdx = -1

        fun appendIndex(maxIdx: Int, maxProb: Float) {
            if (maxIdx == prevIdx) {
                prevIdx = maxIdx
                return
            }
            prevIdx = maxIdx
            if (maxIdx == 0) {
                return
            }
            if (maxIdx < charset.size) {
                result.append(charset[maxIdx])
                confs.add(maxProb)
            }
        }

        fun argmax(probs: FloatArray): Int {
            var maxIdx = 0
            var maxProb = probs[0]
            for (i in 1 until probs.size) {
                if (probs[i] > maxProb) {
                    maxProb = probs[i]
                    maxIdx = i
                }
            }
            return maxIdx
        }

        fun maxProb(probs: FloatArray, maxIdx: Int): Float {
            return if (maxIdx in probs.indices) probs[maxIdx] else 0f
        }

        val dim0 = batch.size
        val dim1 = first.size
        val dim2 = firstVec.size
        val looksLikeClasses: (Int) -> Boolean = { value ->
            value == charset.size || value == charset.size - 1 || value == charset.size + 1
        }

        when {
            dim1 == 1 && dim0 > 1 -> {
                for (t in 0 until dim0) {
                    val inner = batch[t] as? Array<*> ?: continue
                    val probs = inner.firstOrNull() as? FloatArray ?: continue
                    val maxIdx = argmax(probs)
                    appendIndex(maxIdx, maxProb(probs, maxIdx))
                }
            }
            dim0 == 1 && looksLikeClasses(dim2) -> {
                for (step in first) {
                    val probs = step as? FloatArray ?: continue
                    val maxIdx = argmax(probs)
                    appendIndex(maxIdx, maxProb(probs, maxIdx))
                }
            }
            dim0 == 1 && looksLikeClasses(dim1) -> {
                val classArrays = first.mapNotNull { it as? FloatArray }
                val timeCount = classArrays.firstOrNull()?.size ?: 0
                for (t in 0 until timeCount) {
                    var maxIdx = 0
                    var maxProb = Float.NEGATIVE_INFINITY
                    for (c in classArrays.indices) {
                        val arr = classArrays[c]
                        if (t >= arr.size) continue
                        val v = arr[t]
                        if (v > maxProb) {
                            maxProb = v
                            maxIdx = c
                        }
                    }
                    appendIndex(maxIdx, maxProb)
                }
            }
            shape.size == 3L.toInt() && shape[0] == 1L -> {
                for (step in first) {
                    val probs = step as? FloatArray ?: continue
                    val maxIdx = argmax(probs)
                    appendIndex(maxIdx, maxProb(probs, maxIdx))
                }
            }
            else -> {
                for (step in first) {
                    val probs = step as? FloatArray ?: continue
                    val maxIdx = argmax(probs)
                    appendIndex(maxIdx, maxProb(probs, maxIdx))
                }
            }
        }

        val score = if (confs.isEmpty()) 0f else confs.sum() / confs.size
        return OcrResult(result.toString(), score)
    }

    private fun readCharset(): List<String> {
        try {
            val meta = session.metadata
            val customMetadata = meta.customMetadata
            val charString = customMetadata["character"] ?: return getDefaultCharset()

            val charList = charString.lines().filter { it.isNotBlank() }.toMutableList()
            charList.add(0, "blank")
            charList.add(" ")

            AppLogger.log("JapanesePpOcr", "Loaded ${charList.size} characters from model metadata")
            return charList
        } catch (e: Exception) {
            AppLogger.log("JapanesePpOcr", "Failed to read charset from model, using default", e)
            return getDefaultCharset()
        }
    }

    private fun getDefaultCharset(): List<String> {
        val chars = mutableListOf("blank")
        for (cp in 0x3040..0x309F) {
            chars.add(String(Character.toChars(cp)))
        }
        for (cp in 0x30A0..0x30FF) {
            chars.add(String(Character.toChars(cp)))
        }
        for (cp in 0x4E00..0x9FFF) {
            chars.add(String(Character.toChars(cp)))
        }
        for (i in '0'..'9') {
            chars.add(i.toString())
        }
        val punctuation = "。、！？ー・「」『』（）()[]【】….,!?-~:; "
        for (c in punctuation) {
            chars.add(c.toString())
        }
        AppLogger.log("JapanesePpOcr", "Using default charset with ${chars.size} characters")
        return chars
    }

    private fun createSession(assetName: String): OrtSession {
        return OnnxRuntimeSupport.getOrCreateSession(
            cacheDir = context.cacheDir,
            assetProvider = context.assets::open,
            assetName = assetName,
            threadProfile = threadProfile
        )
    }

    data class OcrResult(
        val text: String,
        val score: Float
    )
}
