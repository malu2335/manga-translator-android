package com.manga.translate

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class FolderTranslationCoordinator(
    context: Context,
    private val translationPipeline: TranslationPipeline,
    private val glossaryStore: GlossaryStore,
    private val extractStateStore: ExtractStateStore,
    private val translationStore: TranslationStore,
    private val settingsStore: SettingsStore,
    private val llmClient: LlmClient,
    private val ui: LibraryUiCallbacks
) {
    private data class ResumeTranslationTask(
        val folder: File,
        val images: List<File>,
        val force: Boolean,
        val fullTranslate: Boolean,
        val useVlDirectTranslate: Boolean,
        val language: TranslationLanguage,
        val onTranslateEnabled: (Boolean) -> Unit
    )

    private val appContext = context.applicationContext
    private val translationRunning = AtomicBoolean(false)
    private val cancellationRequested = AtomicBoolean(false)
    @Volatile
    private var activeJob: Job? = null
    @Volatile
    private var resumableTask: ResumeTranslationTask? = null

    fun translateFolder(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        force: Boolean,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        onTranslateEnabled: (Boolean) -> Unit
    ) {
        resumableTask = ResumeTranslationTask(
            folder = folder,
            images = images.toList(),
            force = force,
            fullTranslate = fullTranslate,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language,
            onTranslateEnabled = onTranslateEnabled
        )
        if (fullTranslate) {
            translateFolderFull(scope, folder, images, force, language, onTranslateEnabled)
        } else {
            translateFolderStandard(
                scope,
                folder,
                images,
                force,
                useVlDirectTranslate,
                language,
                onTranslateEnabled
            )
        }
    }

    fun resumeFailedTranslation(scope: CoroutineScope): Boolean {
        val task = resumableTask ?: return false
        translateFolder(
            scope = scope,
            folder = task.folder,
            images = task.images,
            force = task.force,
            fullTranslate = task.fullTranslate,
            useVlDirectTranslate = task.useVlDirectTranslate,
            language = task.language,
            onTranslateEnabled = task.onTranslateEnabled
        )
        return true
    }

    private fun translateFolderStandard(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        force: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        onTranslateEnabled: (Boolean) -> Unit
    ) {
        if (images.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return
        }
        val pendingImages = resolvePendingImages(images, force)
        if (pendingImages.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.translation_done))
            return
        }
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return
        }
        if (!translationRunning.compareAndSet(false, true)) {
            ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
            return
        }

        cancellationRequested.set(false)
        TranslationCancellationRegistry.register { cancelActiveTranslation() }
        onTranslateEnabled(false)
        try {
            TranslationKeepAliveService.start(appContext)
            TranslationKeepAliveService.updateStatus(
                appContext,
                appContext.getString(R.string.translation_preparing)
            )
            AppLogger.log(
                "Library",
                "Start translating folder ${folder.name}, ${pendingImages.size} images"
            )

            val job = scope.launch {
                var failed = false
                try {
                    val glossary = glossaryStore.load(folder)
                    var translatedCount = 0
                    ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
                    for (image in pendingImages) {
                        currentCoroutineContext().ensureActive()
                        val result = try {
                            if (useVlDirectTranslate) {
                                val vlOutcome = translationPipeline.translateImageWithVl(image)
                                when {
                                    vlOutcome.requiresVlModel -> {
                                        ui.showToast(R.string.folder_vl_model_required)
                                        failed = true
                                        break
                                    }
                                    vlOutcome.timedOut -> {
                                        ui.showToast(R.string.floating_translate_timeout)
                                        failed = true
                                        break
                                    }
                                    else -> vlOutcome.result
                                }
                            } else {
                                translationPipeline.translateImage(image, glossary, force, language) { }
                            }
                        } catch (e: LlmRequestException) {
                            AppLogger.log("Library", "Translation aborted for ${image.name}", e)
                            ui.showApiError(e.errorCode, e.responseBody)
                            failed = true
                            break
                        } catch (e: LlmResponseException) {
                            AppLogger.log("Library", "Invalid model response for ${image.name}", e)
                            ui.showModelError(e.responseContent) {
                                resumeFailedTranslation(scope)
                            }
                            failed = true
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.log("Library", "Translation failed for ${image.name}", e)
                            null
                        }
                        if (result != null) {
                            translationPipeline.saveResult(image, result)
                            translatedCount += 1
                        } else {
                            failed = true
                        }
                        if (glossary.isNotEmpty()) {
                            glossaryStore.save(folder, glossary)
                        }
                        ui.setFolderStatus(
                            appContext.getString(
                                R.string.folder_translation_count,
                                translatedCount,
                                pendingImages.size
                            )
                        )
                        TranslationKeepAliveService.updateProgress(
                            appContext,
                            translatedCount,
                            pendingImages.size
                        )
                        if (failed) break
                    }
                    ui.setFolderStatus(
                        if (failed) appContext.getString(R.string.translation_failed) else appContext.getString(
                            R.string.translation_done
                        )
                    )
                    if (failed) {
                        GlobalTaskProgressStore.fail(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_failed)
                        )
                    } else {
                        GlobalTaskProgressStore.complete(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_done)
                        )
                    }
                    AppLogger.log(
                        "Library",
                        "Folder translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                    )
                    if (!failed) {
                        resumableTask = null
                    }
                    ui.refreshImages(folder)
                } catch (e: CancellationException) {
                    if (cancellationRequested.get()) {
                        AppLogger.log("Library", "Folder translation canceled: ${folder.name}")
                        ui.setFolderStatus(appContext.getString(R.string.translation_canceled))
                        ui.showToast(R.string.translation_canceled)
                        GlobalTaskProgressStore.complete(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_canceled)
                        )
                        ui.refreshImages(folder)
                    } else {
                        throw e
                    }
                } finally {
                    activeJob = null
                    TranslationCancellationRegistry.clear()
                    cancellationRequested.set(false)
                    onTranslateEnabled(true)
                    TranslationKeepAliveService.stop(appContext)
                    translationRunning.set(false)
                }
            }
            activeJob = job
            if (cancellationRequested.get()) {
                job.cancel(CancellationException(USER_CANCELED_REASON))
            }
        } catch (e: Exception) {
            activeJob = null
            TranslationCancellationRegistry.clear()
            cancellationRequested.set(false)
            onTranslateEnabled(true)
            TranslationKeepAliveService.stop(appContext)
            translationRunning.set(false)
            AppLogger.log("Library", "Failed to start folder translation ${folder.name}", e)
            ui.setFolderStatus(appContext.getString(R.string.translation_failed))
            GlobalTaskProgressStore.fail(
                appContext.getString(R.string.translation_keepalive_title),
                appContext.getString(R.string.translation_failed)
            )
        }
    }

    private fun translateFolderFull(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        force: Boolean,
        language: TranslationLanguage,
        onTranslateEnabled: (Boolean) -> Unit
    ) {
        if (images.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return
        }
        val pendingImages = resolvePendingImages(images, force)
        if (pendingImages.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.translation_done))
            return
        }
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return
        }
        if (!translationRunning.compareAndSet(false, true)) {
            ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
            return
        }

        cancellationRequested.set(false)
        TranslationCancellationRegistry.register { cancelActiveTranslation() }
        onTranslateEnabled(false)
        try {
            TranslationKeepAliveService.start(appContext)
            TranslationKeepAliveService.updateStatus(
                appContext,
                appContext.getString(R.string.translation_preparing)
            )
            AppLogger.log(
                "Library",
                "Start full-page translating folder ${folder.name}, ${pendingImages.size} images"
            )

            val job = scope.launch {
                var failed = false
                try {
                    val glossary = glossaryStore.load(folder).toMutableMap()
                    val extractState = extractStateStore.load(folder)
                    val ocrResults = ArrayList<PageOcrResult>(pendingImages.size)
                    reportPreprocessProgress(
                        stage = appContext.getString(R.string.folder_preprocess_stage_ocr),
                        processed = 0,
                        total = pendingImages.size
                    )
                    for ((index, image) in pendingImages.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        reportPreprocessProgress(
                            stage = appContext.getString(R.string.folder_preprocess_stage_ocr),
                            processed = index,
                            total = pendingImages.size,
                            imageName = image.name
                        )
                        val result = try {
                            translationPipeline.ocrImage(image, force, language) { }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.log("Library", "OCR failed for ${image.name}", e)
                            null
                        }
                        if (result != null) {
                            ocrResults.add(result)
                        } else {
                            failed = true
                        }
                        reportPreprocessProgress(
                            stage = appContext.getString(R.string.folder_preprocess_stage_ocr),
                            processed = index + 1,
                            total = pendingImages.size,
                            imageName = image.name
                        )
                    }

                    val glossaryPages = ocrResults.filterNot {
                        translationStore.translationFileFor(it.imageFile).exists() ||
                            extractState.contains(it.imageFile.name)
                    }
                    val glossaryText = buildGlossaryText(glossaryPages)
                    if (glossaryText.isNotBlank()) {
                        val glossaryStage = appContext.getString(R.string.folder_preprocess_stage_glossary)
                        val glossaryImage = glossaryPages.firstOrNull()?.imageFile?.name
                        reportPreprocessProgress(
                            stage = glossaryStage,
                            processed = 0,
                            total = 1,
                            imageName = glossaryImage.orEmpty()
                        )
                        val abstractPromptAsset = "llm_prompts_abstract.json"
                        val extracted = llmClient.extractGlossary(glossaryText, glossary, abstractPromptAsset)
                        if (extracted != null) {
                            if (extracted.isNotEmpty()) {
                                for ((key, value) in extracted) {
                                    if (!glossary.containsKey(key)) {
                                        glossary[key] = value
                                    }
                                }
                                glossaryStore.save(folder, glossary)
                            }
                            for (page in glossaryPages) {
                                extractState.add(page.imageFile.name)
                            }
                            extractStateStore.save(folder, extractState)
                        }
                        reportPreprocessProgress(
                            stage = glossaryStage,
                            processed = 1,
                            total = 1,
                            imageName = glossaryImage.orEmpty()
                        )
                    }

                    val maxConcurrency = settingsStore.loadMaxConcurrency()
                    val semaphore = Semaphore(maxConcurrency)
                    val translatedCount = AtomicInteger(0)
                    val hasFailures = AtomicBoolean(false)
                    val requestFailed = AtomicBoolean(false)
                    val reportedModelError = AtomicBoolean(false)
                    val requestException = AtomicReference<LlmRequestException?>(null)
                    ui.setFolderStatus(appContext.getString(R.string.translation_preparing))

                    supervisorScope {
                        val tasks = ocrResults.map { page ->
                            async {
                                semaphore.withPermit {
                                    currentCoroutineContext().ensureActive()
                                    if (requestFailed.get()) {
                                        return@withPermit
                                    }
                                    val fullTransPromptAsset = "llm_prompts_FullTrans.json"
                                    val result = try {
                                        translationPipeline.translateFullPage(
                                            page,
                                            glossary,
                                            fullTransPromptAsset,
                                            language
                                        ) { }
                                    } catch (e: LlmResponseException) {
                                        AppLogger.log(
                                            "Library",
                                            "Invalid model response for ${page.imageFile.name}",
                                            e
                                        )
                                        hasFailures.set(true)
                                        if (reportedModelError.compareAndSet(false, true)) {
                                            withContext(Dispatchers.Main) {
                                                ui.showModelError(e.responseContent) {
                                                    resumeFailedTranslation(scope)
                                                }
                                            }
                                        }
                                        null
                                    } catch (e: LlmRequestException) {
                                        requestFailed.set(true)
                                        requestException.compareAndSet(null, e)
                                        AppLogger.log(
                                            "Library",
                                            "Full-page translation aborted for ${page.imageFile.name}",
                                            e
                                        )
                                        null
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        AppLogger.log(
                                            "Library",
                                            "Full-page translation failed for ${page.imageFile.name}",
                                            e
                                        )
                                        null
                                    }
                                    if (requestFailed.get()) {
                                        return@withPermit
                                    }
                                    if (result != null) {
                                        translationPipeline.saveResult(page.imageFile, result)
                                        translatedCount.incrementAndGet()
                                    } else {
                                        hasFailures.set(true)
                                    }
                                    withContext(Dispatchers.Main) {
                                        val count = translatedCount.get()
                                        ui.setFolderStatus(
                                            appContext.getString(
                                                R.string.folder_translation_count,
                                                count,
                                                pendingImages.size
                                            )
                                        )
                                        TranslationKeepAliveService.updateProgress(
                                            appContext,
                                            count,
                                            pendingImages.size
                                        )
                                    }
                                }
                            }
                        }
                        tasks.awaitAll()
                    }

                    requestException.get()?.let { throw it }

                    failed = failed || hasFailures.get()
                    ui.setFolderStatus(
                        if (failed) appContext.getString(R.string.translation_failed) else appContext.getString(
                            R.string.translation_done
                        )
                    )
                    if (failed) {
                        GlobalTaskProgressStore.fail(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_failed)
                        )
                    } else {
                        GlobalTaskProgressStore.complete(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_done)
                        )
                    }
                    AppLogger.log(
                        "Library",
                        "Full-page translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                    )
                    if (!failed) {
                        resumableTask = null
                    }
                    ui.refreshImages(folder)
                } catch (e: LlmRequestException) {
                    AppLogger.log("Library", "Full-page translation aborted", e)
                    ui.showApiError(e.errorCode, e.responseBody)
                    ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                    GlobalTaskProgressStore.fail(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_failed)
                    )
                } catch (e: CancellationException) {
                    if (cancellationRequested.get()) {
                        AppLogger.log("Library", "Full-page translation canceled: ${folder.name}")
                        ui.setFolderStatus(appContext.getString(R.string.translation_canceled))
                        ui.showToast(R.string.translation_canceled)
                        GlobalTaskProgressStore.complete(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_canceled)
                        )
                        ui.refreshImages(folder)
                    } else {
                        throw e
                    }
                } finally {
                    activeJob = null
                    TranslationCancellationRegistry.clear()
                    cancellationRequested.set(false)
                    onTranslateEnabled(true)
                    TranslationKeepAliveService.stop(appContext)
                    translationRunning.set(false)
                }
            }
            activeJob = job
            if (cancellationRequested.get()) {
                job.cancel(CancellationException(USER_CANCELED_REASON))
            }
        } catch (e: Exception) {
            activeJob = null
            TranslationCancellationRegistry.clear()
            cancellationRequested.set(false)
            onTranslateEnabled(true)
            TranslationKeepAliveService.stop(appContext)
            translationRunning.set(false)
            AppLogger.log("Library", "Failed to start full-page translation ${folder.name}", e)
            ui.setFolderStatus(appContext.getString(R.string.translation_failed))
            GlobalTaskProgressStore.fail(
                appContext.getString(R.string.translation_keepalive_title),
                appContext.getString(R.string.translation_failed)
            )
        }
    }

    private fun buildGlossaryText(pages: List<PageOcrResult>): String {
        val builder = StringBuilder()
        for (page in pages) {
            for (bubble in page.bubbles) {
                val text = bubble.text.trim()
                if (text.isNotBlank()) {
                    builder.append("<b>").append(text).append("</b>\n")
                }
            }
        }
        return builder.toString().trim()
    }

    private fun resolvePendingImages(images: List<File>, force: Boolean): List<File> {
        return if (force) {
            images
        } else {
            images.filterNot { translationStore.translationFileFor(it).exists() }
        }
    }

    private fun reportPreprocessProgress(
        stage: String,
        processed: Int,
        total: Int,
        imageName: String = ""
    ) {
        val safeTotal = total.coerceAtLeast(1)
        val safeProcessed = processed.coerceIn(0, safeTotal)
        val left = appContext.getString(
            R.string.folder_preprocess_progress,
            stage,
            safeProcessed,
            safeTotal
        )
        ui.setFolderStatus(left, imageName)
        val content = if (imageName.isBlank()) left else "$left  $imageName"
        TranslationKeepAliveService.updateProgress(
            appContext,
            safeProcessed,
            safeTotal,
            content,
            appContext.getString(R.string.translation_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message)
        )
    }

    private fun cancelActiveTranslation(): Boolean {
        if (!translationRunning.get()) {
            return false
        }
        cancellationRequested.set(true)
        activeJob?.cancel(CancellationException(USER_CANCELED_REASON))
        return true
    }

    private companion object {
        private const val USER_CANCELED_REASON = "user_canceled_translation"
    }
}
