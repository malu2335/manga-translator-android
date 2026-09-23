package com.manga.translate.ocr

import android.content.Context
import com.manga.translate.detection.OnnxThreadProfile
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore

class PPOcrV6SmallRec(
    context: Context,
    threadProfile: OnnxThreadProfile = OnnxThreadProfile.LIGHT,
    settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) : PaddleOcrBase(
    context = context,
    modelAssetName = MODEL_ASSET,
    logTag = LOG_TAG,
    threadProfile = threadProfile,
    settingsStore = settingsStore,
    dictAssetName = DICT_ASSET
) {
    override fun getDefaultCharset(): List<String> {
        val latinDict = "!\"#\$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]_`abcdefghijklmnopqrstuvwxyz{}¡£§ª«­°²³´µ·º»¿ÀÁÂÄÅÇÈÉÊËÌÍÎÏÒÓÔÕÖÚÜÝßàáâãäåæçèéêëìíîïñòóôõöøùúûüýąĆćČčĐđęıŁłōŒœŠšŸŽžʒβδεзṠ'€™"

        val chars = mutableListOf("blank")
        chars.addAll(latinDict.map { it.toString() })

        AppLogger.log(LOG_TAG, "Using latin_dict charset with ${chars.size} characters")
        return chars
    }

    companion object {
        private const val MODEL_ASSET = "models/ocr/PP-OCRv6_small_rec.onnx"
        private const val DICT_ASSET = "models/ocr/ppocr_keys_v6_small.txt"
        private const val LOG_TAG = "PPOcrV6SmallRec"
    }
}
