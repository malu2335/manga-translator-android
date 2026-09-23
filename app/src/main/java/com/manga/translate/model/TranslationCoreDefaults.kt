package com.manga.translate.model


object TranslationCoreDefaults {
    const val DefaultDetectionInputSize = 640
    const val DefaultLineDetectionInputSize = 960

    const val MinBalloonConfidence = 0.32f
    // Supplemental text detections are more prone to class confusion than
    // balloon detections, especially across LiteRT GPU/CPU backends.
    const val MinTextConfidence = 0.16f
    // Leave a little more room around detected balloons.  The supplement
    // detectors otherwise see the clipped outline/tail at the box edge as a
    // standalone glyph.
    const val PageRegionMaskExpandRatio = 0.15f
    const val PageRegionMaskExpandMin = 4f

    const val PageRegionTextIouThreshold = 0.2f
    const val TinyBubbleShortSideMinPx = 12f
    const val TinyBubbleLongSideMinPx = 28f
    // Page numbers and punctuation-sized model artifacts should not become
    // independent translation regions on high-resolution pages.
    const val TinyBubbleShortSideRatio = 0.02f
    const val TinyBubbleLongSideRatio = 0.035f
    const val TinyBubbleMaxAreaRatio = 0.0008f
    const val BubbleDedupIouThreshold = 0.65f

    const val VlBubbleExpandRatio = 0.1f
    const val VlBubbleExpandMin = 4f
}
