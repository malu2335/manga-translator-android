package com.manga.translate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.withTranslation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingDetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9FFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F1F1F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.2f
    }
    private val editBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00ACC1.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.8f
    }
    private val deletePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2600ACC1
        style = Paint.Style.FILL
    }
    private val previewStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00ACC1.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
        textSize = resources.displayMetrics.density * resources.configuration.fontScale * 12f
    }
    private val sharedMinTextSizePx: Float
        get() = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            SettingsStore(context).loadNormalBubbleRenderSettings().minFontSizeSp.toFloat(),
            resources.displayMetrics
        )
    private val textSizeStepPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        0.5f,
        resources.displayMetrics
    ).coerceAtLeast(0.5f)
    private val sourceRect = RectF()
    private val tempRect = RectF()
    private val shapeRect = RectF()
    private val deleteRect = RectF()
    private val drawingRect = RectF()
    private val bubblePath = Path()
    private val textLayoutCache = mutableMapOf<TextLayoutCacheKey, StaticLayout>()
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var bubbles: List<BubbleTranslation> = emptyList()
    private var bubbleRenderSettings = SettingsStore(context).loadFloatingBubbleRenderSettings()
    private var bubbleOpacity = bubbleRenderSettings.opacityPercent / 100f
    private var editMode = false
    private var createBubbleMode = false
    private val touchSlop = 3f * resources.displayMetrics.density
    private val minCreateSize = 24f * resources.displayMetrics.density
    private var draggingBubbleId: Int? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var isDrawing = false
    private var dirty = false
    private var longPressBubbleId: Int? = null
    private var longPressTriggered = false
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressDeleteRunnable = Runnable {
        val id = longPressBubbleId ?: return@Runnable
        longPressTriggered = true
        draggingBubbleId = null
        isDragging = false
        onBubbleDelete?.invoke(id)
        setDirty(true)
    }

    var onBubblesChanged: ((List<BubbleTranslation>) -> Unit)? = null
    var onBubbleDelete: ((Int) -> Unit)? = null
    var onManualBubbleCreated: ((RectF) -> Unit)? = null
    var onEditDirtyChanged: ((Boolean) -> Unit)? = null
    var onCreateBubbleTouchActiveChanged: ((Boolean) -> Unit)? = null

    init {
        applyBubbleOpacity()
    }

    fun setTranslationSession(sourceWidth: Int, sourceHeight: Int, bubbles: List<BubbleTranslation>) {
        this.sourceWidth = sourceWidth.coerceAtLeast(1)
        this.sourceHeight = sourceHeight.coerceAtLeast(1)
        this.bubbles = bubbles
        textLayoutCache.clear()
        draggingBubbleId = null
        isDragging = false
        cancelLongPressDelete()
        invalidate()
    }

    fun clearDetections() {
        bubbles = emptyList()
        textLayoutCache.clear()
        draggingBubbleId = null
        isDragging = false
        isDrawing = false
        drawingRect.setEmpty()
        cancelLongPressDelete()
        setDirty(false)
        invalidate()
    }

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        if (!enabled) {
            setCreateBubbleMode(false)
            draggingBubbleId = null
            isDragging = false
            isDrawing = false
            drawingRect.setEmpty()
            cancelLongPressDelete()
            setDirty(false)
        }
        invalidate()
    }

    fun setCreateBubbleMode(enabled: Boolean) {
        if (createBubbleMode == enabled) return
        createBubbleMode = enabled && editMode
        draggingBubbleId = null
        isDragging = false
        isDrawing = false
        drawingRect.setEmpty()
        cancelLongPressDelete()
        onCreateBubbleTouchActiveChanged?.invoke(false)
        invalidate()
    }

    fun setFloatingBubbleRenderSettings(settings: FloatingBubbleRenderSettings) {
        if (bubbleRenderSettings == settings) return
        bubbleRenderSettings = settings
        bubbleOpacity = settings.opacityPercent / 100f
        applyBubbleOpacity()
        textLayoutCache.clear()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = when {
            editMode -> handleEditTouch(event)
            else -> false
        }
        if (handled && event.actionMasked == MotionEvent.ACTION_UP && !isDragging) {
            performClick()
        }
        return handled
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun handleEditTouch(event: MotionEvent): Boolean {
        val sourceX = event.x / scaleX()
        val sourceY = event.y / scaleY()
        if (createBubbleMode) {
            return handleCreateTouch(event, sourceX, sourceY)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isDragging = false
                val bubble = findBubbleAt(sourceX, sourceY)
                if (bubble != null) {
                    draggingBubbleId = bubble.id
                    dragOffsetX = sourceX - bubble.rect.left
                    dragOffsetY = sourceY - bubble.rect.top
                    scheduleLongPressDelete(bubble.id)
                } else {
                    draggingBubbleId = null
                    cancelLongPressDelete()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val id = draggingBubbleId ?: return true
                if (!isDragging) {
                    isDragging = abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
                    if (isDragging) {
                        cancelLongPressDelete()
                    }
                }
                if (isDragging) {
                    updateBubblePosition(id, sourceX, sourceY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val id = draggingBubbleId
                val didLongPressDelete = longPressTriggered
                cancelLongPressDelete()
                if (didLongPressDelete) {
                    return true
                }
                if (!isDragging && id != null) {
                    val bubble = bubbles.firstOrNull { it.id == id }
                    if (bubble != null) {
                        computeDeleteRect(bubble.rect, deleteRect)
                        if (deleteRect.contains(sourceX, sourceY)) {
                            onBubbleDelete?.invoke(id)
                            setDirty(true)
                        }
                    }
                }
                draggingBubbleId = null
                isDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressDelete()
                draggingBubbleId = null
                isDragging = false
                return true
            }
        }
        return true
    }

    private fun handleCreateTouch(event: MotionEvent, sourceX: Float, sourceY: Float): Boolean {
        val clampedX = sourceX.coerceIn(0f, sourceWidth.toFloat())
        val clampedY = sourceY.coerceIn(0f, sourceHeight.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                downX = clampedX
                downY = clampedY
                drawingRect.set(clampedX, clampedY, clampedX, clampedY)
                onCreateBubbleTouchActiveChanged?.invoke(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDrawing) return true
                updateDrawingRect(clampedX, clampedY)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDrawing) return true
                updateDrawingRect(clampedX, clampedY)
                val created = RectF(drawingRect)
                isDrawing = false
                drawingRect.setEmpty()
                onCreateBubbleTouchActiveChanged?.invoke(false)
                invalidate()
                if (created.width() * scaleX() >= minCreateSize &&
                    created.height() * scaleY() >= minCreateSize
                ) {
                    onManualBubbleCreated?.invoke(created)
                    setDirty(true)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDrawing = false
                drawingRect.setEmpty()
                onCreateBubbleTouchActiveChanged?.invoke(false)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun updateDrawingRect(sourceX: Float, sourceY: Float) {
        drawingRect.set(
            min(downX, sourceX),
            min(downY, sourceY),
            max(downX, sourceX),
            max(downY, sourceY)
        )
    }

    private fun findBubbleAt(x: Float, y: Float): BubbleTranslation? {
        for (i in bubbles.indices.reversed()) {
            val bubble = bubbles[i]
            if (bubble.rect.contains(x, y)) {
                return bubble
            }
        }
        return null
    }

    private fun updateBubblePosition(id: Int, sourceX: Float, sourceY: Float) {
        val mutable = bubbles.toMutableList()
        val index = mutable.indexOfFirst { it.id == id }
        if (index < 0) return
        val bubble = mutable[index]
        val width = bubble.rect.width().coerceAtLeast(1f)
        val height = bubble.rect.height().coerceAtLeast(1f)
        val maxLeft = max(0f, sourceWidth.toFloat() - width)
        val maxTop = max(0f, sourceHeight.toFloat() - height)
        val newLeft = min(max(sourceX - dragOffsetX, 0f), maxLeft)
        val newTop = min(max(sourceY - dragOffsetY, 0f), maxTop)
        val deltaX = newLeft - bubble.rect.left
        val deltaY = newTop - bubble.rect.top
        val newRect = RectF(newLeft, newTop, newLeft + width, newTop + height)
        mutable[index] = bubble.copy(
            rect = newRect,
            maskContour = BubbleShapePaths.translateMaskContour(
                contour = bubble.maskContour,
                deltaX = deltaX,
                deltaY = deltaY,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            )
        )
        bubbles = mutable
        onBubblesChanged?.invoke(mutable)
        setDirty(true)
        invalidate()
    }

    private fun scheduleLongPressDelete(id: Int) {
        cancelLongPressDelete()
        longPressBubbleId = id
        longPressTriggered = false
        postDelayed(longPressDeleteRunnable, longPressTimeoutMs)
    }

    private fun cancelLongPressDelete() {
        removeCallbacks(longPressDeleteRunnable)
        longPressBubbleId = null
        longPressTriggered = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bubbles.isNotEmpty()) {
            drawBubbles(canvas)
        }
        if (editMode && createBubbleMode && !drawingRect.isEmpty) {
            updateDisplayRect(drawingRect, sourceRect)
            if (bubbleRenderSettings.shape == FloatingBubbleShape.INSCRIBED_ELLIPSE) {
                canvas.drawOval(sourceRect, previewPaint)
                canvas.drawOval(sourceRect, previewStrokePaint)
            } else {
                canvas.drawRoundRect(sourceRect, cornerRadius(), cornerRadius(), previewPaint)
                canvas.drawRoundRect(sourceRect, cornerRadius(), cornerRadius(), previewStrokePaint)
            }
        }
    }

    private fun drawBubbles(canvas: Canvas) {
        val radius = cornerRadius()
        for (bubble in bubbles) {
            val rect = bubble.rect
            updateDisplayRect(rect, sourceRect)
            if (sourceRect.width() < 2f || sourceRect.height() < 2f) continue
            if (bubbleRenderSettings.shape == FloatingBubbleShape.INSCRIBED_ELLIPSE) {
                canvas.drawOval(sourceRect, boxPaint)
                canvas.drawOval(sourceRect, borderPaint)
            } else {
                canvas.drawRoundRect(sourceRect, radius, radius, boxPaint)
                canvas.drawRoundRect(sourceRect, radius, radius, borderPaint)
            }
            if (editMode) {
                if (bubbleRenderSettings.shape == FloatingBubbleShape.INSCRIBED_ELLIPSE) {
                    canvas.drawOval(sourceRect, editBorderPaint)
                } else {
                    canvas.drawRoundRect(sourceRect, radius, radius, editBorderPaint)
                }
                computeDeleteRect(rect, tempRect)
                updateDisplayRect(tempRect, tempRect)
                drawDeleteIcon(canvas, tempRect)
            }
            val text = bubble.text.ifBlank { context.getString(R.string.floating_bubble_placeholder) }
            if (bubbleRenderSettings.shape == FloatingBubbleShape.INSCRIBED_ELLIPSE) {
                bubblePath.reset()
                bubblePath.addOval(sourceRect, Path.Direction.CW)
            } else {
                bubblePath.reset()
                bubblePath.addRoundRect(sourceRect, radius, radius, Path.Direction.CW)
            }
            BubbleShapePaths.insetTextBounds(bubblePath, shapeRect)
            if (bubbleRenderSettings.expandBubbleWhenMinFontSize) {
                ensureExpandedTextBounds(bubblePath, text, bubbleRenderSettings.useHorizontalText)
            }
            val availableWidth = shapeRect.width().toInt().coerceAtLeast(1)
            val availableHeight = shapeRect.height().toInt().coerceAtLeast(1)
            if (bubbleRenderSettings.useHorizontalText) {
                val plan = BubbleTextScaling.buildHorizontalScalePlan(
                    text = text,
                    rect = shapeRect,
                    minTextSizePx = sharedMinTextSizePx,
                    expandBubbleWhenMinFontSize =
                        bubbleRenderSettings.expandBubbleWhenMinFontSize,
                    buildLayout = { content, width, textSize ->
                        buildLayout(
                            text = content,
                            paint = TextPaint(textPaint).apply { this.textSize = textSize },
                            availableWidth = width
                        )
                    },
                    layoutFits = ::layoutFits
                )
                val drawRect = if (bubbleRenderSettings.expandBubbleWhenMinFontSize) shapeRect else plan.drawRect
                val textLayout = if (bubbleRenderSettings.expandBubbleWhenMinFontSize) {
                    buildLayout(
                        text = text,
                        paint = TextPaint(textPaint).apply { textSize = resolveHorizontalTextSize(shapeRect, text) },
                        availableWidth = shapeRect.width().toInt().coerceAtLeast(1)
                    )
                } else {
                    plan.defaultLayout
                }
                canvas.save()
                canvas.translate(drawRect.centerX(), drawRect.centerY())
                canvas.scale(plan.scaleX, plan.scaleY)
                canvas.translate(-textLayout.width / 2f, -textLayout.height / 2f)
                textLayout.draw(canvas)
                canvas.restore()
            } else {
                drawVerticalTextInRect(canvas, VerticalTextSymbolConverter.convert(text), shapeRect)
            }
        }
    }

    private fun drawDeleteIcon(canvas: Canvas, rect: RectF) {
        canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, deletePaint)
        canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, deletePaint)
    }

    private fun computeDeleteRect(source: RectF, outRect: RectF) {
        val size = (min(source.width(), source.height()) * 0.22f).coerceIn(18f, 42f)
        val padding = (size * 0.2f).coerceAtLeast(4f)
        val left = (source.right - size - padding).coerceAtLeast(source.left)
        val top = (source.top + padding).coerceAtLeast(source.top)
        val right = (left + size).coerceAtMost(source.right)
        val bottom = (top + size).coerceAtMost(source.bottom)
        outRect.set(left, top, right, bottom)
    }

    private fun buildFittedTextLayout(
        text: String,
        availableWidth: Int,
        availableHeight: Int
    ): StaticLayout {
        val safeWidth = availableWidth.coerceAtLeast(1)
        val safeHeight = availableHeight.coerceAtLeast(1)
        val cacheKey = TextLayoutCacheKey(
            text = text,
            availableWidth = safeWidth,
            availableHeight = safeHeight,
            minFontSizePx = sharedMinTextSizePx.toBits()
        )
        textLayoutCache[cacheKey]?.let { return it }
        val probePaint = TextPaint(textPaint)
        val minTextSizePx = sharedMinTextSizePx
        val defaultTextSize = findDefaultHorizontalTextSize(text, safeWidth, safeHeight)
        val resolvedTextSize = defaultTextSize.coerceAtLeast(minTextSizePx)
        val layout = buildLayout(text, probePaint.apply { textSize = resolvedTextSize }, safeWidth)
        textLayoutCache[cacheKey] = layout
        return layout
    }

    private fun buildLayout(text: String, paint: TextPaint, availableWidth: Int): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    private fun ensureExpandedTextBounds(path: Path, text: String, horizontalText: Boolean) {
        repeat(8) {
            BubbleShapePaths.insetTextBounds(path, shapeRect)
            if (shapeRect.width() <= 0f || shapeRect.height() <= 0f) return
            val required = if (horizontalText) {
                BubbleTextScaling.buildHorizontalScalePlan(
                    text = text,
                    rect = shapeRect,
                    minTextSizePx = sharedMinTextSizePx,
                    expandBubbleWhenMinFontSize = true,
                    buildLayout = { content, width, textSize ->
                        buildLayout(
                            text = content,
                            paint = TextPaint(textPaint).apply { this.textSize = textSize },
                            availableWidth = width
                        )
                    },
                    layoutFits = ::layoutFits
                ).drawRect
            } else {
                resolveRequiredVerticalRect(text, shapeRect)
            }
            if (shapeRect.width() + 0.5f >= required.width() &&
                shapeRect.height() + 0.5f >= required.height()
            ) {
                return
            }
            val bounds = RectF()
            path.computeBounds(bounds, true)
            if (bounds.width() <= 0f || bounds.height() <= 0f) return
            val matrix = android.graphics.Matrix()
            matrix.setScale(
                (required.width() / shapeRect.width()).coerceAtLeast(1f),
                (required.height() / shapeRect.height()).coerceAtLeast(1f),
                bounds.centerX(),
                bounds.centerY()
            )
            path.transform(matrix)
        }
        BubbleShapePaths.insetTextBounds(path, shapeRect)
    }

    private fun layoutFits(layout: StaticLayout, availableWidth: Int, availableHeight: Int): Boolean {
        if (layout.height > availableHeight) return false
        for (line in 0 until layout.lineCount) {
            if (layout.getLineWidth(line) > availableWidth + 0.5f) {
                return false
            }
        }
        return true
    }

    private fun applyBubbleOpacity() {
        boxPaint.color = Color.argb((bubbleOpacity * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
    }

    private fun updateDisplayRect(source: RectF, outRect: RectF) {
        val adjustScale = (100f + bubbleRenderSettings.sizeAdjustPercent.coerceIn(-90, 90)) / 100f
        val centerX = source.centerX()
        val centerY = source.centerY()
        val adjustedHalfWidth = (source.width() * adjustScale / 2f).coerceAtLeast(1f)
        val adjustedHalfHeight = (source.height() * adjustScale / 2f).coerceAtLeast(1f)
        outRect.set(
            (centerX - adjustedHalfWidth) * scaleX(),
            (centerY - adjustedHalfHeight) * scaleY(),
            (centerX + adjustedHalfWidth) * scaleX(),
            (centerY + adjustedHalfHeight) * scaleY()
        )
    }

    private fun drawVerticalTextInRect(canvas: Canvas, text: String, rect: RectF) {
        val maxWidth = rect.width().toInt().coerceAtLeast(1)
        val maxHeight = rect.height().toInt().coerceAtLeast(1)
        val minTextSizePx = sharedMinTextSizePx
        val defaultTextSize = findDefaultVerticalTextSize(text, maxWidth, maxHeight, rect.width() / 2.2f)
        val textSize = defaultTextSize.coerceAtLeast(minTextSizePx)
        val layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
        textPaint.textSize = textSize
        val dx = rect.right - ((rect.width() - layout.totalWidth) / 2f) - layout.columnWidth
        val dy = rect.top + ((rect.height() - layout.totalHeight) / 2f) - layout.fontMetrics.ascent
        var col = 0
        var row = 0
        for (ch in text) {
            if (ch == '\n') {
                col += 1
                row = 0
                continue
            }
            if (row >= layout.maxRows) {
                col += 1
                row = 0
            }
            if (col >= layout.columns) break
            val glyph = ch.toString()
            val charWidth = textPaint.measureText(glyph)
            val x = dx - col * layout.columnWidth + (layout.columnWidth - charWidth) / 2f
            val y = dy + row * layout.lineHeight
            canvas.drawText(glyph, x, y, textPaint)
            row += 1
        }
    }

    private fun findDefaultHorizontalTextSize(
        text: String,
        availableWidth: Int,
        availableHeight: Int
    ): Float {
        val minTextSizePx = sharedMinTextSizePx
        val probePaint = TextPaint(textPaint)
        val minLayout = buildLayout(text, probePaint.apply { textSize = minTextSizePx }, availableWidth)
        if (!layoutFits(minLayout, availableWidth, availableHeight)) {
            return minTextSizePx
        }
        var bestSize = minTextSizePx
        var low = minTextSizePx
        var high = maxOf(low, max(availableWidth, availableHeight).toFloat())
        while (high - low > textSizeStepPx) {
            val mid = (low + high) / 2f
            val candidate = buildLayout(text, probePaint.apply { textSize = mid }, availableWidth)
            if (layoutFits(candidate, availableWidth, availableHeight)) {
                bestSize = mid
                low = mid
            } else {
                high = mid
            }
        }
        return bestSize
    }

    private fun findDefaultVerticalTextSize(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        initialSize: Float
    ): Float {
        val minTextSizePx = sharedMinTextSizePx
        val maxTextSize = 42f * resources.displayMetrics.density / resources.configuration.fontScale
        var textSize = initialSize.coerceIn(minTextSizePx, maxTextSize)
        var layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
        while ((layout.columnWidth <= 0f || layout.lineHeight <= 0f || !layout.fits) && textSize > minTextSizePx) {
            textSize = (textSize - textSizeStepPx).coerceAtLeast(minTextSizePx)
            layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
            if (textSize <= minTextSizePx) break
        }
        return textSize
    }

    private fun resolveRequiredVerticalRect(text: String, rect: RectF): RectF {
        val maxWidth = rect.width().toInt().coerceAtLeast(1)
        val maxHeight = rect.height().toInt().coerceAtLeast(1)
        val defaultTextSize = findDefaultVerticalTextSize(text, maxWidth, maxHeight, rect.width() / 2.2f)
        if (defaultTextSize >= sharedMinTextSizePx) return RectF(rect)
        val layout = buildVerticalLayout(text, maxWidth, maxHeight, sharedMinTextSizePx)
        val targetWidth = maxOf(rect.width(), layout.totalWidth)
        val targetHeight = maxOf(rect.height(), layout.totalHeight)
        return RectF(
            rect.centerX() - targetWidth / 2f,
            rect.centerY() - targetHeight / 2f,
            rect.centerX() + targetWidth / 2f,
            rect.centerY() + targetHeight / 2f
        )
    }

    private fun resolveHorizontalTextSize(rect: RectF, text: String): Float {
        return findDefaultHorizontalTextSize(
            text = text,
            availableWidth = rect.width().toInt().coerceAtLeast(1),
            availableHeight = rect.height().toInt().coerceAtLeast(1)
        ).coerceAtLeast(sharedMinTextSizePx)
    }

    private fun buildVerticalLayout(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        textSize: Float
    ): VerticalLayout {
        textPaint.textSize = textSize
        val fontMetrics = textPaint.fontMetrics
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1f)
        val maxRows = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
        val charCount = text.count { it != '\n' }.coerceAtLeast(1)
        var maxCharWidth = 0f
        for (ch in text) {
            if (ch == '\n') continue
            val width = textPaint.measureText(ch.toString())
            if (width > maxCharWidth) {
                maxCharWidth = width
            }
        }
        if (maxCharWidth <= 0f) {
            maxCharWidth = textPaint.measureText("国")
        }
        maxCharWidth = maxCharWidth.coerceAtLeast(1f)
        val columns = ((charCount + maxRows - 1) / maxRows).coerceAtLeast(1)
        val totalWidth = columns * maxCharWidth
        val totalHeight = maxRows * lineHeight
        val fits = totalWidth <= maxWidth && totalHeight <= maxHeight
        return VerticalLayout(
            columnWidth = maxCharWidth,
            lineHeight = lineHeight,
            maxRows = maxRows,
            columns = columns,
            totalWidth = totalWidth,
            totalHeight = totalHeight,
            fontMetrics = fontMetrics,
            fits = fits
        )
    }

    private fun setDirty(value: Boolean) {
        if (dirty == value) return
        dirty = value
        onEditDirtyChanged?.invoke(value)
    }

    private fun scaleX(): Float = width.toFloat().coerceAtLeast(1f) / sourceWidth.coerceAtLeast(1)

    private fun scaleY(): Float = height.toFloat().coerceAtLeast(1f) / sourceHeight.coerceAtLeast(1)

    private fun cornerRadius(): Float = resources.displayMetrics.density * 8f

    private data class TextLayoutCacheKey(
        val text: String,
        val availableWidth: Int,
        val availableHeight: Int,
        val minFontSizePx: Int
    )

    private data class VerticalLayout(
        val columnWidth: Float,
        val lineHeight: Float,
        val maxRows: Int,
        val columns: Int,
        val totalWidth: Float,
        val totalHeight: Float,
        val fontMetrics: Paint.FontMetrics,
        val fits: Boolean
    )
}
