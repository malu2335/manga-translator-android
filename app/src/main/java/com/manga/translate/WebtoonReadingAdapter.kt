package com.manga.translate

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.databinding.ItemReadingWebtoonPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WebtoonReadingAdapter(
    private val scope: CoroutineScope,
    private val translationStore: TranslationStore
) : RecyclerView.Adapter<WebtoonReadingAdapter.WebtoonPageViewHolder>() {
    data class BoundPageSnapshot(
        val imageFile: File,
        val translation: TranslationResult?,
        val sourceWidth: Int,
        val sourceHeight: Int
    )

    private data class PresentationConfig(
        val verticalLayoutEnabled: Boolean,
        val bubbleRenderSettings: NormalBubbleRenderSettings
    )

    private companion object {
        const val PAYLOAD_PRESENTATION_ONLY = "presentation_only"
        const val PAYLOAD_TRANSLATION_ONLY = "translation_only"
        const val DEFAULT_PLACEHOLDER_HEIGHT_RATIO = 1.4f
    }

    private var items: List<File> = emptyList()
    private var verticalLayoutEnabled: Boolean = true
    private var bubbleRenderSettings = NormalBubbleRenderSettings(
        shrinkPercent = 0,
        freeBubbleShrinkPercent = 0,
        freeBubbleOpacityPercent = 100,
        fontScalePercent = 100,
        useHorizontalText = true
    )
    private val rememberedPageHeights = mutableMapOf<String, Int>()
    private val boundHolders = mutableMapOf<String, WebtoonPageViewHolder>()
    private var editModeEnabled = false
    private var lockedPagePath: String? = null
    private var lockedPageTranslation: TranslationResult? = null
    private var lockedPageOffsets: Map<Int, Pair<Float, Float>> = emptyMap()

    var onLockedBubbleOffsetChanged: ((Int, Float, Float) -> Unit)? = null
    var onLockedBubbleRemove: ((Int) -> Unit)? = null
    var onLockedBubbleTap: ((Int) -> Unit)? = null
    var onLockedBubbleResizeTap: ((Int) -> Unit)? = null
    var onLockedBubbleLongPress: ((Int) -> Unit)? = null

    fun submit(
        images: List<File>,
        verticalLayoutEnabled: Boolean,
        bubbleRenderSettings: NormalBubbleRenderSettings
    ) {
        val previousItems = items
        val previousConfig = PresentationConfig(
            verticalLayoutEnabled = this.verticalLayoutEnabled,
            bubbleRenderSettings = this.bubbleRenderSettings
        )
        val newConfig = PresentationConfig(
            verticalLayoutEnabled = verticalLayoutEnabled,
            bubbleRenderSettings = bubbleRenderSettings
        )
        val diffResult = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previousItems.size

                override fun getNewListSize(): Int = images.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return previousItems[oldItemPosition].absolutePath == images[newItemPosition].absolutePath
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return areItemsTheSame(oldItemPosition, newItemPosition) && previousConfig == newConfig
                }

                override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
                    if (!areItemsTheSame(oldItemPosition, newItemPosition)) return null
                    return if (
                        previousConfig.verticalLayoutEnabled != newConfig.verticalLayoutEnabled ||
                        previousConfig.bubbleRenderSettings != newConfig.bubbleRenderSettings
                    ) {
                        PAYLOAD_PRESENTATION_ONLY
                    } else {
                        null
                    }
                }
            }
        )
        items = images
        this.verticalLayoutEnabled = verticalLayoutEnabled
        this.bubbleRenderSettings = bubbleRenderSettings
        diffResult.dispatchUpdatesTo(this)
    }

    fun updateEditSession(
        enabled: Boolean,
        lockedImagePath: String?,
        translation: TranslationResult?,
        offsets: Map<Int, Pair<Float, Float>>
    ) {
        val affectedPaths = linkedSetOf<String>()
        lockedPagePath?.let(affectedPaths::add)
        lockedImagePath?.let(affectedPaths::add)
        editModeEnabled = enabled
        lockedPagePath = lockedImagePath
        lockedPageTranslation = translation
        lockedPageOffsets = offsets.toMap()
        for (path in affectedPaths) {
            refreshPath(path)
        }
    }

    fun findBoundPageSnapshot(imagePath: String): BoundPageSnapshot? {
        return boundHolders[imagePath]?.buildSnapshot()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WebtoonPageViewHolder {
        val binding = ItemReadingWebtoonPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return WebtoonPageViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: WebtoonPageViewHolder, position: Int) {
        holder.bind(
            imageFile = items[position],
            verticalLayoutEnabled = verticalLayoutEnabled,
            bubbleRenderSettings = bubbleRenderSettings
        )
    }

    override fun onBindViewHolder(
        holder: WebtoonPageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_TRANSLATION_ONLY)) {
            holder.reloadTranslationOverlay()
            return
        }
        if (payloads.contains(PAYLOAD_PRESENTATION_ONLY)) {
            holder.updatePresentation(
                verticalLayoutEnabled = verticalLayoutEnabled,
                bubbleRenderSettings = bubbleRenderSettings
            )
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: WebtoonPageViewHolder) {
        holder.recycle()
    }

    fun notifyTranslationChanged(imagePath: String) {
        val index = items.indexOfFirst { it.absolutePath == imagePath }
        if (index >= 0) {
            notifyItemChanged(index, PAYLOAD_TRANSLATION_ONLY)
        }
    }

    private fun refreshPath(path: String) {
        val holder = boundHolders[path]
        if (holder != null) {
            holder.refreshOverlayPresentation()
            return
        }
        val index = items.indexOfFirst { it.absolutePath == path }
        if (index >= 0) {
            notifyItemChanged(index, PAYLOAD_PRESENTATION_ONLY)
        }
    }

    inner class WebtoonPageViewHolder(
        private val binding: ItemReadingWebtoonPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private var bindJob: Job? = null
        private var overlayReloadJob: Job? = null
        private var boundPath: String? = null
        private var boundFile: File? = null
        private var currentBitmap: Bitmap? = null
        private var currentImageWidth: Int = 0
        private var currentImageHeight: Int = 0
        private var currentTranslation: TranslationResult? = null

        fun bind(
            imageFile: File,
            verticalLayoutEnabled: Boolean,
            bubbleRenderSettings: NormalBubbleRenderSettings
        ) {
            val previousPath = boundPath
            if (previousPath != null && previousPath != imageFile.absolutePath) {
                boundHolders.remove(previousPath)
            }
            boundPath = imageFile.absolutePath
            boundFile = imageFile
            currentBitmap = null
            currentImageWidth = 0
            currentImageHeight = 0
            currentTranslation = null
            bindJob?.cancel()
            overlayReloadJob?.cancel()
            boundHolders[imageFile.absolutePath] = this
            binding.readingPageOverlay.setEditMode(false)
            binding.readingPageOverlay.setTouchPassthroughEnabled(true)
            binding.readingPageOverlay.setEditScrollThroughEnabled(false)
            binding.readingPageOverlay.setVerticalLayoutEnabled(verticalLayoutEnabled)
            binding.readingPageOverlay.setNormalBubbleRenderSettings(bubbleRenderSettings)
            binding.readingPageOverlay.onOffsetChanged = null
            binding.readingPageOverlay.onBubbleRemove = null
            binding.readingPageOverlay.onBubbleTap = null
            binding.readingPageOverlay.onBubbleResizeTap = null
            binding.readingPageOverlay.onBubbleLongPress = null
            binding.readingPageOverlay.visibility = View.GONE
            applyPlaceholder(imageFile)
            binding.readingPageImage.setImageDrawable(null)
            binding.root.doOnLayout {
                if (boundPath != imageFile.absolutePath) return@doOnLayout
                loadPage(imageFile)
            }
        }

        fun updatePresentation(
            verticalLayoutEnabled: Boolean,
            bubbleRenderSettings: NormalBubbleRenderSettings
        ) {
            binding.readingPageOverlay.setVerticalLayoutEnabled(verticalLayoutEnabled)
            binding.readingPageOverlay.setNormalBubbleRenderSettings(bubbleRenderSettings)
            refreshOverlayPresentation()
        }

        fun refreshOverlayPresentation() {
            val bitmap = currentBitmap ?: return
            bindOverlay(bitmap, currentTranslation)
        }

        fun buildSnapshot(): BoundPageSnapshot? {
            val imageFile = boundFile ?: return null
            return BoundPageSnapshot(
                imageFile = imageFile,
                translation = currentTranslation,
                sourceWidth = currentImageWidth,
                sourceHeight = currentImageHeight
            )
        }

        private fun loadPage(imageFile: File) {
            bindJob?.cancel()
            bindJob = scope.launch {
                val targetWidth = resolveTargetWidth()
                val targetHeight = resolveTargetHeight()
                val decoded = withContext(Dispatchers.IO) {
                    ReadingBitmapDecoder.decode(imageFile, targetWidth, targetHeight)
                }
                val bitmap = decoded?.bitmap
                val translation = withContext(Dispatchers.IO) { translationStore.load(imageFile) }
                if (boundPath != imageFile.absolutePath) return@launch
                if (bitmap == null) {
                    currentTranslation = translation
                    binding.readingPageImage.setImageDrawable(null)
                    binding.readingPageOverlay.visibility = View.GONE
                    showPlaceholder(imageFile.absolutePath)
                    return@launch
                }
                currentBitmap = bitmap
                currentImageWidth = decoded.sourceWidth
                currentImageHeight = decoded.sourceHeight
                currentTranslation = normalizeTranslation(translation)
                binding.readingPageImage.setImageBitmap(bitmap)
                binding.readingPageImage.doOnLayout {
                    if (boundPath != imageFile.absolutePath) return@doOnLayout
                    rememberedPageHeights[imageFile.absolutePath] = binding.readingPageImage.height
                    binding.readingPagePlaceholder.visibility = View.GONE
                    bindOverlay(bitmap, currentTranslation)
                }
            }
        }

        fun recycle() {
            bindJob?.cancel()
            overlayReloadJob?.cancel()
            boundPath?.let(boundHolders::remove)
            boundPath = null
            boundFile = null
            currentBitmap = null
            currentImageWidth = 0
            currentImageHeight = 0
            currentTranslation = null
            binding.readingPageImage.setImageDrawable(null)
            binding.readingPageOverlay.onOffsetChanged = null
            binding.readingPageOverlay.onBubbleRemove = null
            binding.readingPageOverlay.onBubbleTap = null
            binding.readingPageOverlay.onBubbleResizeTap = null
            binding.readingPageOverlay.onBubbleLongPress = null
            binding.readingPageOverlay.visibility = View.GONE
            binding.readingPagePlaceholder.visibility = View.VISIBLE
        }

        private fun applyPlaceholder(imageFile: File) {
            showPlaceholder(imageFile.absolutePath)
        }

        private fun showPlaceholder(path: String) {
            val targetHeight = rememberedPageHeights[path] ?: estimatePlaceholderHeight()
            updatePlaceholderHeight(targetHeight)
            binding.readingPagePlaceholder.visibility = View.VISIBLE
        }

        private fun estimatePlaceholderHeight(): Int {
            val metrics = binding.root.resources.displayMetrics
            val width = binding.root.width.takeIf { it > 0 } ?: metrics.widthPixels
            val estimated = (width * DEFAULT_PLACEHOLDER_HEIGHT_RATIO).toInt()
            val minHeight = (metrics.density * 240f).toInt()
            return estimated.coerceAtLeast(minHeight)
        }

        private fun updatePlaceholderHeight(height: Int) {
            val params = binding.readingPagePlaceholder.layoutParams
            if (params.height == height) return
            params.height = height
            binding.readingPagePlaceholder.layoutParams = params
        }

        private fun bindOverlay(bitmap: Bitmap, translation: TranslationResult?) {
            val width = binding.readingPageImage.width.toFloat()
            val height = binding.readingPageImage.height.toFloat()
            if (width <= 0f || height <= 0f) {
                binding.readingPageOverlay.visibility = View.GONE
                return
            }
            val resolved = resolveOverlayTranslation(translation)
            val lockedForEdit = isLockedEditPage()
            binding.readingPageOverlay.setDisplayRect(RectF(0f, 0f, width, height))
            binding.readingPageOverlay.setTranslations(resolved)
            binding.readingPageOverlay.setOffsets(if (lockedForEdit) lockedPageOffsets else emptyMap())
            binding.readingPageOverlay.setTouchPassthroughEnabled(!lockedForEdit)
            binding.readingPageOverlay.setEditScrollThroughEnabled(lockedForEdit)
            binding.readingPageOverlay.onOffsetChanged = if (lockedForEdit) { bubbleId, offsetX, offsetY ->
                onLockedBubbleOffsetChanged?.invoke(bubbleId, offsetX, offsetY)
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleRemove = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleRemove?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleTap = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleTap?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleResizeTap = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleResizeTap?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.onBubbleLongPress = if (lockedForEdit) {
                { bubbleId -> onLockedBubbleLongPress?.invoke(bubbleId) }
            } else {
                null
            }
            binding.readingPageOverlay.setEditMode(lockedForEdit)
            binding.readingPageOverlay.visibility = if (resolved.bubbles.isEmpty()) View.GONE else View.VISIBLE
        }

        fun reloadTranslationOverlay() {
            val imageFile = boundFile ?: return
            val bitmap = currentBitmap ?: return
            overlayReloadJob?.cancel()
            overlayReloadJob = scope.launch {
                val translation = withContext(Dispatchers.IO) {
                    translationStore.load(imageFile)
                }
                if (boundPath != imageFile.absolutePath) return@launch
                currentTranslation = normalizeTranslation(translation)
                bindOverlay(bitmap, currentTranslation)
            }
        }

        private fun resolveOverlayTranslation(base: TranslationResult?): TranslationResult {
            val preferred = if (isLockedEditPage()) lockedPageTranslation ?: base else base
            return normalizeTranslation(preferred)
                ?: TranslationResult("", currentImageWidth, currentImageHeight, emptyList())
        }

        private fun normalizeTranslation(translation: TranslationResult?): TranslationResult? {
            if (translation == null) return null
            return if (translation.width == currentImageWidth && translation.height == currentImageHeight) {
                translation
            } else {
                translation.copy(width = currentImageWidth, height = currentImageHeight)
            }
        }

        private fun isLockedEditPage(): Boolean {
            return editModeEnabled &&
                boundPath != null &&
                boundPath == lockedPagePath
        }

        private fun resolveTargetWidth(): Int {
            return binding.readingPageImage.width
                .takeIf { it > 0 }
                ?: binding.root.width.takeIf { it > 0 }
                ?: binding.root.resources.displayMetrics.widthPixels
        }

        private fun resolveTargetHeight(): Int {
            return binding.readingPageImage.height
                .takeIf { it > 0 }
                ?: binding.root.height.takeIf { it > 0 }
                ?: binding.root.resources.displayMetrics.heightPixels
        }
    }
}
