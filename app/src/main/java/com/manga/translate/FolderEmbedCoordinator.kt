package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.graphics.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

internal class FolderEmbedCoordinator(
    context: Context,
    private val translationStore: TranslationStore,
    private val settingsStore: SettingsStore,
    private val prefs: SharedPreferences,
    private val embeddedStateStore: EmbeddedStateStore,
    private val ui: LibraryUiCallbacks
) {
    private val appContext = context.applicationContext
    private var activeJob: Job? = null

    fun getEmbedThreadCount(): Int {
        val saved = prefs.getInt(KEY_EMBED_THREADS, DEFAULT_EMBED_THREADS)
        return normalizeEmbedThreads(saved)
    }

    fun getUseWhiteBubbleCover(): Boolean {
        return prefs.getBoolean(KEY_EMBED_WHITE_BUBBLE_COVER, DEFAULT_EMBED_WHITE_BUBBLE_COVER)
    }

    fun getUseImageRepair(): Boolean {
        return prefs.getBoolean(KEY_EMBED_IMAGE_REPAIR, DEFAULT_EMBED_IMAGE_REPAIR)
    }

    fun embedFolder(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        embedThreads: Int,
        useWhiteBubbleCover: Boolean,
        useImageRepair: Boolean,
        onSetActionsEnabled: (Boolean) -> Unit
    ) {
        if (activeJob?.isActive == true) {
            ui.showToast(R.string.folder_embed_running)
            return
        }
        if (images.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return
        }

        val normalizedThreads = normalizeEmbedThreads(embedThreads)
        prefs.edit() {
                putInt(KEY_EMBED_THREADS, normalizedThreads)
                .putBoolean(KEY_EMBED_WHITE_BUBBLE_COVER, useWhiteBubbleCover)
                .putBoolean(KEY_EMBED_IMAGE_REPAIR, useImageRepair)
            }

        onSetActionsEnabled(false)
        TranslationKeepAliveService.start(
            appContext,
            appContext.getString(R.string.embed_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message),
            appContext.getString(R.string.folder_embed_progress, 0, images.size)
        )
        TranslationKeepAliveService.updateStatus(
            appContext,
            appContext.getString(R.string.folder_embed_progress, 0, images.size),
            appContext.getString(R.string.embed_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message)
        )
        activeJob = scope.launch {
            try {
                val result = embedInternal(
                    folder = folder,
                    images = images,
                    embedThreads = normalizedThreads,
                    useWhiteBubbleCover = useWhiteBubbleCover,
                    useImageRepair = useImageRepair
                ) { done, total ->
                    withContext(Dispatchers.Main) {
                        val progressText = appContext.getString(R.string.folder_embed_progress, done, total)
                        ui.setFolderStatus(progressText)
                        TranslationKeepAliveService.updateProgress(
                            appContext,
                            done,
                            total,
                            progressText,
                            appContext.getString(R.string.embed_keepalive_title),
                            appContext.getString(R.string.translation_keepalive_message)
                        )
                    }
                }
                when (result) {
                    is EmbedResult.Success -> {
                        ui.setFolderStatus(appContext.getString(R.string.folder_embed_done))
                        GlobalTaskProgressStore.complete(
                            appContext.getString(R.string.embed_keepalive_title),
                            appContext.getString(R.string.folder_embed_done)
                        )
                        ui.showToast(R.string.folder_embed_done)
                    }
                    is EmbedResult.Failure -> {
                        val message = result.message.ifBlank { appContext.getString(R.string.folder_embed_failed) }
                        ui.setFolderStatus(appContext.getString(R.string.folder_embed_failed))
                        GlobalTaskProgressStore.fail(
                            appContext.getString(R.string.embed_keepalive_title),
                            message
                        )
                        ui.showToastMessage(message)
                    }
                }
                ui.refreshImages(folder)
                ui.refreshFolders()
            } finally {
                onSetActionsEnabled(true)
                TranslationKeepAliveService.stop(appContext)
            }
        }
    }

    fun cancelEmbed(
        scope: CoroutineScope,
        folder: File,
        onSetActionsEnabled: (Boolean) -> Unit
    ) {
        if (activeJob?.isActive == true) {
            ui.showToast(R.string.folder_embed_running)
            return
        }
        onSetActionsEnabled(false)
        activeJob = scope.launch {
            val success = withContext(Dispatchers.IO) {
                embeddedStateStore.clearEmbeddedState(folder)
                !embeddedStateStore.isEmbedded(folder)
            }
            onSetActionsEnabled(true)
            if (success) {
                ui.setFolderStatus(appContext.getString(R.string.folder_unembed_done))
                ui.showToast(R.string.folder_unembed_done)
            } else {
                ui.setFolderStatus(appContext.getString(R.string.folder_unembed_failed))
                ui.showToast(R.string.folder_unembed_failed)
            }
            ui.refreshImages(folder)
            ui.refreshFolders()
        }
    }

    private suspend fun embedInternal(
        folder: File,
        images: List<File>,
        embedThreads: Int,
        useWhiteBubbleCover: Boolean,
        useImageRepair: Boolean,
        onProgress: suspend (done: Int, total: Int) -> Unit
    ): EmbedResult = withContext(Dispatchers.IO) {
        val embeddedDir = embeddedStateStore.embeddedDir(folder)
        if (!embeddedDir.exists() && !embeddedDir.mkdirs()) {
            return@withContext EmbedResult.Failure(appContext.getString(R.string.folder_embed_failed))
        }

        val pending = ArrayList<Pair<File, TranslationResult>>(images.size)
        for (image in images) {
            val translation = translationStore.load(image)
            if (translation == null) {
                continue
            }
            val target = File(embeddedDir, ImageFileSupport.resolveRenderedOutputName(image.name))
            if (target.exists()) {
                continue
            }
            pending.add(image to translation)
        }

        if (pending.isEmpty()) {
            if (images.all {
                    File(embeddedDir, ImageFileSupport.resolveRenderedOutputName(it.name)).exists()
                }
            ) {
                embeddedStateStore.writeEmbeddedState(folder, images.size)
            }
            return@withContext EmbedResult.Success
        }
        val verticalLayoutEnabled = !settingsStore.loadUseHorizontalText()
        val workerCount = minOf(normalizeEmbedThreads(embedThreads), pending.size).coerceAtLeast(1)
        val nextIndex = AtomicInteger(0)
        val doneCount = AtomicInteger(0)
        val failed = AtomicBoolean(false)
        val failedImage = AtomicReference<File?>(null)
        val initFailed = AtomicBoolean(false)
        val onnxThreadProfile = OnnxThreadProfile.forCpuBoundWork(workerCount)
        val workerDispatcher = Executors.newFixedThreadPool(workerCount) { runnable ->
            Thread(runnable, "embed-worker").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }.asCoroutineDispatcher()

        try {
            val inpainter = try {
                MiganInpainter(appContext, threadProfile = onnxThreadProfile)
            } catch (e: Exception) {
                AppLogger.log("Embed", "Failed to init migan inpainter", e)
                initFailed.set(true)
                failed.set(true)
                null
            }
            if (inpainter == null) {
                return@withContext EmbedResult.Failure(appContext.getString(R.string.folder_embed_failed))
            }

            coroutineScope {
                val workers = List(workerCount) {
                    async(workerDispatcher) {
                        val renderer = EmbeddedTextRenderer()

                        while (!failed.get()) {
                            val index = nextIndex.getAndIncrement()
                            if (index >= pending.size) {
                                return@async
                            }
                            val item = pending[index]
                            val image = item.first
                            val translation = item.second
                            val result = runCatching {
                                processSingleImage(
                                    sourceImage = image,
                                    translation = translation,
                                    inpainter = inpainter,
                                    renderer = renderer,
                                    verticalLayoutEnabled = verticalLayoutEnabled,
                                    useWhiteBubbleCover = useWhiteBubbleCover,
                                    useImageRepair = useImageRepair,
                                    outputDir = embeddedDir
                                )
                            }
                            if (result.isFailure || result.getOrNull() != true) {
                                val throwable = result.exceptionOrNull()
                                if (throwable != null) {
                                    AppLogger.log("Embed", "Embed failed for ${image.name}", throwable)
                                } else {
                                    AppLogger.log("Embed", "Embed failed for ${image.name}")
                                }
                                failedImage.compareAndSet(null, image)
                                failed.set(true)
                                return@async
                            }
                            val done = doneCount.incrementAndGet()
                            onProgress(done, pending.size)
                        }
                    }
                }
                workers.awaitAll()
            }
        } finally {
            workerDispatcher.close()
        }

        if (failed.get()) {
            if (initFailed.get()) {
                return@withContext EmbedResult.Failure(appContext.getString(R.string.folder_embed_failed))
            }
            val image = failedImage.get()
            if (image != null) {
                return@withContext EmbedResult.Failure(
                    appContext.getString(R.string.folder_embed_failed_image, image.name)
                )
            }
            return@withContext EmbedResult.Failure(appContext.getString(R.string.folder_embed_failed))
        }

        if (images.all {
                File(embeddedDir, ImageFileSupport.resolveRenderedOutputName(it.name)).exists()
            }
        ) {
            embeddedStateStore.writeEmbeddedState(folder, images.size)
        }
        EmbedResult.Success
    }

    private fun processSingleImage(
        sourceImage: File,
        translation: TranslationResult,
        inpainter: MiganInpainter,
        renderer: EmbeddedTextRenderer,
        verticalLayoutEnabled: Boolean,
        useWhiteBubbleCover: Boolean,
        useImageRepair: Boolean,
        outputDir: File
    ): Boolean {
        val bitmap = BitmapFactory.decodeFile(sourceImage.absolutePath) ?: return false
        val covered = if (useWhiteBubbleCover) {
            val bubbleCoverMask = buildBubbleInteriorMask(
                translation = translation,
                width = bitmap.width,
                height = bitmap.height
            )
            applyPureWhiteCover(bitmap, bubbleCoverMask)
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        val repaired = if (useImageRepair) {
            repairNonBubbleTextRegions(
                source = covered,
                translation = translation,
                inpainter = inpainter
            )
        } else {
            covered.copy(Bitmap.Config.ARGB_8888, true)
        }
        val rendered = renderer.render(
            source = repaired,
            translation = translation,
            verticalLayoutEnabled = verticalLayoutEnabled,
            shouldDrawTextBackground = { true }
        )
        val outputFile = File(outputDir, ImageFileSupport.resolveRenderedOutputName(sourceImage.name))
        val saved = saveBitmap(outputFile, rendered)

        if (rendered !== repaired) {
            rendered.recycle()
        }
        if (repaired !== covered) {
            repaired.recycle()
        }
        if (covered !== bitmap) {
            covered.recycle()
        }
        bitmap.recycle()
        return saved
    }

    private fun repairNonBubbleTextRegions(
        source: Bitmap,
        translation: TranslationResult,
        inpainter: MiganInpainter
    ): Bitmap {
        var repaired = source.copy(Bitmap.Config.ARGB_8888, true)
        val nonBubbleTargets = translation.bubbles.filter { it.source != BubbleSource.BUBBLE_DETECTOR }
        for (bubble in nonBubbleTargets) {
            val bubbleMask = buildSingleBubbleRepairMask(
                bitmap = repaired,
                bubble = bubble
            )
            if (!bubbleMask.any { it }) continue
            val next = inpainter.inpaint(repaired, bubbleMask)
            if (next !== repaired) {
                repaired.recycle()
            }
            repaired = next
        }
        return repaired
    }

    private fun buildSingleBubbleRepairMask(
        bitmap: Bitmap,
        bubble: BubbleTranslation
    ): BooleanArray {
        val width = bitmap.width
        val height = bitmap.height
        val mask = BooleanArray(width * height)
        val left = bubble.rect.left.toInt().coerceIn(0, width - 1)
        val top = bubble.rect.top.toInt().coerceIn(0, height - 1)
        val right = bubble.rect.right.toInt().coerceIn(left + 1, width)
        val bottom = bubble.rect.bottom.toInt().coerceIn(top + 1, height)
        val cropW = right - left
        val cropH = bottom - top
        if (cropW <= 1 || cropH <= 1) return mask

        for (y in top until bottom) {
            val row = y * width
            for (x in left until right) {
                mask[row + x] = true
            }
        }
        return mask
    }

    private fun applyPureWhiteCover(source: Bitmap, coverMask: BooleanArray): Bitmap {
        val prepared = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = source.width
        val height = source.height
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!coverMask[row + x]) continue
                prepared[x, y] = Color.WHITE
            }
        }
        return prepared
    }

    private fun buildBubbleInteriorMask(
        translation: TranslationResult,
        width: Int,
        height: Int
    ): BooleanArray {
        val scaleX = if (translation.width > 0) width.toFloat() / translation.width.toFloat() else 1f
        val scaleY = if (translation.height > 0) height.toFloat() / translation.height.toFloat() else 1f
        val mask = BooleanArray(width * height)
        for (bubble in translation.bubbles) {
            if (bubble.source != BubbleSource.BUBBLE_DETECTOR) continue
            val scaled = android.graphics.RectF(
                bubble.rect.left * scaleX,
                bubble.rect.top * scaleY,
                bubble.rect.right * scaleX,
                bubble.rect.bottom * scaleY
            )
            markBubbleInteriorEllipse(mask, scaled, width, height)
        }
        return mask
    }

    private fun markBubbleInteriorEllipse(
        mask: BooleanArray,
        rect: android.graphics.RectF,
        width: Int,
        height: Int
    ) {
        val left = rect.left.coerceIn(0f, (width - 1).toFloat())
        val top = rect.top.coerceIn(0f, (height - 1).toFloat())
        val right = rect.right.coerceIn((left + 1f).coerceAtMost(width.toFloat()), width.toFloat())
        val bottom = rect.bottom.coerceIn((top + 1f).coerceAtMost(height.toFloat()), height.toFloat())
        val rectW = right - left
        val rectH = bottom - top
        if (rectW <= 1f || rectH <= 1f) return

        val cx = (left + right) * 0.5f
        val cy = (top + bottom) * 0.5f
        val inset = max(BUBBLE_FILL_MIN_INSET_PX, min(rectW, rectH) * BUBBLE_FILL_INSET_RATIO)
        val radiusX = (rectW * 0.5f - inset).coerceAtLeast(1f)
        val radiusY = (rectH * 0.5f - inset).coerceAtLeast(1f)

        val minX = (cx - radiusX).toInt().coerceIn(0, width - 1)
        val maxX = (cx + radiusX).toInt().coerceIn(0, width - 1)
        val minY = (cy - radiusY).toInt().coerceIn(0, height - 1)
        val maxY = (cy + radiusY).toInt().coerceIn(0, height - 1)
        val invRX2 = 1f / (radiusX * radiusX)
        val invRY2 = 1f / (radiusY * radiusY)

        for (y in minY..maxY) {
            val py = y + 0.5f - cy
            val pyTerm = py * py * invRY2
            val row = y * width
            for (x in minX..maxX) {
                val px = x + 0.5f - cx
                if (px * px * invRX2 + pyTerm <= 1f) {
                    mask[row + x] = true
                }
            }
        }
    }

    private fun saveBitmap(target: File, bitmap: Bitmap): Boolean {
        val ext = target.name.substringAfterLast('.', "").lowercase()
        val format = when (ext) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP
            }
            else -> Bitmap.CompressFormat.JPEG
        }
        val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 95
        return try {
            FileOutputStream(target).use { output ->
                bitmap.compress(format, quality, output)
            }
        } catch (e: Exception) {
            AppLogger.log("Embed", "Save embedded image failed: ${target.absolutePath}", e)
            false
        }
    }

    private sealed class EmbedResult {
        data object Success : EmbedResult()
        data class Failure(val message: String) : EmbedResult()
    }

    companion object {
        private const val BUBBLE_FILL_INSET_RATIO = 0.025f
        private const val BUBBLE_FILL_MIN_INSET_PX = 2f
        private const val KEY_EMBED_THREADS = "embed_threads"
        private const val KEY_EMBED_WHITE_BUBBLE_COVER = "embed_white_bubble_cover"
        private const val KEY_EMBED_IMAGE_REPAIR = "embed_image_repair"
        private const val DEFAULT_EMBED_THREADS = 2
        private const val DEFAULT_EMBED_WHITE_BUBBLE_COVER = true
        private const val DEFAULT_EMBED_IMAGE_REPAIR = false
        private const val MIN_EMBED_THREADS = 1
        private const val MAX_EMBED_THREADS = 16
    }

    private fun normalizeEmbedThreads(value: Int): Int {
        return value.coerceIn(MIN_EMBED_THREADS, MAX_EMBED_THREADS)
    }
}
