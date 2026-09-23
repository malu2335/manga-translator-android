package com.manga.translate.floating

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Transparent screen overlay. Coordinates are normalized to the full capture display. */
internal class FloatingRegionSelectionView(
    context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    initialSelection: RectF
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val location = IntArray(2)
    private var selection = RectF(initialSelection)
    private var beforeDrag = RectF(selection)
    private var startX = 0f
    private var startY = 0f
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    fun selectedRegion(): RectF = RectF(selection)

    fun selectFullScreen() {
        selection.set(0f, 0f, 1f, 1f)
        onSelectionChanged?.invoke(true)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(location)
        val rect = RectF(
            selection.left * screenWidth - location[0],
            selection.top * screenHeight - location[1],
            selection.right * screenWidth - location[0],
            selection.bottom * screenHeight - location[1]
        )
        paint.style = Paint.Style.FILL
        paint.color = 0x88000000.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), max(0f, rect.top), paint)
        canvas.drawRect(0f, min(height.toFloat(), rect.bottom), width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, rect.top, max(0f, rect.left), rect.bottom, paint)
        canvas.drawRect(min(width.toFloat(), rect.right), rect.top, width.toFloat(), rect.bottom, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * resources.displayMetrics.density
        paint.color = Color.WHITE
        canvas.drawRect(rect, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = (event.rawX / screenWidth).coerceIn(0f, 1f)
        val y = (event.rawY / screenHeight).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beforeDrag = RectF(selection)
                startX = x
                startY = y
                selection.set(x, y, x, y)
                onSelectionChanged?.invoke(false)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                selection.set(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    // Match the existing inset limits; accidental taps keep the previous region.
                    if (selection.width() < 0.1f || selection.height() < 0.1f) selection.set(beforeDrag)
                    onSelectionChanged?.invoke(true)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                selection.set(beforeDrag)
                onSelectionChanged?.invoke(true)
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
