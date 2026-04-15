package com.manga.translate

fun extractTaggedSegments(
    text: String,
    fallback: List<String>,
    onMissingTags: ((expected: Int) -> Unit)? = null,
    onCountMismatch: ((expected: Int, actual: Int) -> Unit)? = null
): List<String> {
    val expected = fallback.size
    val regex = Regex("<b>(.*?)</b>", RegexOption.DOT_MATCHES_ALL)
    val matches = regex.findAll(text).map { it.groupValues[1].trim() }.toList()
    if (matches.isEmpty()) {
        if (expected == 1) return listOf(text.trim())
        onMissingTags?.invoke(expected)
        return List(expected) { "" }
    }
    if (matches.size != expected) {
        onCountMismatch?.invoke(expected, matches.size)
    }
    val result = MutableList(expected) { "" }
    val limit = minOf(expected, matches.size)
    for (i in 0 until limit) {
        result[i] = matches[i]
    }
    return result
}
