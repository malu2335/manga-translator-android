package com.manga.translate.detection

import android.content.Context
import android.graphics.Bitmap
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.manga.translate.platform.AppLogger

/** GPU resources are created, invoked and destroyed on one dedicated thread. */
internal class TfliteDualModel(
    context: Context,
    assetName: String,
    threads: Int
) : AutoCloseable {
    private val worker = ModelExecutionThread()
    private val appContext = context.applicationContext
    private var closed = false
    private var session = try {
        worker.call {
            try {
                TfliteDualSession(appContext, assetName, threads, useGpu = true)
            } catch (failure: Exception) {
                AppLogger.log("BubbleDetector", "GPU initialization failed; using CPU", failure)
                cpuSession(assetName, threads)
            } catch (failure: LinkageError) {
                AppLogger.log("BubbleDetector", "GPU native library unavailable; using CPU", failure)
                cpuSession(assetName, threads)
            }
        }
    } catch (failure: Throwable) {
        worker.close()
        throw failure
    }
    private val modelAsset = assetName
    private val cpuThreads = threads
    val inputWidth get() = session.inputWidth
    val inputHeight get() = session.inputHeight
    val anchorCount get() = session.anchorCount
    val detections get() = session.detections
    val prototypes get() = session.prototypes
    val protoHeight get() = session.protoHeight
    val protoWidth get() = session.protoWidth

    private fun cpuSession(asset: String, threads: Int) =
        TfliteDualSession(appContext, asset, threads, useGpu = false)

    @Synchronized
    fun run(bitmap: Bitmap) {
        check(!closed) { "Detector has been closed" }
        worker.call {
            try {
                session.run(bitmap)
            } catch (failure: Exception) {
                if (!session.useGpu) throw failure
                fallbackAndRetry(bitmap, failure)
            } catch (failure: LinkageError) {
                if (!session.useGpu) throw failure
                fallbackAndRetry(bitmap, failure)
            }
        }
    }

    private fun fallbackAndRetry(bitmap: Bitmap, failure: Throwable) {
        AppLogger.log("BubbleDetector", "GPU inference failed; retrying on CPU", failure)
        session.close()
        session = cpuSession(modelAsset, cpuThreads)
        session.run(bitmap)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            worker.call { session.close() }
        } finally {
            worker.close()
        }
    }
}

