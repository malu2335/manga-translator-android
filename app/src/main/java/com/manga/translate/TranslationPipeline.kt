package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

internal class TranslationPipeline(
    context: Context,
    private val llmClient: LlmClient = LlmClient(context.applicationContext),
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext),
    private val store: TranslationStore = TranslationStore(),
    private val ocrStore: OcrStore = OcrStore(),
    private val ocrEngineRegistry: OcrEngineRegistry =
        OcrEngineRegistry(context.applicationContext, settingsStore),
    private val bubbleTextRecognizer: BubbleTextRecognizer =
        BubbleTextRecognizer(llmClient, ocrEngineRegistry),
    private val floatingTranslationCacheStore: FloatingTranslationCacheStore =
        FloatingTranslationCacheStore(context.applicationContext),
    private val textBubbleTranslationCoordinator: TextBubbleTranslationCoordinator =
        TextBubbleTranslationCoordinator(
            llmClient = llmClient,
            settingsStore = settingsStore,
            floatingTranslationCacheStore = floatingTranslationCacheStore
        ),
    private val floatingBubbleTranslationCoordinator: FloatingBubbleTranslationCoordinator =
        FloatingBubbleTranslationCoordinator(
            llmClient = llmClient,
            floatingTranslationCacheStore = floatingTranslationCacheStore,
            settingsStore = settingsStore
        )
) {
    private val appContext = context.applicationContext
    private var detector: BubbleDetector? = null
    private var textDetector: TextDetector? = null

    suspend fun translateImage(
        imageFile: File,
        glossary: MutableMap<String, String>,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        if (!llmClient.isConfigured()) {
            onProgress(appContext.getString(R.string.missing_api_settings))
            AppLogger.log("Pipeline", "Missing API settings")
            return@withContext null
        }
        val page = ocrImage(imageFile, forceOcr, language, onProgress) ?: return@withContext null
        val metadata = buildTranslationMetadata(
            imageFile = imageFile,
            language = language,
            mode = TranslationMetadata.MODE_STANDARD,
            promptAsset = STANDARD_PROMPT_ASSET,
            ocrCacheMode = page.cacheMode
        )
        AppLogger.log("Pipeline", "Translate image ${imageFile.name}")
        val translatable = page.bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) {
            val emptyTranslations = page.bubbles.map {
                BubbleTranslation(it.id, it.rect, "", it.source, it.maskContour)
            }
            return@withContext TranslationResult(
                imageFile.name,
                page.width,
                page.height,
                emptyTranslations,
                metadata
            )
        }
        onProgress(appContext.getString(R.string.translating_bubbles))
        val promptAsset = STANDARD_PROMPT_ASSET
        val translatedBubbles = try {
            val translated = executeWithModelResponseRetries("Pipeline") {
                textBubbleTranslationCoordinator.translateBubbles(
                    bubbles = translatable.map {
                        BubbleTranslation(it.id, it.rect, it.text, it.source, it.maskContour)
                    },
                    glossary = glossary,
                    promptAsset = promptAsset,
                    language = language,
                    logTag = "Pipeline",
                    invalidResponseMode = "standard"
                )
            } ?: return@withContext null
            if (translated.glossaryUsed.isNotEmpty()) {
                glossary.putAll(translated.glossaryUsed)
            }
            translated.bubbles
        } catch (e: LlmResponseException) {
            throw e.withPageName(imageFile.name)
        }
        val translationMap = translatedBubbles.associateBy({ it.id }, { it.text })
        val bubbles = page.bubbles.map { bubble ->
            val text = translationMap[bubble.id] ?: ""
            BubbleTranslation(bubble.id, bubble.rect, text, bubble.source, bubble.maskContour)
        }
        AppLogger.log("Pipeline", "Translation finished for ${imageFile.name}")
        TranslationResult(imageFile.name, page.width, page.height, bubbles, metadata)
    }

    suspend fun ocrImage(
        imageFile: File,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onProgress: (String) -> Unit
    ): PageOcrResult? = withContext(Dispatchers.Default) {
        val ocrSettings = settingsStore.loadOcrApiSettings()
        val cacheMode = buildOcrCacheMode(ocrSettings.useLocalOcr, language)
        val expectedMetadata = buildOcrMetadata(imageFile, language, ocrSettings, cacheMode)
        if (!forceOcr) {
            val cached = ocrStore.load(imageFile, expectedMetadata = expectedMetadata)
            if (cached != null) {
                AppLogger.log("Pipeline", "Reuse OCR for ${imageFile.name}")
                return@withContext cached
            }
        }
        val detector = getDetector() ?: return@withContext null
        val useLocalOcr = ocrSettings.useLocalOcr
        val ocrEngine: OcrEngine? = if (useLocalOcr) {
            bubbleTextRecognizer.getLocalOcrEngine(language, "Pipeline")
        } else {
            null
        }
        if (!useLocalOcr && !llmClient.isOcrConfigured()) {
            onProgress(appContext.getString(R.string.missing_ocr_api_settings))
            AppLogger.log("Pipeline", "Missing OCR API settings")
            return@withContext null
        }
        if (useLocalOcr && ocrEngine == null) {
            return@withContext null
        }
        val textDetector = getTextDetector()
        val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: run {
                AppLogger.log("Pipeline", "Failed to decode ${imageFile.name}")
                return@withContext null
            }
        try {
            onProgress(appContext.getString(R.string.detecting_bubbles))
            val detections = detector.detect(bitmap)
            AppLogger.log("Pipeline", "Detected ${detections.size} bubbles in ${imageFile.name}")
            val bubbleRects = detections.map { it.rect }
            if (useLocalOcr && language == TranslationLanguage.EN_TO_ZH && ocrEngine is EnglishOcr) {
                val bubbles = ArrayList<OcrBubble>(bubbleRects.size)
                if (bubbleRects.isEmpty()) {
                    val lines = bubbleTextRecognizer.detectRecognizedLines(
                        source = bitmap,
                        language = language,
                        logTag = "Pipeline"
                    )
                    for ((index, line) in lines.withIndex()) {
                        bubbles.add(OcrBubble(index, line.rect, line.text, BubbleSource.TEXT_DETECTOR))
                    }
                } else {
                    for ((bubbleId, det) in detections.withIndex()) {
                        val text = bubbleTextRecognizer.recognizeRegion(
                            source = bitmap,
                            rect = det.rect,
                            language = language,
                            useLocalOcr = true,
                            logTag = "Pipeline"
                        )
                        if (text.isBlank()) continue
                        bubbles.add(OcrBubble(bubbleId, det.rect, text, BubbleSource.BUBBLE_DETECTOR, det.maskContour))
                    }
                }
                val result = PageOcrResult(
                    imageFile,
                    bitmap.width,
                    bitmap.height,
                    bubbles,
                    cacheMode,
                    expectedMetadata
                )
                ocrStore.save(imageFile, result)
                return@withContext result
            }
            if (useLocalOcr && language == TranslationLanguage.KO_TO_ZH && ocrEngine is KoreanOcr) {
                val bubbles = ArrayList<OcrBubble>(bubbleRects.size)
                if (bubbleRects.isEmpty()) {
                    val lines = bubbleTextRecognizer.detectRecognizedLines(
                        source = bitmap,
                        language = language,
                        logTag = "Pipeline"
                    )
                    for ((index, line) in lines.withIndex()) {
                        bubbles.add(OcrBubble(index, line.rect, line.text, BubbleSource.TEXT_DETECTOR))
                    }
                } else {
                    for ((bubbleId, det) in detections.withIndex()) {
                        val text = bubbleTextRecognizer.recognizeRegion(
                            source = bitmap,
                            rect = det.rect,
                            language = language,
                            useLocalOcr = true,
                            logTag = "Pipeline"
                        )
                        if (text.isBlank()) continue
                        bubbles.add(OcrBubble(bubbleId, det.rect, text, BubbleSource.BUBBLE_DETECTOR, det.maskContour))
                    }
                }
                val result = PageOcrResult(
                    imageFile,
                    bitmap.width,
                    bitmap.height,
                    bubbles,
                    cacheMode,
                    expectedMetadata
                )
                ocrStore.save(imageFile, result)
                return@withContext result
            }
            val textRects = textDetector?.let { detectorInstance ->
                val masked = maskDetections(bitmap, detections)
                try {
                    val rawTextRects = detectorInstance.detect(masked)
                    val filtered = filterOverlapping(rawTextRects, bubbleRects, TEXT_IOU_THRESHOLD)
                    RectGeometryDeduplicator.mergeSupplementRects(filtered, bitmap.width, bitmap.height)
                } finally {
                    if (masked !== bitmap) {
                        masked.recycleSafely()
                    }
                }
            } ?: emptyList()
            if (bubbleRects.isEmpty() && textRects.isEmpty()) {
                val emptyResult = PageOcrResult(
                    imageFile,
                    bitmap.width,
                    bitmap.height,
                    emptyList(),
                    cacheMode,
                    expectedMetadata
                )
                ocrStore.save(imageFile, emptyResult)
                return@withContext emptyResult
            }
            if (textRects.isNotEmpty()) {
                AppLogger.log(
                    "Pipeline",
                    "Supplemented ${textRects.size} text boxes in ${imageFile.name}"
                )
            }
            val allRects = ArrayList<RectF>(bubbleRects.size + textRects.size)
            allRects.addAll(bubbleRects)
            allRects.addAll(textRects)
            val bubbles = ArrayList<OcrBubble>(allRects.size)
            val bubbleDetectorCount = bubbleRects.size
            for ((bubbleId, rect) in allRects.withIndex()) {
                val text = bubbleTextRecognizer.recognizeRegion(
                    source = bitmap,
                    rect = rect,
                    language = language,
                    useLocalOcr = useLocalOcr,
                    logTag = "Pipeline"
                )
                if (text.isBlank() && !useLocalOcr) {
                    continue
                }
                val source = if (bubbleId < bubbleDetectorCount) {
                    BubbleSource.BUBBLE_DETECTOR
                } else {
                    BubbleSource.TEXT_DETECTOR
                }
                val maskContour = if (bubbleId < bubbleDetectorCount) {
                    detections.getOrNull(bubbleId)?.maskContour
                } else null
                bubbles.add(OcrBubble(bubbleId, rect, text, source, maskContour))
            }
            val result = PageOcrResult(
                imageFile,
                bitmap.width,
                bitmap.height,
                bubbles,
                cacheMode,
                expectedMetadata
            )
            ocrStore.save(imageFile, result)
            result
        } finally {
            bitmap.recycleSafely()
        }
    }

    suspend fun translateFullPage(
        page: PageOcrResult,
        glossary: Map<String, String>,
        promptAsset: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        val metadata = buildTranslationMetadata(
            imageFile = page.imageFile,
            language = language,
            mode = TranslationMetadata.MODE_FULL_PAGE,
            promptAsset = promptAsset,
            ocrCacheMode = page.cacheMode
        )
        val translatable = page.bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) {
            val emptyTranslations = page.bubbles.map {
                BubbleTranslation(it.id, it.rect, "", it.source, it.maskContour)
            }
            return@withContext TranslationResult(
                page.imageFile.name,
                page.width,
                page.height,
                emptyTranslations,
                metadata
            )
        }
        onProgress(appContext.getString(R.string.translating_bubbles))
        val translatedBubbles = try {
            val translated = executeWithModelResponseRetries("Pipeline") {
                textBubbleTranslationCoordinator.translateBubbles(
                    bubbles = translatable.map {
                        BubbleTranslation(it.id, it.rect, it.text, it.source, it.maskContour)
                    },
                    glossary = glossary,
                    promptAsset = promptAsset,
                    language = language,
                    logTag = "Pipeline",
                    invalidResponseMode = "full_page"
                )
            } ?: return@withContext null
            translated.bubbles
        } catch (e: LlmResponseException) {
            throw e.withPageName(page.imageFile.name)
        }
        val translationMap = translatedBubbles.associateBy({ it.id }, { it.text })
        val bubbles = page.bubbles.map { bubble ->
            val text = translationMap[bubble.id] ?: ""
            BubbleTranslation(bubble.id, bubble.rect, text, bubble.source, bubble.maskContour)
        }
        TranslationResult(page.imageFile.name, page.width, page.height, bubbles, metadata)
    }

    suspend fun translateImageWithVl(
        imageFile: File,
        language: TranslationLanguage
    ): FolderVlTranslateOutcome =
        withContext(Dispatchers.Default) {
            if (!llmClient.isConfigured()) {
                AppLogger.log("Pipeline", "Missing API settings for VL direct translate")
                return@withContext FolderVlTranslateOutcome()
            }
            val page = detectImageBubbles(imageFile) ?: return@withContext FolderVlTranslateOutcome()
            if (page.bubbles.isEmpty()) {
                return@withContext FolderVlTranslateOutcome(
                    result = TranslationResult(
                        imageFile.name,
                        page.width,
                        page.height,
                        emptyList(),
                        buildTranslationMetadata(
                            imageFile = imageFile,
                            language = language,
                            mode = TranslationMetadata.MODE_VL_DIRECT,
                            promptAsset = VL_PROMPT_ASSET,
                            ocrCacheMode = ""
                        )
                    )
                )
            }
            val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: run {
                    AppLogger.log("Pipeline", "Failed to decode ${imageFile.name} for VL direct translate")
                    return@withContext FolderVlTranslateOutcome()
                }
            try {
                val floatingSettings = settingsStore.loadFloatingTranslateApiSettings()
                val outcome = floatingBubbleTranslationCoordinator.translateImageBubbles(
                    bitmap = bitmap,
                    bubbles = page.bubbles.map { bubble ->
                        BubbleTranslation(bubble.id, bubble.rect, "", bubble.source, bubble.maskContour)
                    },
                    timeoutMs = settingsStore.loadApiTimeoutMs(),
                    retryCount = 3,
                    promptAsset = VL_PROMPT_ASSET,
                    apiSettings = settingsStore.load(),
                    concurrency = floatingSettings.vlTranslateConcurrency,
                    maxConcurrency = 16,
                    logTag = "Pipeline"
                )
                if (outcome.requiresVlModel || outcome.timedOut) {
                    return@withContext FolderVlTranslateOutcome(
                        timedOut = outcome.timedOut,
                        requiresVlModel = outcome.requiresVlModel
                    )
                }
                FolderVlTranslateOutcome(
                    result = TranslationResult(
                        imageFile.name,
                        page.width,
                        page.height,
                        outcome.bubbles,
                        buildTranslationMetadata(
                            imageFile = imageFile,
                            language = language,
                            mode = TranslationMetadata.MODE_VL_DIRECT,
                            promptAsset = VL_PROMPT_ASSET,
                            ocrCacheMode = ""
                        )
                    )
                )
            } finally {
                bitmap.recycleSafely()
            }
        }

    fun hasValidTranslation(
        imageFile: File,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage
    ): Boolean {
        val expected = buildExpectedTranslationMetadata(
            imageFile = imageFile,
            fullTranslate = fullTranslate,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language
        )
        return store.load(imageFile, expectedMetadata = expected) != null
    }

    fun saveResult(imageFile: File, result: TranslationResult): File {
        val saved = store.save(imageFile, result)
        val ocrFile = ocrStore.ocrFileFor(imageFile)
        if (ocrFile.exists()) {
            ocrFile.delete()
        }
        return saved
    }

    suspend fun buildBlankTranslationResult(
        imageFile: File,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH
    ): TranslationResult? = withContext(Dispatchers.Default) {
        val page = ocrImage(imageFile, forceOcr, language) { } ?: return@withContext null
        buildBlankTranslationResult(
            page = page,
            mode = TranslationMetadata.MODE_STANDARD,
            promptAsset = STANDARD_PROMPT_ASSET,
            language = language
        )
    }

    fun buildBlankTranslationResult(
        page: PageOcrResult,
        mode: String,
        promptAsset: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH
    ): TranslationResult {
        val metadata = buildTranslationMetadata(
            imageFile = page.imageFile,
            language = language,
            mode = mode,
            promptAsset = promptAsset,
            ocrCacheMode = page.cacheMode
        )
        val bubbles = page.bubbles.map { bubble ->
            BubbleTranslation(bubble.id, bubble.rect, "", bubble.source, bubble.maskContour)
        }
        return TranslationResult(
            imageName = page.imageFile.name,
            width = page.width,
            height = page.height,
            bubbles = bubbles,
            metadata = metadata
        )
    }

    fun translationFileFor(imageFile: File): File {
        return store.translationFileFor(imageFile)
    }

    private fun getDetector(): BubbleDetector? {
        if (detector != null) return detector
        return try {
            detector = BubbleDetector(appContext, settingsStore = settingsStore)
            detector
        } catch (e: Exception) {
            AppLogger.log("Pipeline", "Failed to init bubble detector", e)
            null
        }
    }

    private fun getTextDetector(): TextDetector? {
        if (textDetector != null) return textDetector
        return try {
            textDetector = TextDetector(appContext, settingsStore = settingsStore)
            textDetector
        } catch (e: Exception) {
            AppLogger.log("Pipeline", "Failed to init text detector", e)
            null
        }
    }

    private fun maskDetections(source: Bitmap, detections: List<BubbleDetection>): Bitmap {
        if (detections.isEmpty()) return source
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        for (det in detections) {
            if (det.maskContour != null && det.maskContour.size >= 6) {
                canvas.drawPath(
                    buildContourPath(det.maskContour, source.width.toFloat(), source.height.toFloat()),
                    paint
                )
            } else {
                canvas.drawRect(
                    padRect(det.rect, source.width, source.height, MASK_EXPAND_RATIO, MASK_EXPAND_MIN),
                    paint
                )
            }
        }
        return copy
    }

    private fun buildContourPath(contour: FloatArray, w: Float, h: Float): Path {
        val path = Path()
        path.moveTo(contour[0] * w, contour[1] * h)
        var i = 2
        while (i + 1 < contour.size) {
            path.lineTo(contour[i] * w, contour[i + 1] * h)
            i += 2
        }
        path.close()
        return path
    }

    private fun padRect(rect: RectF, width: Int, height: Int, ratio: Float, minPad: Float): RectF {
        val h = max(1f, rect.height())
        val pad = max(minPad, ratio * h)
        val left = (rect.left - pad).coerceIn(0f, width.toFloat())
        val top = (rect.top - pad).coerceIn(0f, height.toFloat())
        val right = (rect.right + pad).coerceIn(0f, width.toFloat())
        val bottom = (rect.bottom + pad).coerceIn(0f, height.toFloat())
        return RectF(left, top, right, bottom)
    }

    private fun filterOverlapping(
        textRects: List<RectF>,
        bubbleRects: List<RectF>,
        threshold: Float
    ): List<RectF> {
        if (bubbleRects.isEmpty()) return textRects
        val filtered = ArrayList<RectF>(textRects.size)
        for (rect in textRects) {
            var overlapped = false
            for (bubble in bubbleRects) {
                if (iou(rect, bubble) >= threshold || contains(bubble, rect)) {
                    overlapped = true
                    break
                }
            }
            if (!overlapped) {
                filtered.add(rect)
            }
        }
        return filtered
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, right - left) * max(0f, bottom - top)
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun contains(outer: RectF, inner: RectF): Boolean {
        return outer.left <= inner.left &&
            outer.top <= inner.top &&
            outer.right >= inner.right &&
            outer.bottom >= inner.bottom
    }

    private suspend fun detectImageBubbles(imageFile: File): PageOcrResult? =
        withContext(Dispatchers.Default) {
            val detector = getDetector() ?: return@withContext null
            val textDetector = getTextDetector()
            val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: run {
                    AppLogger.log("Pipeline", "Failed to decode ${imageFile.name}")
                    return@withContext null
                }
            try {
                val detections = detector.detect(bitmap)
                val bubbleRects = detections.map { it.rect }
                val textRects = textDetector?.let { detectorInstance ->
                    val masked = maskDetections(bitmap, detections)
                    try {
                        val rawTextRects = detectorInstance.detect(masked)
                        val filtered = filterOverlapping(rawTextRects, bubbleRects, TEXT_IOU_THRESHOLD)
                        RectGeometryDeduplicator.mergeSupplementRects(
                            filtered,
                            bitmap.width,
                            bitmap.height
                        )
                    } finally {
                        if (masked !== bitmap) {
                            masked.recycleSafely()
                        }
                    }
                } ?: emptyList()
                val allRects = ArrayList<RectF>(bubbleRects.size + textRects.size)
                allRects.addAll(bubbleRects)
                allRects.addAll(textRects)
                val bubbleDetectorCount = bubbleRects.size
                val bubbles = allRects.mapIndexed { index, rect ->
                    OcrBubble(
                        id = index,
                        rect = rect,
                        text = "",
                        source = if (index < bubbleDetectorCount) BubbleSource.BUBBLE_DETECTOR else BubbleSource.TEXT_DETECTOR,
                        maskContour = if (index < bubbleDetectorCount) detections.getOrNull(index)?.maskContour else null
                    )
                }
                PageOcrResult(imageFile, bitmap.width, bitmap.height, bubbles)
            } finally {
                bitmap.recycleSafely()
            }
        }

    companion object {
        private const val TEXT_IOU_THRESHOLD = 0.2f
        private const val MASK_EXPAND_RATIO = 0.1f
        private const val MASK_EXPAND_MIN = 4f
        private const val STANDARD_PROMPT_ASSET = "llm_prompts.json"
        private const val FULL_TRANS_PROMPT_ASSET = "llm_prompts_FullTrans.json"
        private const val VL_PROMPT_ASSET = "vl_bubble_prompts.json"
        private const val MODEL_RESPONSE_SILENT_RETRY_COUNT = 3
    }

    private fun buildExpectedTranslationMetadata(
        imageFile: File,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage
    ): TranslationMetadata {
        return when {
            useVlDirectTranslate -> buildTranslationMetadata(
                imageFile = imageFile,
                language = language,
                mode = TranslationMetadata.MODE_VL_DIRECT,
                promptAsset = VL_PROMPT_ASSET,
                ocrCacheMode = ""
            )
            fullTranslate -> buildTranslationMetadata(
                imageFile = imageFile,
                language = language,
                mode = TranslationMetadata.MODE_FULL_PAGE,
                promptAsset = FULL_TRANS_PROMPT_ASSET,
                ocrCacheMode = buildOcrCacheMode(settingsStore.loadOcrApiSettings().useLocalOcr, language)
            )
            else -> buildTranslationMetadata(
                imageFile = imageFile,
                language = language,
                mode = TranslationMetadata.MODE_STANDARD,
                promptAsset = STANDARD_PROMPT_ASSET,
                ocrCacheMode = buildOcrCacheMode(settingsStore.loadOcrApiSettings().useLocalOcr, language)
            )
        }
    }

    private fun buildTranslationMetadata(
        imageFile: File,
        language: TranslationLanguage,
        mode: String,
        promptAsset: String,
        ocrCacheMode: String
    ): TranslationMetadata {
        val apiSettings = settingsStore.load()
        return TranslationMetadata(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length(),
            mode = mode,
            language = language.name,
            promptAsset = promptAsset,
            modelName = apiSettings.modelName,
            apiFormat = apiSettings.apiFormat.prefValue,
            ocrCacheMode = ocrCacheMode
        )
    }

    private fun buildOcrMetadata(
        imageFile: File,
        language: TranslationLanguage,
        ocrSettings: OcrApiSettings,
        cacheMode: String
    ): OcrMetadata {
        val engineModel = if (ocrSettings.useLocalOcr) {
            "local:$cacheMode"
        } else {
            "api:${ocrSettings.modelName}"
        }
        return OcrMetadata(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length(),
            cacheMode = cacheMode,
            language = language.name,
            engineModel = engineModel
        )
    }

    private fun buildOcrCacheMode(
        useLocalOcr: Boolean,
        language: TranslationLanguage
    ): String {
        return if (!useLocalOcr) {
            "api"
        } else {
            when (language) {
                TranslationLanguage.JA_TO_ZH -> "local_ja"
                TranslationLanguage.EN_TO_ZH -> "local_en"
                TranslationLanguage.KO_TO_ZH -> "local_ko"
            }
        }
    }

    private suspend fun <T> executeWithModelResponseRetries(
        logTag: String,
        block: suspend () -> T?
    ): T? {
        var lastError: LlmResponseException? = null
        repeat(MODEL_RESPONSE_SILENT_RETRY_COUNT) { attempt ->
            try {
                return block()
            } catch (e: LlmResponseException) {
                lastError = e
                AppLogger.log(
                    logTag,
                    "Model response invalid, retry ${attempt + 1}/$MODEL_RESPONSE_SILENT_RETRY_COUNT",
                    e
                )
            }
        }
        throw requireNotNull(lastError)
    }

    private fun LlmResponseException.withPageName(pageName: String): LlmResponseException {
        if (responseContent.startsWith("页面：")) return this
        return LlmResponseException(
            errorCode = errorCode,
            responseContent = "页面：$pageName\n$responseContent",
            cause = this
        )
    }

}

data class OcrBubble(
    val id: Int,
    val rect: RectF,
    val text: String,
    val source: BubbleSource = BubbleSource.UNKNOWN,
    val maskContour: FloatArray? = null
)

data class PageOcrResult(
    val imageFile: File,
    val width: Int,
    val height: Int,
    val bubbles: List<OcrBubble>,
    val cacheMode: String = "",
    val metadata: OcrMetadata = OcrMetadata()
)

data class FolderVlTranslateOutcome(
    val result: TranslationResult? = null,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)
