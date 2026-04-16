package com.manga.translate

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

internal data class FolderTranslationTask(
    val folder: File,
    val images: List<File>,
    val force: Boolean,
    val fullTranslate: Boolean,
    val useVlDirectTranslate: Boolean,
    val language: TranslationLanguage
)

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

    private data class PreparedCollectionTask(
        val folder: File,
        val pendingImages: List<File>,
        val force: Boolean,
        val fullTranslate: Boolean,
        val useVlDirectTranslate: Boolean,
        val language: TranslationLanguage
    )

    private enum class CollectionTaskResult {
        SUCCESS,
        FAILED,
        ABORTED
    }

    private enum class ModelErrorAction {
        RETRY,
        SKIP
    }

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

    fun translateCollection(
        scope: CoroutineScope,
        collectionFolder: File,
        tasks: List<FolderTranslationTask>,
        onTranslateEnabled: (Boolean) -> Unit
    ) {
        translateTaskBatch(
            scope = scope,
            tasks = tasks,
            onTranslateEnabled = onTranslateEnabled,
            onFinished = { ui.refreshImages(collectionFolder) }
        )
    }

    fun translateBatch(
        scope: CoroutineScope,
        tasks: List<FolderTranslationTask>,
        onTranslateEnabled: (Boolean) -> Unit
    ) {
        translateTaskBatch(
            scope = scope,
            tasks = tasks,
            onTranslateEnabled = onTranslateEnabled,
            onFinished = { ui.refreshFolders() }
        )
    }

    private fun translateTaskBatch(
        scope: CoroutineScope,
        tasks: List<FolderTranslationTask>,
        onTranslateEnabled: (Boolean) -> Unit,
        onFinished: () -> Unit
    ) {
        if (tasks.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_chapters_empty))
            return
        }
        val preparedTasks = tasks.mapNotNull { task ->
            val pendingImages = resolvePendingImages(
                images = task.images,
                force = task.force,
                fullTranslate = task.fullTranslate,
                useVlDirectTranslate = task.useVlDirectTranslate,
                language = task.language
            )
            if (pendingImages.isEmpty()) {
                null
            } else {
                PreparedCollectionTask(
                    folder = task.folder,
                    pendingImages = pendingImages,
                    force = task.force,
                    fullTranslate = task.fullTranslate,
                    useVlDirectTranslate = task.useVlDirectTranslate,
                    language = task.language
                )
            }
        }
        if (preparedTasks.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.translation_done))
            onFinished()
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

        resumableTask = null
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
                "Start translating task batch, ${preparedTasks.size} folders"
            )

            val totalImages = preparedTasks.sumOf { it.pendingImages.size }.coerceAtLeast(1)
            val job = scope.launch {
                var failed = false
                try {
                    var translatedImages = 0
                    for ((index, task) in preparedTasks.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val result = if (task.fullTranslate) {
                            translateCollectionFolderFull(
                                scope = scope,
                                task = task,
                                chapterIndex = index,
                                chapterTotal = preparedTasks.size,
                                translatedImages = translatedImages,
                                totalImages = totalImages
                            )
                        } else {
                            translateCollectionFolderStandard(
                                scope = scope,
                                task = task,
                                chapterIndex = index,
                                chapterTotal = preparedTasks.size,
                                translatedImages = translatedImages,
                                totalImages = totalImages
                            )
                        }
                        when (result) {
                            CollectionTaskResult.SUCCESS -> {
                                translatedImages += task.pendingImages.size
                            }
                            CollectionTaskResult.FAILED -> {
                                translatedImages += task.pendingImages.size
                                failed = true
                            }
                            CollectionTaskResult.ABORTED -> {
                                failed = true
                                break
                            }
                        }
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
                    onFinished()
                } catch (e: CancellationException) {
                    if (cancellationRequested.get()) {
                        AppLogger.log("Library", "Batch translation canceled")
                        ui.setFolderStatus(appContext.getString(R.string.translation_canceled))
                        ui.showToast(R.string.translation_canceled)
                        GlobalTaskProgressStore.complete(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_canceled)
                        )
                        onFinished()
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
            AppLogger.log("Library", "Failed to start batch translation", e)
            ui.setFolderStatus(appContext.getString(R.string.translation_failed))
            GlobalTaskProgressStore.fail(
                appContext.getString(R.string.translation_keepalive_title),
                appContext.getString(R.string.translation_failed)
            )
        }
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
        val pendingImages = resolvePendingImages(
            images = images,
            force = force,
            fullTranslate = false,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language
        )
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
                        var recoveredFromModelError = false
                        val result = try {
                            if (useVlDirectTranslate) {
                                val vlOutcome = translationPipeline.translateImageWithVl(
                                    imageFile = image,
                                    language = language
                                )
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
                            when (reportModelError(e.responseContent)) {
                                ModelErrorAction.RETRY -> {
                                    recoveredFromModelError =
                                        retryStandardImage(folder, image, force, language)
                                }
                                ModelErrorAction.SKIP -> {
                                    skipStandardImage(folder, image, force, language)
                                    recoveredFromModelError = true
                                }
                            }
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.log("Library", "Translation failed for ${image.name}", e)
                            null
                        }
                        if (result != null) {
                            translationPipeline.saveResult(image, result)
                            translatedCount += 1
                        } else if (recoveredFromModelError) {
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
        val pendingImages = resolvePendingImages(
            images = images,
            force = force,
            fullTranslate = true,
            useVlDirectTranslate = false,
            language = language
        )
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
                        translationPipeline.hasValidTranslation(
                            imageFile = it.imageFile,
                            fullTranslate = true,
                            useVlDirectTranslate = false,
                            language = language
                        ) ||
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
                        while (true) {
                            try {
                                val extracted =
                                    llmClient.extractGlossary(glossaryText, glossary, abstractPromptAsset)
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
                                break
                            } catch (e: LlmRequestException) {
                                throw e
                            } catch (e: LlmResponseException) {
                                AppLogger.log("Library", "Full-page glossary response invalid", e)
                                if (reportModelError(e.responseContent) == ModelErrorAction.SKIP) {
                                    failed = true
                                    break
                                }
                            }
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
                                    var recoveredFromModelError = false
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
                                        when (reportModelError(e.responseContent)) {
                                            ModelErrorAction.RETRY -> {
                                                recoveredFromModelError = retryFullPageImage(
                                                    folder,
                                                    page,
                                                    fullTransPromptAsset,
                                                    language
                                                )
                                            }
                                            ModelErrorAction.SKIP -> {
                                                skipFullPageImage(
                                                    folder,
                                                    page,
                                                    fullTransPromptAsset,
                                                    language
                                                )
                                                recoveredFromModelError = true
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
                                    } else if (recoveredFromModelError) {
                                        translatedCount.incrementAndGet()
                                    } else {
                                        hasFailures.set(true)
                                    }
                                    withContext(Dispatchers.Main) {
                                        val processedCount: Int = translatedCount.get()
                                        ui.setFolderStatus(
                                            appContext.getString(
                                                R.string.folder_translation_count,
                                                processedCount,
                                                pendingImages.size
                                            )
                                        )
                                        TranslationKeepAliveService.updateProgress(
                                            appContext,
                                            processedCount,
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

    private suspend fun translateCollectionFolderStandard(
        scope: CoroutineScope,
        task: PreparedCollectionTask,
        chapterIndex: Int,
        chapterTotal: Int,
        translatedImages: Int,
        totalImages: Int
    ): CollectionTaskResult {
        var failed = false
        val glossary = glossaryStore.load(task.folder)
        for ((imageIndex, image) in task.pendingImages.withIndex()) {
            currentCoroutineContext().ensureActive()
            reportCollectionProgress(
                chapterIndex = chapterIndex,
                chapterTotal = chapterTotal,
                imageIndex = translatedImages + imageIndex,
                imageTotal = totalImages,
                chapterName = task.folder.name,
                imageName = image.name
            )
            var recoveredFromModelError = false
            val result = try {
                if (task.useVlDirectTranslate) {
                    val vlOutcome = translationPipeline.translateImageWithVl(
                        imageFile = image,
                        language = task.language
                    )
                    when {
                        vlOutcome.requiresVlModel -> {
                            ui.showToast(R.string.folder_vl_model_required)
                            return CollectionTaskResult.ABORTED
                        }
                        vlOutcome.timedOut -> {
                            ui.showToast(R.string.floating_translate_timeout)
                            return CollectionTaskResult.ABORTED
                        }
                        else -> vlOutcome.result
                    }
                } else {
                    translationPipeline.translateImage(
                        image,
                        glossary,
                        task.force,
                        task.language
                    ) { }
                }
            } catch (e: LlmRequestException) {
                AppLogger.log("Library", "Collection translation aborted for ${image.name}", e)
                ui.showApiError(e.errorCode, e.responseBody)
                return CollectionTaskResult.ABORTED
            } catch (e: LlmResponseException) {
                AppLogger.log("Library", "Invalid model response for ${image.name}", e)
                when (reportModelError(e.responseContent)) {
                    ModelErrorAction.RETRY -> {
                        recoveredFromModelError =
                            retryStandardImage(task.folder, image, task.force, task.language)
                    }
                    ModelErrorAction.SKIP -> {
                        skipStandardImage(task.folder, image, task.force, task.language)
                        recoveredFromModelError = true
                    }
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("Library", "Collection translation failed for ${image.name}", e)
                null
            }
            if (result != null) {
                translationPipeline.saveResult(image, result)
            } else if (!recoveredFromModelError) {
                failed = true
            }
            if (glossary.isNotEmpty()) {
                glossaryStore.save(task.folder, glossary)
            }
            reportCollectionProgress(
                chapterIndex = chapterIndex,
                chapterTotal = chapterTotal,
                imageIndex = translatedImages + imageIndex + 1,
                imageTotal = totalImages,
                chapterName = task.folder.name,
                imageName = image.name
            )
        }
        return if (failed) CollectionTaskResult.FAILED else CollectionTaskResult.SUCCESS
    }

    private suspend fun translateCollectionFolderFull(
        scope: CoroutineScope,
        task: PreparedCollectionTask,
        chapterIndex: Int,
        chapterTotal: Int,
        translatedImages: Int,
        totalImages: Int
    ): CollectionTaskResult {
        var failed = false
        val glossary = glossaryStore.load(task.folder).toMutableMap()
        val extractState = extractStateStore.load(task.folder)
        val ocrResults = ArrayList<PageOcrResult>(task.pendingImages.size)
        for ((index, image) in task.pendingImages.withIndex()) {
            currentCoroutineContext().ensureActive()
            reportCollectionProgress(
                chapterIndex = chapterIndex,
                chapterTotal = chapterTotal,
                imageIndex = translatedImages + index,
                imageTotal = totalImages,
                chapterName = task.folder.name,
                imageName = image.name
            )
            val result = try {
                translationPipeline.ocrImage(image, task.force, task.language) { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("Library", "Collection OCR failed for ${image.name}", e)
                null
            }
            if (result != null) {
                ocrResults.add(result)
            } else {
                failed = true
            }
        }

        val glossaryPages = ocrResults.filterNot {
            translationPipeline.hasValidTranslation(
                imageFile = it.imageFile,
                fullTranslate = true,
                useVlDirectTranslate = false,
                language = task.language
            ) || extractState.contains(it.imageFile.name)
        }
        val glossaryText = buildGlossaryText(glossaryPages)
        if (glossaryText.isNotBlank()) {
            val abstractPromptAsset = "llm_prompts_abstract.json"
            while (true) {
                try {
                    val extracted = llmClient.extractGlossary(glossaryText, glossary, abstractPromptAsset)
                    if (extracted != null) {
                        if (extracted.isNotEmpty()) {
                            for ((key, value) in extracted) {
                                if (!glossary.containsKey(key)) {
                                    glossary[key] = value
                                }
                            }
                            glossaryStore.save(task.folder, glossary)
                        }
                        for (page in glossaryPages) {
                            extractState.add(page.imageFile.name)
                        }
                        extractStateStore.save(task.folder, extractState)
                    }
                    break
                } catch (e: LlmRequestException) {
                    AppLogger.log("Library", "Collection glossary extraction aborted", e)
                    ui.showApiError(e.errorCode, e.responseBody)
                    return CollectionTaskResult.ABORTED
                } catch (e: LlmResponseException) {
                    AppLogger.log("Library", "Collection glossary response invalid", e)
                    if (reportModelError(e.responseContent) == ModelErrorAction.SKIP) {
                        failed = true
                        break
                    }
                }
            }
        }

        for ((index, page) in ocrResults.withIndex()) {
            currentCoroutineContext().ensureActive()
            reportCollectionProgress(
                chapterIndex = chapterIndex,
                chapterTotal = chapterTotal,
                imageIndex = translatedImages + index,
                imageTotal = totalImages,
                chapterName = task.folder.name,
                imageName = page.imageFile.name
            )
            val fullTransPromptAsset = "llm_prompts_FullTrans.json"
            var recoveredFromModelError = false
            val result = try {
                translationPipeline.translateFullPage(
                    page,
                    glossary,
                    fullTransPromptAsset,
                    task.language
                ) { }
            } catch (e: LlmResponseException) {
                AppLogger.log("Library", "Invalid collection model response for ${page.imageFile.name}", e)
                when (reportModelError(e.responseContent)) {
                    ModelErrorAction.RETRY -> {
                        recoveredFromModelError = retryFullPageImage(
                            task.folder,
                            page,
                            fullTransPromptAsset,
                            task.language
                        )
                    }
                    ModelErrorAction.SKIP -> {
                        skipFullPageImage(
                            task.folder,
                            page,
                            fullTransPromptAsset,
                            task.language
                        )
                        recoveredFromModelError = true
                    }
                }
                null
            } catch (e: LlmRequestException) {
                AppLogger.log("Library", "Collection full translation aborted for ${page.imageFile.name}", e)
                ui.showApiError(e.errorCode, e.responseBody)
                return CollectionTaskResult.ABORTED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("Library", "Collection full translation failed for ${page.imageFile.name}", e)
                null
            }
            if (result != null) {
                translationPipeline.saveResult(page.imageFile, result)
            } else if (!recoveredFromModelError) {
                failed = true
            }
            reportCollectionProgress(
                chapterIndex = chapterIndex,
                chapterTotal = chapterTotal,
                imageIndex = translatedImages + index + 1,
                imageTotal = totalImages,
                chapterName = task.folder.name,
                imageName = page.imageFile.name
            )
        }

        return if (failed) CollectionTaskResult.FAILED else CollectionTaskResult.SUCCESS
    }

    private fun reportCollectionProgress(
        chapterIndex: Int,
        chapterTotal: Int,
        imageIndex: Int,
        imageTotal: Int,
        chapterName: String,
        imageName: String
    ) {
        val safeChapterIndex = (chapterIndex + 1).coerceIn(1, chapterTotal.coerceAtLeast(1))
        val safeChapterTotal = chapterTotal.coerceAtLeast(1)
        val safeImageIndex = imageIndex.coerceIn(0, imageTotal.coerceAtLeast(1))
        val safeImageTotal = imageTotal.coerceAtLeast(1)
        val left = appContext.getString(
            R.string.folder_collection_translation_progress,
            safeChapterIndex,
            safeChapterTotal,
            safeImageIndex,
            safeImageTotal
        )
        val right = appContext.getString(
            R.string.folder_collection_translation_target,
            chapterName,
            imageName
        )
        ui.setFolderStatus(left, right)
        TranslationKeepAliveService.updateProgress(
            appContext,
            safeImageIndex,
            safeImageTotal,
            "$left  $chapterName / $imageName",
            appContext.getString(R.string.translation_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message)
        )
    }

    private suspend fun reportModelError(content: String): ModelErrorAction {
        val resolution = CompletableDeferred<ModelErrorAction>()
        if (!ui.isUiAttached()) {
            AppLogger.log("Library", "Model error dialog skipped because UI is detached")
            return ModelErrorAction.SKIP
        }
        val appInForeground = ui.isAppInForeground()
        val useSystemOverlay = !appInForeground && ui.canShowSystemOverlay()
        if (!appInForeground && !useSystemOverlay) {
            AppLogger.log(
                "Library",
                "Model error dialog queued for foreground display because library is in background and overlay is unavailable"
            )
            TranslationKeepAliveService.notifyModelErrorNeedsAttention(appContext)
        }
        withContext(Dispatchers.Main) {
            ui.showModelError(
                content = content,
                useSystemOverlay = useSystemOverlay,
                onRetry = {
                    TranslationKeepAliveService.clearModelErrorAttention(appContext)
                    resolution.complete(ModelErrorAction.RETRY)
                },
                onSkip = {
                    TranslationKeepAliveService.clearModelErrorAttention(appContext)
                    resolution.complete(ModelErrorAction.SKIP)
                }
            )
        }
        return resolution.await()
    }

    private suspend fun skipStandardImage(
        folder: File,
        image: File,
        force: Boolean,
        language: TranslationLanguage
    ) {
        val blank = translationPipeline.buildBlankTranslationResult(
            imageFile = image,
            forceOcr = force,
            language = language
        ) ?: return
        translationPipeline.saveResult(image, blank)
        withContext(Dispatchers.Main) {
            ui.refreshImages(folder)
        }
    }

    private suspend fun retryStandardImage(
        folder: File,
        image: File,
        force: Boolean,
        language: TranslationLanguage
    ): Boolean {
        val glossary = glossaryStore.load(folder)
        val result = try {
            translationPipeline.translateImage(image, glossary, force, language) { }
        } catch (e: LlmResponseException) {
            AppLogger.log("Library", "Retry still returned invalid response for ${image.name}", e)
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Retry failed for ${image.name}", e)
            return false
        }
        if (result == null) {
            return false
        }
        translationPipeline.saveResult(image, result)
        if (glossary.isNotEmpty()) {
            glossaryStore.save(folder, glossary)
        }
        withContext(Dispatchers.Main) {
            ui.refreshImages(folder)
        }
        return true
    }

    private suspend fun retryFullPageImage(
        folder: File,
        page: PageOcrResult,
        promptAsset: String,
        language: TranslationLanguage
    ): Boolean {
        val glossary = glossaryStore.load(folder)
        val result = try {
            translationPipeline.translateFullPage(page, glossary, promptAsset, language) { }
        } catch (e: LlmResponseException) {
            AppLogger.log("Library", "Retry still returned invalid response for ${page.imageFile.name}", e)
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Retry failed for ${page.imageFile.name}", e)
            return false
        }
        if (result == null) {
            return false
        }
        translationPipeline.saveResult(page.imageFile, result)
        withContext(Dispatchers.Main) {
            ui.refreshImages(folder)
        }
        return true
    }

    private suspend fun skipFullPageImage(
        folder: File,
        page: PageOcrResult,
        promptAsset: String,
        language: TranslationLanguage
    ) {
        val blank = translationPipeline.buildBlankTranslationResult(
            page = page,
            mode = TranslationMetadata.MODE_FULL_PAGE,
            promptAsset = promptAsset,
            language = language
        )
        translationPipeline.saveResult(page.imageFile, blank)
        withContext(Dispatchers.Main) {
            ui.refreshImages(folder)
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

    private fun resolvePendingImages(
        images: List<File>,
        force: Boolean,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage
    ): List<File> {
        return if (force) {
            images
        } else {
            images.filterNot {
                translationPipeline.hasValidTranslation(
                    imageFile = it,
                    fullTranslate = fullTranslate,
                    useVlDirectTranslate = useVlDirectTranslate,
                    language = language
                )
            }
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
