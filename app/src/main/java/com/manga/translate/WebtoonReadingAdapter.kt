package com.manga.translate

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.databinding.ItemReadingWebtoonPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WebtoonReadingAdapter(
    private val scope: CoroutineScope,
    private val translationStore: TranslationStore
) : RecyclerView.Adapter<WebtoonReadingAdapter.WebtoonPageViewHolder>() {
    private data class PresentationConfig(
        val embeddedMode: Boolean,
        val verticalLayoutEnabled: Boolean,
        val bubbleOpacity: Float
    )

    private companion object {
        const val PAYLOAD_PRESENTATION_ONLY = "presentation_only"
        const val DEFAULT_PLACEHOLDER_HEIGHT_RATIO = 1.4f
    }

    private var items: List<File> = emptyList()
    private var isEmbeddedMode: Boolean = false
    private var verticalLayoutEnabled: Boolean = true
    private var bubbleOpacity: Float = 1f
    private val rememberedPageHeights = mutableMapOf<String, Int>()

    fun submit(
        images: List<File>,
        embeddedMode: Boolean,
        verticalLayoutEnabled: Boolean,
        bubbleOpacity: Float
    ) {
        val previousItems = items
        val previousConfig = PresentationConfig(
            embeddedMode = isEmbeddedMode,
            verticalLayoutEnabled = this.verticalLayoutEnabled,
            bubbleOpacity = this.bubbleOpacity
        )
        val newConfig = PresentationConfig(
            embeddedMode = embeddedMode,
            verticalLayoutEnabled = verticalLayoutEnabled,
            bubbleOpacity = bubbleOpacity
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
                        previousConfig.embeddedMode == newConfig.embeddedMode &&
                        (previousConfig.verticalLayoutEnabled != newConfig.verticalLayoutEnabled ||
                            previousConfig.bubbleOpacity != newConfig.bubbleOpacity)
                    ) {
                        PAYLOAD_PRESENTATION_ONLY
                    } else {
                        null
                    }
                }
            }
        )
        items = images
        isEmbeddedMode = embeddedMode
        this.verticalLayoutEnabled = verticalLayoutEnabled
        this.bubbleOpacity = bubbleOpacity
        diffResult.dispatchUpdatesTo(this)
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
            embeddedMode = isEmbeddedMode,
            verticalLayoutEnabled = verticalLayoutEnabled,
            bubbleOpacity = bubbleOpacity
        )
    }

    override fun onBindViewHolder(
        holder: WebtoonPageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_PRESENTATION_ONLY)) {
            holder.updatePresentation(
                verticalLayoutEnabled = verticalLayoutEnabled,
                bubbleOpacity = bubbleOpacity
            )
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: WebtoonPageViewHolder) {
        holder.recycle()
    }

    override fun onViewDetachedFromWindow(holder: WebtoonPageViewHolder) {
        holder.stopWatching()
    }

    override fun onViewAttachedToWindow(holder: WebtoonPageViewHolder) {
        holder.resumeWatching()
    }

    inner class WebtoonPageViewHolder(
        private val binding: ItemReadingWebtoonPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private var bindJob: Job? = null
        private var watchJob: Job? = null
        private var boundPath: String? = null
        private var boundFile: File? = null
        private var boundEmbeddedMode: Boolean = false
        private var currentBitmap: Bitmap? = null
        private var currentImageWidth: Int = 0
        private var currentImageHeight: Int = 0
        private var lastTranslationModified: Long = Long.MIN_VALUE

        fun bind(
            imageFile: File,
            embeddedMode: Boolean,
            verticalLayoutEnabled: Boolean,
            bubbleOpacity: Float
        ) {
            boundPath = imageFile.absolutePath
            boundFile = imageFile
            boundEmbeddedMode = embeddedMode
            currentBitmap = null
            currentImageWidth = 0
            currentImageHeight = 0
            lastTranslationModified = Long.MIN_VALUE
            bindJob?.cancel()
            watchJob?.cancel()
            binding.readingPageOverlay.setEditMode(false)
            binding.readingPageOverlay.setTouchPassthroughEnabled(true)
            binding.readingPageOverlay.setVerticalLayoutEnabled(verticalLayoutEnabled)
            binding.readingPageOverlay.setBubbleOpacity(bubbleOpacity)
            binding.readingPageOverlay.visibility = View.GONE
            applyPlaceholder(imageFile)
            binding.readingPageImage.setImageDrawable(null)
            binding.root.doOnLayout {
                if (boundPath != imageFile.absolutePath) return@doOnLayout
                loadPage(imageFile, embeddedMode)
            }
        }

        fun updatePresentation(verticalLayoutEnabled: Boolean, bubbleOpacity: Float) {
            binding.readingPageOverlay.setVerticalLayoutEnabled(verticalLayoutEnabled)
            binding.readingPageOverlay.setBubbleOpacity(bubbleOpacity)
        }

        fun resumeWatching() {
            val imageFile = boundFile ?: return
            if (boundEmbeddedMode) return
            if (watchJob?.isActive == true) return
            startWatchingTranslations(imageFile)
        }

        fun stopWatching() {
            watchJob?.cancel()
            watchJob = null
        }

        private fun loadPage(imageFile: File, embeddedMode: Boolean) {
            bindJob?.cancel()
            bindJob = scope.launch {
                val targetWidth = resolveTargetWidth()
                val targetHeight = resolveTargetHeight()
                val decoded = withContext(Dispatchers.IO) {
                    ReadingBitmapDecoder.decode(imageFile, targetWidth, targetHeight)
                }
                val bitmap = decoded?.bitmap
                val translation = if (embeddedMode) {
                    null
                } else {
                    withContext(Dispatchers.IO) { translationStore.load(imageFile) }
                }
                if (boundPath != imageFile.absolutePath) return@launch
                if (bitmap == null) {
                    binding.readingPageImage.setImageDrawable(null)
                    binding.readingPageOverlay.visibility = View.GONE
                    showPlaceholder(imageFile.absolutePath)
                    return@launch
                }
                currentBitmap = bitmap
                currentImageWidth = decoded.sourceWidth
                currentImageHeight = decoded.sourceHeight
                binding.readingPageImage.setImageBitmap(bitmap)
                binding.readingPageImage.doOnLayout {
                    if (boundPath != imageFile.absolutePath) return@doOnLayout
                    rememberedPageHeights[imageFile.absolutePath] = binding.readingPageImage.height
                    binding.readingPagePlaceholder.visibility = View.GONE
                    bindOverlay(bitmap, translation)
                    if (!embeddedMode) {
                        startWatchingTranslations(imageFile)
                    }
                }
            }
        }

        fun recycle() {
            bindJob?.cancel()
            watchJob?.cancel()
            boundPath = null
            boundFile = null
            currentBitmap = null
            currentImageWidth = 0
            currentImageHeight = 0
            binding.readingPageImage.setImageDrawable(null)
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
            val resolved = when {
                translation == null -> TranslationResult("", currentImageWidth, currentImageHeight, emptyList())
                translation.width == currentImageWidth && translation.height == currentImageHeight -> translation
                else -> translation.copy(width = currentImageWidth, height = currentImageHeight)
            }
            binding.readingPageOverlay.setDisplayRect(RectF(0f, 0f, width, height))
            binding.readingPageOverlay.setOffsets(emptyMap())
            binding.readingPageOverlay.setTranslations(resolved)
            binding.readingPageOverlay.visibility = if (resolved.bubbles.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun startWatchingTranslations(imageFile: File) {
            watchJob?.cancel()
            watchJob = scope.launch {
                val translationFile = translationStore.translationFileFor(imageFile)
                while (isActive && boundPath == imageFile.absolutePath) {
                    val modified = if (translationFile.exists()) translationFile.lastModified() else Long.MIN_VALUE
                    if (modified != lastTranslationModified) {
                        lastTranslationModified = modified
                        val bitmap = currentBitmap
                        if (bitmap != null) {
                            val translation = withContext(Dispatchers.IO) {
                                if (translationFile.exists()) translationStore.load(imageFile) else null
                            }
                            if (boundPath != imageFile.absolutePath) return@launch
                            bindOverlay(bitmap, translation)
                        }
                    }
                    delay(800)
                }
            }
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
