package com.manga.translate

import java.io.File

data class FolderItem(
    val folder: File,
    val imageCount: Int,
    val chapterCount: Int = 0,
    val isCollection: Boolean = false,
    val status: FolderStatus = FolderStatus.UNTRANSLATED
)

data class ImageItem(
    val file: File,
    val translated: Boolean,
    val embedded: Boolean
)

enum class FolderStatus(val labelRes: Int) {
    TRANSLATED(R.string.image_translated),
    UNTRANSLATED(R.string.image_not_translated),
    EMBEDDED(R.string.image_embedded)
}
