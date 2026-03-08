package com.manga.translate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FloatingBallOverlayService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val settingsStore by lazy { SettingsStore(applicationContext) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var controllerRoot: LinearLayout? = null
    private var controllerLayoutParams: WindowManager.LayoutParams? = null
    private var detectionOverlayView: FloatingDetectionOverlayView? = null
    private var detectionLayoutParams: WindowManager.LayoutParams? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var textDetector: TextDetector? = null
    private var mangaOcr: MangaOcr? = null
    private var llmClient: LlmClient? = null
    private var detectJob: Job? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var densityDpi = 0
    private var bubbleDragEnabled = false
    private var bubbleDragToggleButton: AppCompatButton? = null
    private var progressStatusView: TextView? = null
    private val hideProgressStatusRunnable = Runnable {
        progressStatusView?.visibility = View.GONE
    }
    private val bubbleDragAutoDisableRunnable = Runnable {
        if (!bubbleDragEnabled) return@Runnable
        applyBubbleDragEnabled(false)
        Toast.makeText(
            this,
            R.string.overlay_drag_bubble_auto_disabled,
            Toast.LENGTH_SHORT
        ).show()
    }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            scope.launch(Dispatchers.Main) {
                releaseProjection()
                Toast.makeText(
                    this@FloatingBallOverlayService,
                    R.string.floating_capture_not_ready,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.log("FloatingOCR", "Service onStartCommand action=${intent?.action ?: "null"}")
        if (intent?.action == ACTION_STOP) {
            AppLogger.log("FloatingOCR", "Received stop action")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!canDrawOverlays()) {
            AppLogger.log("FloatingOCR", "Overlay permission missing, stop service")
            stopSelf()
            return START_NOT_STICKY
        }
        ensureForeground()
        ensureWindowManager()
        bubbleDragEnabled = settingsStore.loadFloatingBubbleDragEnabled()
        AppLogger.log("FloatingOCR", "Loaded bubbleDragEnabled=$bubbleDragEnabled")
        if (detectionOverlayView == null) {
            showDetectionOverlay()
        }
        if (controllerRoot == null) {
            showControllerOverlay()
            showUsageTip()
        }
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
            val data = intent.getParcelableIntentExtraCompat(EXTRA_RESULT_DATA)
            if (resultCode != Int.MIN_VALUE && data != null) {
                AppLogger.log("FloatingOCR", "Prepare projection from start intent")
                prepareProjection(resultCode, data)
            } else {
                AppLogger.log("FloatingOCR", "Start intent missing projection extras")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        detectJob?.cancel()
        cancelBubbleDragAutoDisable()
        releaseProjection()
        removeOverlay()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun canDrawOverlays(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        return Settings.canDrawOverlays(this)
    }

    private fun ensureForeground() {
        val manager = getSystemService<NotificationManager>() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_service_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.floating_service_title))
            .setContentText(getString(R.string.floating_service_message))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfoForegroundTypes.MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showControllerOverlay() {
        ensureWindowManager()
        val density = resources.displayMetrics.density
        val ballSize = (56f * density).toInt()
        val margin = (8f * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val progressView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(
                (10f * density).toInt(),
                (6f * density).toInt(),
                (10f * density).toInt(),
                (6f * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(0xCC1B1B1B.toInt())
                setStroke((1f * density).toInt(), 0x44FFFFFF)
            }
            visibility = View.GONE
        }
        val floatingBall = TextView(this).apply {
            text = "译"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF3F51B5.toInt())
            }
        }

        root.addView(
            progressView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4f * density).toInt()
            }
        )
        val menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (8f * density).toInt(),
                (8f * density).toInt(),
                (8f * density).toInt(),
                (8f * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(0xF5FFFFFF.toInt())
                setStroke((1f * density).toInt(), 0x33222222)
            }
            visibility = View.GONE
        }
        val bubbleDragButton = AppCompatButton(this).apply {
            textSize = 13f
            setTextColor(0xFF1F1F1F.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(0xFFFFFFFF.toInt())
                setStroke((1f * density).toInt(), 0x33222222)
            }
            minimumWidth = 0
            minWidth = 0
            setPadding(
                (10f * density).toInt(),
                (8f * density).toInt(),
                (10f * density).toInt(),
                (8f * density).toInt()
            )
            setOnClickListener {
                applyBubbleDragEnabled(!bubbleDragEnabled)
            }
        }
        val exitButton = AppCompatButton(this).apply {
            text = getString(R.string.overlay_exit_button)
            textSize = 13f
            setTextColor(0xFF1F1F1F.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(0xFFFFFFFF.toInt())
                setStroke((1f * density).toInt(), 0x33222222)
            }
            minimumWidth = 0
            minWidth = 0
            setPadding(
                (10f * density).toInt(),
                (8f * density).toInt(),
                (10f * density).toInt(),
                (8f * density).toInt()
            )
            setOnClickListener { stopSelf() }
        }
        bubbleDragToggleButton = bubbleDragButton
        updateBubbleDragToggleButton()
        menuPanel.addView(
            bubbleDragButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        menuPanel.addView(
            exitButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6f * density).toInt()
            }
        )

        root.addView(
            menuPanel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4f * density).toInt()
            }
        )
        root.addView(
            floatingBall,
            LinearLayout.LayoutParams(ballSize, ballSize).apply {
                topMargin = margin
            }
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - ballSize - margin).coerceAtLeast(0)
            y = (180f * density).toInt()
        }

        attachBallGesture(floatingBall, menuPanel, params)
        windowManager.addView(root, params)
        AppLogger.log("FloatingOCR", "Controller overlay added")
        controllerRoot = root
        controllerLayoutParams = params
        progressStatusView = progressView
    }

    private fun showDetectionOverlay() {
        ensureWindowManager()
        val overlay = FloatingDetectionOverlayView(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            buildDetectionFlags(bubbleDragEnabled),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        overlay.setBubbleDragEnabled(bubbleDragEnabled)
        overlay.setBubbleOpacity(settingsStore.loadTranslationBubbleOpacity())
        windowManager.addView(overlay, params)
        AppLogger.log("FloatingOCR", "Detection overlay added dragEnabled=$bubbleDragEnabled")
        detectionOverlayView = overlay
        detectionLayoutParams = params
    }

    private fun ensureWindowManager() {
        if (!this::windowManager.isInitialized) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
    }

    private fun buildDetectionFlags(dragEnabled: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!dragEnabled) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    private fun applyBubbleDragEnabled(enabled: Boolean) {
        bubbleDragEnabled = enabled
        settingsStore.saveFloatingBubbleDragEnabled(enabled)
        AppLogger.log("FloatingOCR", "Bubble drag toggled enabled=$enabled")
        if (enabled) {
            scheduleBubbleDragAutoDisable()
            Toast.makeText(
                this,
                R.string.overlay_drag_bubble_auto_disable_notice,
                Toast.LENGTH_SHORT
            ).show()
        } else {
            cancelBubbleDragAutoDisable()
        }
        updateBubbleDragToggleButton()
        detectionOverlayView?.setBubbleDragEnabled(enabled)
        val params = detectionLayoutParams ?: return
        val newFlags = buildDetectionFlags(enabled)
        if (params.flags != newFlags) {
            params.flags = newFlags
            try {
                windowManager.updateViewLayout(detectionOverlayView, params)
            } catch (_: Exception) {
            }
        }
        ensureControllerOnTop()
    }

    private fun scheduleBubbleDragAutoDisable() {
        cancelBubbleDragAutoDisable()
        mainHandler.postDelayed(bubbleDragAutoDisableRunnable, BUBBLE_DRAG_AUTO_DISABLE_MS)
    }

    private fun showProgressStatus(messageResId: Int, autoHide: Boolean = false) {
        showProgressStatus(getString(messageResId), autoHide)
    }

    private fun showProgressStatus(message: String, autoHide: Boolean = false) {
        val statusView = progressStatusView ?: return
        mainHandler.removeCallbacks(hideProgressStatusRunnable)
        statusView.text = message
        statusView.visibility = View.VISIBLE
        if (autoHide) {
            mainHandler.postDelayed(hideProgressStatusRunnable, FLOATING_PROGRESS_HIDE_DELAY_MS)
        }
    }

    private fun cancelBubbleDragAutoDisable() {
        mainHandler.removeCallbacks(bubbleDragAutoDisableRunnable)
    }

    private fun ensureControllerOnTop() {
        val root = controllerRoot ?: return
        val params = controllerLayoutParams ?: return
        try {
            windowManager.removeView(root)
            windowManager.addView(root, params)
        } catch (_: Exception) {
        }
    }

    private fun updateBubbleDragToggleButton() {
        val suffix = if (bubbleDragEnabled) "开" else "关"
        bubbleDragToggleButton?.text = "${getString(R.string.overlay_drag_bubble_option)}：$suffix"
    }

    private fun showUsageTip() {
        Toast.makeText(this, R.string.floating_usage_tip, Toast.LENGTH_LONG).show()
    }

    private fun prepareProjection(resultCode: Int, data: Intent) {
        AppLogger.log("FloatingOCR", "Preparing projection")
        releaseProjection()
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return
        val projection = manager.getMediaProjection(resultCode, data) ?: return
        mediaProjection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        val metrics = resources.displayMetrics
        captureWidth = metrics.widthPixels.coerceAtLeast(1)
        captureHeight = metrics.heightPixels.coerceAtLeast(1)
        densityDpi = metrics.densityDpi.coerceAtLeast(1)
        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2
        )
        virtualDisplay = projection.createVirtualDisplay(
            "floating-ocr-capture",
            captureWidth,
            captureHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        AppLogger.log("FloatingOCR", "Projection ready ${captureWidth}x${captureHeight}@${densityDpi}dpi")
    }

    private fun runTextDetection() {
        if (detectJob?.isActive == true) return
        val projection = mediaProjection
        if (projection == null || imageReader == null) {
            AppLogger.log("FloatingOCR", "Run detection blocked: projection not ready")
            showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
            Toast.makeText(this, R.string.floating_capture_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        AppLogger.log("FloatingOCR", "Run detection started")
        showProgressStatus(R.string.floating_progress_capturing)
        detectJob = scope.launch(Dispatchers.Default) {
            var bitmap: Bitmap? = null
            try {
                bitmap = captureCurrentScreen()
                if (bitmap == null) {
                    AppLogger.log("FloatingOCR", "Capture screen returned null")
                    withContext(Dispatchers.Main) {
                        showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            R.string.floating_capture_not_ready,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    showProgressStatus(R.string.floating_progress_detecting)
                }
                val detector = textDetector ?: TextDetector(applicationContext).also { textDetector = it }
                val ocr = mangaOcr ?: runCatching {
                    MangaOcr(applicationContext)
                }.getOrNull()?.also {
                    mangaOcr = it
                }
                val detections = detector.detect(bitmap)
                AppLogger.log("FloatingOCR", "Raw detections count=${detections.size}")
                val deduplicatedRects = RectGeometryDeduplicator.mergeSupplementRects(
                    detections,
                    bitmap.width,
                    bitmap.height
                )
                if (deduplicatedRects.size < detections.size) {
                    AppLogger.log(
                        "FloatingOCR",
                        "Deduplicated overlapping detections: ${detections.size} -> ${deduplicatedRects.size}"
                    )
                }
                AppLogger.log("FloatingOCR", "Deduplicated detections count=${deduplicatedRects.size}")
                withContext(Dispatchers.Main) {
                    showProgressStatus(
                        getString(R.string.floating_progress_recognizing, deduplicatedRects.size)
                    )
                }
                val bubbles = ArrayList<BubbleTranslation>(deduplicatedRects.size)
                for ((index, rect) in deduplicatedRects.withIndex()) {
                    val crop = cropBitmap(bitmap, rect)
                    if (crop == null) {
                        bubbles.add(
                            BubbleTranslation(
                                id = index,
                                rect = rect,
                                text = "",
                                source = BubbleSource.TEXT_DETECTOR
                            )
                        )
                        continue
                    }
                    val text = try {
                        ocr?.recognize(crop)?.trim().orEmpty()
                    } catch (e: Exception) {
                        AppLogger.log("FloatingOCR", "MangaOCR recognize failed", e)
                        ""
                    } finally {
                        crop.recycle()
                    }
                    bubbles.add(
                        BubbleTranslation(
                            id = index,
                            rect = rect,
                            text = text,
                            source = BubbleSource.TEXT_DETECTOR
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    showProgressStatus(R.string.floating_progress_translating)
                }
                val translatedBubbles = translateBubblesIfConfigured(
                    bubbles = bubbles,
                    timeoutMs = FLOATING_TRANSLATE_TIMEOUT_MS.toInt(),
                    retryCount = FLOATING_TRANSLATE_RETRY_COUNT
                )
                if (translatedBubbles == null) {
                    AppLogger.log("FloatingOCR", "Translate timeout")
                    withContext(Dispatchers.Main) {
                        showProgressStatus(R.string.floating_translate_timeout, autoHide = true)
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            R.string.floating_translate_timeout,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    detectionOverlayView?.setDetections(bitmap.width, bitmap.height, translatedBubbles)
                    showProgressStatus(
                        getString(R.string.floating_progress_done, translatedBubbles.size),
                        autoHide = true
                    )
                    Toast.makeText(
                        this@FloatingBallOverlayService,
                        getString(R.string.floating_detected_count, translatedBubbles.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                AppLogger.log("FloatingOCR", "Run detection finished bubbles=${translatedBubbles.size}")
            } catch (e: Exception) {
                AppLogger.log("FloatingOCR", "Floating detection failed", e)
                withContext(Dispatchers.Main) {
                    showProgressStatus(R.string.floating_detect_failed, autoHide = true)
                    Toast.makeText(
                        this@FloatingBallOverlayService,
                        R.string.floating_detect_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                bitmap?.recycle()
            }
        }
    }

    private suspend fun translateBubblesIfConfigured(
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int
    ): List<BubbleTranslation>? {
        if (bubbles.isEmpty()) return bubbles
        val translatable = bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) {
            AppLogger.log("FloatingOCR", "Skip translate: no translatable text")
            return bubbles
        }
        val client = llmClient ?: LlmClient(applicationContext).also { llmClient = it }
        val floatingApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings()
        if (!client.isConfigured(floatingApiSettings)) {
            AppLogger.log("FloatingOCR", "Skip translate: LLM client not configured")
            return bubbles
        }
        AppLogger.log("FloatingOCR", "Translate request segments=${translatable.size}")
        return try {
            val text = translatable.joinToString("\n") { "<b>${it.text}</b>" }
            val translated = client.translate(
                text = text,
                glossary = emptyMap(),
                promptAsset = FLOAT_PROMPT_ASSET,
                requestTimeoutMs = timeoutMs,
                retryCount = retryCount,
                apiSettings = floatingApiSettings
            ) ?: return bubbles
            val segments = extractTaggedSegments(
                translated.translation,
                translatable.map { it.text }
            )
            val translatedMap = HashMap<Int, String>(translatable.size)
            for (i in translatable.indices) {
                translatedMap[translatable[i].id] = segments.getOrElse(i) { translatable[i].text }
            }
            val result = bubbles.map { bubble ->
                val translatedText = translatedMap[bubble.id]
                if (translatedText.isNullOrBlank()) {
                    bubble
                } else {
                    bubble.copy(text = translatedText)
                }
            }
            AppLogger.log("FloatingOCR", "Translate success segments=${translatedMap.size}")
            result
        } catch (e: LlmRequestException) {
            if (e.errorCode == "TIMEOUT") {
                AppLogger.log("FloatingOCR", "LLM translate timeout")
                null
            } else {
                AppLogger.log("FloatingOCR", "LLM translate request failed, fallback to OCR text", e)
                bubbles
            }
        } catch (e: Exception) {
            AppLogger.log("FloatingOCR", "LLM translate failed, fallback to OCR text", e)
            bubbles
        }
    }

    private fun captureCurrentScreen(): Bitmap? {
        val reader = imageReader ?: return null
        var image = reader.acquireLatestImage()
        var retry = 0
        while (image == null && retry < 8) {
            Thread.sleep(50)
            image = reader.acquireLatestImage()
            retry++
        }
        if (image == null) {
            AppLogger.log("FloatingOCR", "Capture frame timeout retry=$retry")
            return null
        }
        image.use { frame ->
            val plane = frame.planes.firstOrNull() ?: return null
            val width = frame.width
            val height = frame.height
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val fullWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(fullWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            AppLogger.log("FloatingOCR", "Captured frame ${width}x${height} retry=$retry")
            return Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                bitmap.recycle()
            }
        }
    }

    private fun attachBallGesture(
        target: TextView,
        menuPanel: View,
        params: WindowManager.LayoutParams
    ) {
        val touchSlop = (3f * resources.displayMetrics.density)
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var dragging = false
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    menuPanel.visibility = View.GONE
                    runTextDetection()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    menuPanel.visibility = View.GONE
                    detectionOverlayView?.clearDetections()
                    if (bubbleDragEnabled) {
                        applyBubbleDragEnabled(false)
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    menuPanel.visibility = if (menuPanel.visibility == View.VISIBLE) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                }
            }
        )
        target.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        menuPanel.visibility = View.GONE
                    }
                    if (!dragging) {
                        return@setOnTouchListener true
                    }
                    params.x = (downX + dx).toInt().coerceAtLeast(0)
                    params.y = (downY + dy).toInt().coerceAtLeast(0)
                    windowManager.updateViewLayout(controllerRoot, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    dragging
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    false
                }

                else -> true
            }
        }
    }

    private fun removeOverlay() {
        val root = controllerRoot
        if (root != null) {
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
            }
        }
        val detection = detectionOverlayView
        if (detection != null) {
            try {
                windowManager.removeView(detection)
            } catch (_: Exception) {
            }
        }
        controllerRoot = null
        controllerLayoutParams = null
        detectionOverlayView = null
        detectionLayoutParams = null
        bubbleDragToggleButton = null
        progressStatusView = null
    }

    private fun releaseProjection() {
        val projection = mediaProjection
        if (projection != null) {
            try {
                projection.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    private object ServiceInfoForegroundTypes {
        const val MEDIA_PROJECTION = 0x00000020
    }

    companion object {
        const val ACTION_START = "com.manga.translate.action.FLOATING_START"
        const val ACTION_STOP = "com.manga.translate.action.FLOATING_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val BUBBLE_DRAG_AUTO_DISABLE_MS = 10_000L
        private const val FLOATING_PROGRESS_HIDE_DELAY_MS = 2_000L
        private const val CHANNEL_ID = "floating_detect_channel"
        private const val NOTIFICATION_ID = 2002
        private const val FLOAT_PROMPT_ASSET = "float_llm_prompts.json"
        private const val FLOATING_TRANSLATE_TIMEOUT_MS = 30_000L
        private const val FLOATING_TRANSLATE_RETRY_COUNT = 1
    }
}

private fun Intent.getParcelableIntentExtraCompat(key: String): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}
