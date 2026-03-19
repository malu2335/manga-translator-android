package com.manga.translate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object ErrorDialogFormatter {
    fun formatApiErrorMessage(context: Context, errorCode: String, detail: String?): String {
        val resolvedDetail = extractApiErrorDetail(detail).ifBlank {
            when (errorCode) {
                "TIMEOUT" -> context.getString(R.string.api_request_error_timeout)
                "NETWORK_ERROR" -> context.getString(R.string.api_request_error_network)
                "MISSING_URL" -> context.getString(R.string.api_request_error_missing_url)
                "EMPTY_RESPONSE" -> context.getString(R.string.api_request_error_empty_response)
                else -> errorCode
            }
        }
        return if (resolvedDetail == errorCode) {
            resolvedDetail
        } else {
            "$resolvedDetail\n\n${context.getString(R.string.error_code_with_label, errorCode)}"
        }
    }

    fun formatModelErrorMessage(context: Context, responseContent: String): String {
        val resolved = extractApiErrorDetail(responseContent).ifBlank {
            responseContent.trim()
        }
        return context.getString(R.string.model_response_failed_message, resolved)
    }

    private fun extractApiErrorDetail(detail: String?): String {
        val trimmed = detail?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        parseJsonErrorDetail(trimmed)?.let { return it }
        return trimmed
    }

    private fun parseJsonErrorDetail(body: String): String? {
        return try {
            val root = JSONObject(body)
            extractErrorMessage(root)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractErrorMessage(json: JSONObject): String? {
        json.optString("message").trim().takeIf { it.isNotBlank() }?.let { return it }
        json.optString("error_description").trim().takeIf { it.isNotBlank() }?.let { return it }
        val errorValue = json.opt("error")
        when (errorValue) {
            is String -> if (errorValue.isNotBlank()) return errorValue.trim()
            is JSONObject -> extractErrorMessage(errorValue)?.let { return it }
            is JSONArray -> {
                for (i in 0 until errorValue.length()) {
                    val item = errorValue.opt(i)
                    when (item) {
                        is String -> if (item.isNotBlank()) return item.trim()
                        is JSONObject -> extractErrorMessage(item)?.let { return it }
                    }
                }
            }
        }
        return null
    }
}
