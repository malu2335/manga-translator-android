package com.manga.translate

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt

internal object PdfImageCodec {
    private const val IMPORT_RENDER_SCALE = 2f
    private const val IMPORT_FILE_NAME_WIDTH = 4

    fun renderPdfToImages(
        contentResolver: ContentResolver,
        uri: Uri,
        outputDir: File
    ): Int {
        val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: return 0
        return descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                var imported = 0
                for (index in 0 until renderer.pageCount) {
                    val page = renderer.openPage(index)
                    try {
                        val bitmap = Bitmap.createBitmap(
                            (page.width * IMPORT_RENDER_SCALE).roundToInt().coerceAtLeast(1),
                            (page.height * IMPORT_RENDER_SCALE).roundToInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        try {
                            bitmap.eraseColor(0xFFFFFFFF.toInt())
                            val matrix = Matrix().apply {
                                setScale(IMPORT_RENDER_SCALE, IMPORT_RENDER_SCALE)
                            }
                            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val fileName = "${(index + 1).toString().padStart(IMPORT_FILE_NAME_WIDTH, '0')}.png"
                            FileOutputStream(File(outputDir, fileName)).use { output ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                            }
                            imported += 1
                        } finally {
                            bitmap.recycle()
                        }
                    } finally {
                        page.close()
                    }
                }
                imported
            }
        }
    }

    fun writeImagesToPdf(
        images: List<File>,
        outputStream: OutputStream
    ): Boolean {
        if (images.isEmpty()) return false
        val document = PdfDocument()
        return try {
            images.forEachIndexed { index, image ->
                val bitmap = android.graphics.BitmapFactory.decodeFile(image.absolutePath) ?: return false
                try {
                    val pageInfo = PdfDocument.PageInfo.Builder(
                        bitmap.width.coerceAtLeast(1),
                        bitmap.height.coerceAtLeast(1),
                        index + 1
                    ).create()
                    val page = document.startPage(pageInfo)
                    try {
                        drawBitmapFit(page.canvas, bitmap)
                    } finally {
                        document.finishPage(page)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            document.writeTo(outputStream)
            true
        } catch (e: Exception) {
            AppLogger.log("PdfImageCodec", "Write PDF failed", e)
            false
        } finally {
            document.close()
        }
    }

    private fun drawBitmapFit(canvas: Canvas, bitmap: Bitmap) {
        val src = Rect(0, 0, bitmap.width, bitmap.height)
        val dst = Rect(0, 0, canvas.width, canvas.height)
        canvas.drawColor(0xFFFFFFFF.toInt())
        canvas.drawBitmap(bitmap, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }
}
