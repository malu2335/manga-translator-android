package com.manga.translate

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LockedWebtoonLinearLayoutManager(
    context: Context
) : LinearLayoutManager(context) {
    private var lockedPosition: Int? = null

    fun setLockedPosition(position: Int?) {
        if (lockedPosition == position) return
        lockedPosition = position
    }

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        val target = lockedPosition ?: return super.scrollVerticallyBy(dy, recycler, state)
        if (dy == 0 || childCount == 0) return 0
        val lockedView = findViewByPosition(target) ?: return 0
        val viewportTop = paddingTop
        val viewportBottom = height - paddingBottom
        val viewportHeight = viewportBottom - viewportTop
        val itemHeight = lockedView.height
        if (itemHeight <= viewportHeight) return 0

        val maxScrollDown = (lockedView.bottom - viewportBottom).coerceAtLeast(0)
        val maxScrollUp = (lockedView.top - viewportTop).coerceAtMost(0)
        val constrainedDy = when {
            dy > 0 -> dy.coerceAtMost(maxScrollDown)
            dy < 0 -> dy.coerceAtLeast(maxScrollUp)
            else -> 0
        }
        if (constrainedDy == 0) return 0

        val consumed = super.scrollVerticallyBy(constrainedDy, recycler, state)
        val updatedTop = findViewByPosition(target)?.top ?: return consumed
        val minTop = viewportBottom - itemHeight
        val correction = when {
            updatedTop > viewportTop -> updatedTop - viewportTop
            updatedTop < minTop -> updatedTop - minTop
            else -> 0
        }
        if (correction != 0) {
            super.scrollVerticallyBy(correction, recycler, state)
        }
        return consumed
    }
}
