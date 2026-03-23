package com.manga.translate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
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
    private val minTextSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        8f,
        resources.displayMetrics
    )
    private val textSizeStepPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        0.5f,
        resources.displayMetrics
    ).coerceAtLeast(0.5f)
    private val sourceRect = RectF()
    private val tempRect = RectF()
    private val deleteRect = RectF()
    private val drawingRect = RectF()
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var bubbles: List<BubbleTranslation> = emptyList()
    private var bubbleOpacity = SettingsStore(context).loadTranslationBubbleOpacity()
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

    var onBubblesChanged: ((List<BubbleTranslation>) -> Unit)? = null
    var onBubbleDelete: ((Int) -> Unit)? = null
    var onManualBubbleCreated: ((RectF) -> Unit)? = null
    var onEditDirtyChanged: ((Boolean) -> Unit)? = null

    init {
        applyBubbleOpacity()
    }

    fun setTranslationSession(sourceWidth: Int, sourceHeight: Int, bubbles: List<BubbleTranslation>) {
        this.sourceWidth = sourceWidth.coerceAtLeast(1)
        this.sourceHeight = sourceHeight.coerceAtLeast(1)
        this.bubbles = bubbles
        draggingBubbleId = null
        isDragging = false
        invalidate()
    }

    fun clearDetections() {
        bubbles = emptyList()
        draggingBubbleId = null
        isDragging = false
        isDrawing = false
        drawingRect.setEmpty()
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
        invalidate()
    }

    fun setBubbleOpacity(opacity: Float) {
        val normalized = opacity.coerceIn(0f, 1f)
        if (bubbleOpacity == normalized) return
        bubbleOpacity = normalized
        applyBubbleOpacity()
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
                } else {
                    draggingBubbleId = null
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val id = draggingBubbleId ?: return true
                if (!isDragging) {
                    isDragging = abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
                }
                if (isDragging) {
                    updateBubblePosition(id, sourceX, sourceY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val id = draggingBubbleId
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
        val newRect = RectF(newLeft, newTop, newLeft + width, newTop + height)
        mutable[index] = bubble.copy(rect = newRect)
        bubbles = mutable
        onBubblesChanged?.invoke(mutable)
        setDirty(true)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bubbles.isNotEmpty()) {
            drawBubbles(canvas)
        }
        if (editMode && createBubbleMode && !drawingRect.isEmpty) {
            sourceRect.set(
                drawingRect.left * scaleX(),
                drawingRect.top * scaleY(),
                drawingRect.right * scaleX(),
                drawingRect.bottom * scaleY()
            )
            canvas.drawRoundRect(sourceRect, cornerRadius(), cornerRadius(), previewPaint)
            canvas.drawRoundRect(sourceRect, cornerRadius(), cornerRadius(), previewStrokePaint)
        }
    }

    private fun drawBubbles(canvas: Canvas) {
        val radius = cornerRadius()
        val horizontalPadding = resources.displayMetrics.density * 6f
        val verticalPadding = resources.displayMetrics.density * 5f
        for (bubble in bubbles) {
            val rect = bubble.rect
            sourceRect.set(
                rect.left * scaleX(),
                rect.top * scaleY(),
                rect.right * scaleX(),
                rect.bottom * scaleY()
            )
            if (sourceRect.width() < 2f || sourceRect.height() < 2f) continue
            canvas.drawRoundRect(sourceRect, radius, radius, boxPaint)
            canvas.drawRoundRect(sourceRect, radius, radius, borderPaint)
            if (editMode) {
                canvas.drawRoundRect(sourceRect, radius, radius, editBorderPaint)
                computeDeleteRect(rect, tempRect)
                tempRect.set(
                    tempRect.left * scaleX(),
                    tempRect.top * scaleY(),
                    tempRect.right * scaleX(),
                    tempRect.bottom * scaleY()
                )
                drawDeleteIcon(canvas, tempRect)
            }
            val text = bubble.text.ifBlank { context.getString(R.string.floating_bubble_placeholder) }
            val availableWidth = (sourceRect.width() - horizontalPadding * 2f).toInt().coerceAtLeast(1)
            val availableHeight = (sourceRect.height() - verticalPadding * 2f).toInt().coerceAtLeast(1)
            val textLayout = buildFittedTextLayout(text, availableWidth, availableHeight)
            val drawY = sourceRect.top + verticalPadding +
                ((availableHeight - textLayout.height) / 2f).coerceAtLeast(0f)
            canvas.withTranslation(sourceRect.left + horizontalPadding, drawY) {
                textLayout.draw(this)
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
        val probePaint = TextPaint(textPaint)
        var size = textPaint.textSize
        var fitted = buildLayout(text, probePaint, availableWidth)
        while (fitted.height > availableHeight && size > minTextSizePx) {
            size = (size - textSizeStepPx).coerceAtLeast(minTextSizePx)
            probePaint.textSize = size
            fitted = buildLayout(text, probePaint, availableWidth)
            if (size <= minTextSizePx) break
        }
        return fitted
    }

    private fun buildLayout(text: String, paint: TextPaint, availableWidth: Int): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    private fun applyBubbleOpacity() {
        boxPaint.color = Color.argb((bubbleOpacity * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
    }

    private fun setDirty(value: Boolean) {
        if (dirty == value) return
        dirty = value
        onEditDirtyChanged?.invoke(value)
    }

    private fun scaleX(): Float = width.toFloat().coerceAtLeast(1f) / sourceWidth.coerceAtLeast(1)

    private fun scaleY(): Float = height.toFloat().coerceAtLeast(1f) / sourceHeight.coerceAtLeast(1)

    private fun cornerRadius(): Float = resources.displayMetrics.density * 8f
}
