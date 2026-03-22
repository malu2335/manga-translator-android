package com.manga.translate

import ai.onnxruntime.OrtEnvironment
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
        return sessionCache.getOrPut(cacheKey) {
            val modelFile = copyAssetToCacheIfMissing(cacheDir, assetProvider, assetName)
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threadProfile.intraOpThreads)
                setInterOpNumThreads(threadProfile.interOpThreads)
            }
            env.createSession(modelFile.absolutePath, options)
        }
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
            assetProvider(assetName).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        }
        return target
    }

    private val cacheLock = Any()
}
