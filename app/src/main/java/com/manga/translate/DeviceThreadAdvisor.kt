package com.manga.translate

import android.app.ActivityManager
import android.content.Context
import kotlin.math.max
import kotlin.math.min

data class ThreadAdvice(
    val recommendedThreads: Int,
    val warningThreshold: Int,
    val summary: String
)

object DeviceThreadAdvisor {
    private const val MIB = 1024L * 1024L
    private const val TEXT_MASK_MODEL_BYTES = 11L * MIB
    private const val MIGAN_MODEL_BYTES = 656L * 1024L + 29L * MIB

    fun adviseEmbed(context: Context, imageRepairEnabled: Boolean): ThreadAdvice {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val memoryClassMb = activityManager?.memoryClass ?: 0
        val largeMemoryClassMb = activityManager?.largeMemoryClass ?: memoryClassMb
        val lowRam = activityManager?.isLowRamDevice ?: false
        val embedModelBytes = TEXT_MASK_MODEL_BYTES + if (imageRepairEnabled) MIGAN_MODEL_BYTES else 0L
        val embedModelMb = max(1, (embedModelBytes / MIB).toInt())

        val recommended = when {
            lowRam -> 1
            memoryClassMb in 1..256 -> 1
            cpuCores <= 4 -> 1
            imageRepairEnabled && memoryClassMb <= 384 -> 1
            imageRepairEnabled && cpuCores <= 6 -> 2
            memoryClassMb <= 384 -> min(2, max(1, cpuCores / 3))
            cpuCores <= 6 -> 2
            imageRepairEnabled -> min(3, max(2, cpuCores / 3))
            else -> min(4, max(2, cpuCores / 2))
        }.coerceIn(1, 16)

        val warningThreshold = when {
            lowRam -> 2
            imageRepairEnabled && memoryClassMb <= 384 -> 2
            imageRepairEnabled -> min(4, recommended + 1)
            memoryClassMb <= 384 -> min(3, recommended + 1)
            else -> min(6, recommended + 2)
        }.coerceAtLeast(recommended).coerceIn(1, 16)

        val summary = buildString {
            append("推荐线程数：")
            append(recommended)
            append("。当前设备约 ")
            append(cpuCores)
            append(" 核 / ")
            append(memoryClassMb)
            append("MB 内存级别")
            if (largeMemoryClassMb > memoryClassMb) {
                append("（大内存上限 ")
                append(largeMemoryClassMb)
                append("MB）")
            }
            if (lowRam) {
                append("，系统标记为低内存设备")
            }
            append("。嵌字模型常驻约 ")
            append(embedModelMb)
            append("MB")
            if (imageRepairEnabled) {
                append("，已包含图像修复模型")
            } else {
                append("，未启用图像修复")
            }
            append("。超过 ")
            append(warningThreshold)
            append(" 线程可能导致卡顿或系统压力明显上升。")
        }

        return ThreadAdvice(
            recommendedThreads = recommended,
            warningThreshold = warningThreshold,
            summary = summary
        )
    }
}