/** Access only on ModelExecutionThread; output buffers are read after run completes. */
private class TfliteDualSession(
    context: Context,
    assetName: String,
    threads: Int,
    val useGpu: Boolean
) : AutoCloseable {
    // Keep the mapped model alive for the lifetime of its interpreter.
    private val modelBuffer = context.assets.openFd(assetName).use { asset ->
        FileInputStream(asset.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
        }
    }
    private var interpreter: Interpreter? = Interpreter(modelBuffer, Interpreter.Options()
        .setNumThreads(threads.coerceAtLeast(1))
        .setUseXNNPACK(false))
    private var gpuModel: CompiledModel? = null
    private var gpuInputs: List<TensorBuffer> = emptyList()
    private var gpuOutputs: List<TensorBuffer> = emptyList()
    private var closed = false
    val inputWidth: Int
    val inputHeight: Int
    val anchorCount: Int
    private val input: ByteBuffer?
    private val gpuInput: FloatArray?
    private val outputBuffers: Map<Int, Any>
    private val pixels: IntArray
    val detections: FloatBuffer
    val prototypes: FloatBuffer
    val protoHeight: Int
    val protoWidth: Int
    
    init {
        try {
            val interpreter = checkNotNull(interpreter)
            require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 2) {
                "Bubble/text segmentation model must have one input and two outputs"
            }
            val inputTensor = interpreter.getInputTensor(0)
            val shape = inputTensor.shape()
            require(inputTensor.dataType() == DataType.FLOAT32 &&
                shape.contentEquals(intArrayOf(1, 1472, 1472, 3))) {
                "Expected FLOAT32 NHWC [1,1472,1472,3] text input"
            }
            inputHeight = shape[1]
            inputWidth = shape[2]
            // Output order is not guaranteed by the TFLite converter. Resolve by shape.
            val detectionIndex = (0 until interpreter.outputTensorCount).single { index ->
                interpreter.getOutputTensor(index).shape().contentEquals(intArrayOf(1, 38, 44436))
            }
            val prototypeIndex = 1 - detectionIndex
            val detectionTensor = interpreter.getOutputTensor(detectionIndex)
            val prototypeTensor = interpreter.getOutputTensor(prototypeIndex)
            require(detectionTensor.dataType() == DataType.FLOAT32 &&
                prototypeTensor.dataType() == DataType.FLOAT32 &&
                prototypeTensor.shape().contentEquals(intArrayOf(1, 368, 368, 32))) {
                "Expected FLOAT32 detection [1,38,44436] and NHWC prototype [1,368,368,32] outputs"
            }
            anchorCount = detectionTensor.shape()[2]
            protoHeight = prototypeTensor.shape()[1]
            protoWidth = prototypeTensor.shape()[2]
            input = if (useGpu) null else directBuffer(inputTensor.numBytes())
            gpuInput = if (useGpu) FloatArray(inputTensor.numBytes() / Float.SIZE_BYTES) else null
            val head = directBuffer(detectionTensor.numBytes())
            detections = head.asFloatBuffer()
            val masks = directBuffer(prototypeTensor.numBytes())
            prototypes = masks.asFloatBuffer()
            outputBuffers = mapOf(detectionIndex to head, prototypeIndex to masks)
            pixels = IntArray(inputWidth * inputHeight)
            if (useGpu) {
                val compiled = CompiledModel.create(
                    context.assets, assetName, CompiledModel.Options(Accelerator.GPU)
                )
                gpuModel = compiled
                gpuInputs = compiled.createInputBuffers()
                gpuOutputs = compiled.createOutputBuffers()
                require(gpuInputs.size == 1 && gpuOutputs.size == 2)
                // Tensor metadata is resolved above using the CPU interpreter. Inference on GPU
                // uses the same subgraph input/output order, including models without signatures.
                interpreter.close()
                this.interpreter = null
            }
            AppLogger.log("BubbleDetector", "TFLite backend=${if (useGpu) "CompiledModel GPU" else "CPU"}")
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun run(bitmap: Bitmap) {
        require(bitmap.width == inputWidth && bitmap.height == inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        outputBuffers.values.forEach { (it as ByteBuffer).clear() }
        val compiled = gpuModel
        if (compiled != null) {
            val values = checkNotNull(gpuInput)
            writeDetectionRgbInput(pixels, values)
            gpuInputs.single().writeFloat(values)
            compiled.run(gpuInputs, gpuOutputs)
            gpuOutputs.forEachIndexed { index, tensor ->
                val output = (outputBuffers.getValue(index) as ByteBuffer).asFloatBuffer()
                val result = tensor.readFloat()
                require(result.size == output.capacity()) { "Unexpected GPU output size" }
                output.put(result)
            }
        } else {
            val input = checkNotNull(input)
            input.clear()
            for (pixel in pixels) {
                input.putFloat(((pixel ushr 16) and 0xff) / 255f)
                input.putFloat(((pixel ushr 8) and 0xff) / 255f)
                input.putFloat((pixel and 0xff) / 255f)
            }
            input.rewind()
            checkNotNull(interpreter).runForMultipleInputsOutputs(arrayOf(input), outputBuffers)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            try {
                gpuInputs.forEach { it.close() }
            } finally {
                gpuOutputs.forEach { it.close() }
            }
        } finally {
            try {
                gpuModel?.close()
            } finally {
                interpreter?.close()
            }
        }
    }

    private fun directBuffer(bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
}

/** Reuse the GPU input array directly instead of copying through a CPU byte buffer. */
internal fun writeDetectionRgbInput(pixels: IntArray, output: FloatArray) {
    require(output.size == pixels.size * 3)
    var index = 0
    for (pixel in pixels) {
        output[index++] = ((pixel ushr 16) and 0xff) / 255f
        output[index++] = ((pixel ushr 8) and 0xff) / 255f
        output[index++] = (pixel and 0xff) / 255f
    }
}

/** Decode the channel-first dual-label detection head before letterbox inversion. */
internal fun decodeDualDetections(
    values: FloatBuffer,
    anchorCount: Int,
    inputWidth: Int,
    inputHeight: Int,
    configuredThreshold: Float,
    nmsThreshold: Float = 0.45f
): List<RawDetection> {
    require(anchorCount > 0 && values.limit().toLong() == 38L * anchorCount)
    require(inputWidth > 0 && inputHeight > 0)
    require(configuredThreshold.isFinite() && nmsThreshold in 0f..1f)
    val candidates = ArrayList<RawDetection>()
    for (anchor in 0 until anchorCount) {
        fun feature(channel: Int) = values.get(channel * anchorCount + anchor)
        val score0 = feature(4)
        val score1 = feature(5)
        if (!score0.isFinite() || !score1.isFinite()) continue
        val classId = if (score0 >= score1) BubbleDetector.CLASS_BALLOON else BubbleDetector.CLASS_TEXT
        val confidence = maxOf(score0, score1)
        if (confidence !in 0f..1f ||
            confidence < effectiveDetectionConfidenceThreshold(classId, configuredThreshold)
        ) {
            continue
        }
        val cx = feature(0) * inputWidth
        val cy = feature(1) * inputHeight
        val width = feature(2) * inputWidth
        val height = feature(3) * inputHeight
        if (!cx.isFinite() || !cy.isFinite() || !width.isFinite() || !height.isFinite() ||
            width <= 0f || height <= 0f
        ) {
            continue
        }
        val coefficients = FloatArray(32) { feature(6 + it) }
        if (coefficients.any { !it.isFinite() }) continue
        candidates += RawDetection(cx, cy, width, height, confidence, classId, coefficients)
    }
    val kept = ArrayList<RawDetection>()
    for (candidate in candidates.sortedByDescending { it.confidence }) {
        if (kept.none { it.classId == candidate.classId && rawDetectionIou(it, candidate) > nmsThreshold }) kept += candidate
        if (kept.size >= 300) break
    }
    return kept
}

private fun rawDetectionIou(a: RawDetection, b: RawDetection): Float {
    val w = (minOf(a.cx + a.width / 2, b.cx + b.width / 2) - maxOf(a.cx - a.width / 2, b.cx - b.width / 2)).coerceAtLeast(0f)
    val h = (minOf(a.cy + a.height / 2, b.cy + b.height / 2) - maxOf(a.cy - a.height / 2, b.cy - b.height / 2)).coerceAtLeast(0f)
    val inter = w * h; val union = a.width * a.height + b.width * b.height - inter
    return if (union > 0f) inter / union else 0f
}
