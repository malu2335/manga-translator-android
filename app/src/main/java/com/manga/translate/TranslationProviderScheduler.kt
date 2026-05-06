package com.manga.translate

import java.util.concurrent.atomic.AtomicInteger

data class AdditionalTranslationProvider(
    val name: String,
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val weight: Int,
    val enabled: Boolean = true
) {
    fun isConfigured(): Boolean {
        return apiUrl.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank()
    }
}

data class WeightedProviderCandidate(
    val providerId: String,
    val displayName: String,
    val settings: ApiSettings,
    val weight: Int,
    val isPrimary: Boolean
)

data class PageTranslationProviderContext(
    val providerId: String,
    val displayName: String,
    val apiSettings: ApiSettings,
    val isPrimary: Boolean
)

class WeightedTranslationProviderScheduler(
    candidates: List<WeightedProviderCandidate>
) {
    private val normalizedCandidates = candidates.filter { it.weight > 0 }
    private val weightedSequence: List<WeightedProviderCandidate> = buildList {
        normalizedCandidates.forEach { candidate ->
            repeat(candidate.weight) {
                add(candidate)
            }
        }
    }
    private val nextIndex = AtomicInteger(0)

    fun orderedCandidatesForPage(): List<PageTranslationProviderContext> {
        if (weightedSequence.isEmpty()) return emptyList()
        val start = nextIndex.getAndUpdate { current ->
            val next = current + 1
            if (next >= weightedSequence.size) 0 else next
        }
        val ordered = ArrayList<PageTranslationProviderContext>(normalizedCandidates.size)
        val seen = LinkedHashSet<String>(normalizedCandidates.size)
        for (offset in weightedSequence.indices) {
            val candidate = weightedSequence[(start + offset) % weightedSequence.size]
            if (!seen.add(candidate.providerId)) continue
            ordered += candidate.toContext()
            if (seen.size == normalizedCandidates.size) {
                break
            }
        }
        return ordered
    }

    private fun WeightedProviderCandidate.toContext(): PageTranslationProviderContext {
        return PageTranslationProviderContext(
            providerId = providerId,
            displayName = displayName,
            apiSettings = settings,
            isPrimary = isPrimary
        )
    }
}
