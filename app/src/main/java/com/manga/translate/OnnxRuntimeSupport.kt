package com.manga.translate

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

enum class OnnxThreadProfile(
    val intraOpThreads: Int,
    val interOpThreads: Int
) {
    SINGLE(1, 1),
    LIGHT(2, 1);

    companion object {
        fun forCpuBoundWork(parallelism: Int): OnnxThreadProfile {
            return if (parallelism <= 1) LIGHT else SINGLE
        }
    }
}

object OnnxRuntimeSupport {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionCache = ConcurrentHashMap<String, OrtSession>()

    fun environment(): OrtEnvironment = env

    fun getOrCreateSession(
        cacheDir: File,
        assetProvider: (String) -> java.io.InputStream,
        assetName: String,
        threadProfile: OnnxThreadProfile
    ): OrtSession {
        val cacheKey = "${cacheDir.absolutePath}|$assetName|${threadProfile.name}"
        sessionCache[cacheKey]?.let { return it }
        synchronized(cacheLock) {
            sessionCache[cacheKey]?.let { return it }
            val session = createSessionWithRecovery(
                cacheDir = cacheDir,
                assetProvider = assetProvider,
                assetName = assetName,
                threadProfile = threadProfile
            )
            sessionCache[cacheKey] = session
            return session
        }
    }

    private fun createSessionWithRecovery(
        cacheDir: File,
        assetProvider: (String) -> java.io.InputStream,
        assetName: String,
        threadProfile: OnnxThreadProfile
    ): OrtSession {
        val modelFile = copyAssetToCacheIfMissing(cacheDir, assetProvider, assetName)
        return try {
            createSession(modelFile, threadProfile)
        } catch (e: OrtException) {
            if (!shouldRebuildCache(e)) throw e
            AppLogger.log(
                "OnnxRuntime",
                "Model cache looks corrupted, rebuilding $assetName",
                e
            )
            deleteCachedModel(cacheDir, assetName)
            val rebuiltFile = forceCopyAssetToCache(cacheDir, assetProvider, assetName)
            createSession(rebuiltFile, threadProfile)
        }
    }

    private fun createSession(
        modelFile: File,
        threadProfile: OnnxThreadProfile
    ): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadProfile.intraOpThreads)
            setInterOpNumThreads(threadProfile.interOpThreads)
        }
        return options.use {
            env.createSession(modelFile.absolutePath, it)
        }
    }

    fun forceCopyAssetToCache(
        cacheDir: File,
        assetProvider: (String) -> java.io.InputStream,
        assetName: String
    ): File {
        val target = File(cacheDir, assetName)
        synchronized(cacheLock) {
            if (target.exists()) {
                target.delete()
            }
            copyAssetToCache(target, assetProvider, assetName)
        }
        return target
    }

    fun copyAssetToCacheIfMissing(
        cacheDir: File,
        assetProvider: (String) -> java.io.InputStream,
        assetName: String
    ): File {
        val target = File(cacheDir, assetName)
        synchronized(cacheLock) {
            if (target.exists() && target.length() > 0L) {
                return target
            }
            if (target.exists()) {
                target.delete()
            }
            copyAssetToCache(target, assetProvider, assetName)
        }
        return target
    }

    private fun copyAssetToCache(
        target: File,
        assetProvider: (String) -> java.io.InputStream,
        assetName: String
    ) {
        target.parentFile?.mkdirs()
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        try {
            assetProvider(assetName).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (!tempFile.renameTo(target)) {
                tempFile.copyTo(target, overwrite = true)
                if (!tempFile.delete()) {
                    tempFile.deleteOnExit()
                }
            }
        } catch (t: Throwable) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw t
        }
    }

    private fun deleteCachedModel(cacheDir: File, assetName: String) {
        val target = File(cacheDir, assetName)
        if (target.exists() && !target.delete()) {
            target.deleteOnExit()
        }
        val tempFile = File(cacheDir, "$assetName.tmp")
        if (tempFile.exists() && !tempFile.delete()) {
            tempFile.deleteOnExit()
        }
    }

    private fun shouldRebuildCache(error: OrtException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("ORT_INVALID_PROTOBUF", ignoreCase = true) ||
            message.contains("Protobuf parsing failed", ignoreCase = true) ||
            message.contains("Load model from", ignoreCase = true)
    }

    private val cacheLock = Any()
}
