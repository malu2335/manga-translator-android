package com.manga.translate.background

import com.manga.translate.storage.TranslationTaskDescriptor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One-shot, same-process handoff to the service. Image paths must never travel
 * through Binder: a large library can exceed its transaction buffer before OCR
 * starts. Process death intentionally discards these tasks; it must not resume them.
 */
internal class PendingTranslationTasks {
    private val pending = ConcurrentHashMap<String, TranslationTaskDescriptor>()

    fun put(descriptor: TranslationTaskDescriptor): String {
        val id = UUID.randomUUID().toString()
        pending[id] = descriptor
        return id
    }

    fun take(id: String?): TranslationTaskDescriptor? = id?.let(pending::remove)
}
