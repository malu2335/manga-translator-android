package com.manga.translate.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import com.manga.translate.BuildConfig
import com.manga.translate.platform.AppLogger

/**
 * Single source of truth for the running build's version, resolved at runtime.
 *
 * The version is declared exactly once, in `app/build.gradle.kts`. From there it flows into
 * the merged manifest (read back via [PackageManager]) and into [BuildConfig] (used as the
 * fallback when the package lookup fails). Nothing here is hand-maintained, so the local
 * version can no longer drift below the one published in `update.json` and cause the update
 * dialog to fire on every launch.
 */
object VersionInfo {
    @Volatile
    private var cachedCode: Int? = null

    @Volatile
    private var cachedName: String? = null

    /**
     * Version code of the installed APK, falling back to [BuildConfig.VERSION_CODE].
     *
     * Reading the manifest at runtime avoids the stale-constant failure mode: Kotlin inlines
     * `const val`s at compile time, so a call site compiled against an older BuildConfig can
     * keep comparing against a version code the APK no longer has.
     */
    fun versionCode(context: Context): Int {
        cachedCode?.let { return it }
        val resolved = readPackageInfo(context)?.let { info ->
            PackageInfoCompat.getLongVersionCode(info).toInt()
        }
        if (resolved != null && resolved > 0) {
            if (resolved != BuildConfig.VERSION_CODE) {
                AppLogger.log(
                    "VersionInfo",
                    "Manifest versionCode=$resolved differs from BuildConfig=${BuildConfig.VERSION_CODE}"
                )
            }
            cachedCode = resolved
            return resolved
        }
        cachedCode = BuildConfig.VERSION_CODE
        return BuildConfig.VERSION_CODE
    }

    /** Version name of the installed APK, falling back to [BuildConfig.VERSION_NAME]. */
    fun versionName(context: Context): String {
        cachedName?.let { return it }
        val resolved = readPackageInfo(context)?.versionName?.takeIf { it.isNotBlank() }
            ?: BuildConfig.VERSION_NAME
        cachedName = resolved
        return resolved
    }

    private fun readPackageInfo(context: Context) = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        AppLogger.log("VersionInfo", "Own package not found, falling back to BuildConfig", e)
        null
    } catch (e: Exception) {
        AppLogger.log("VersionInfo", "Failed to read package info, falling back to BuildConfig", e)
        null
    }
}
