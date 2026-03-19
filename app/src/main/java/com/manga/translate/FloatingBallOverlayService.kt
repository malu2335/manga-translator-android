package com.manga.translate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
    private val floatingTranslationCacheStore by lazy {
        FloatingTranslationCacheStore(applicationContext)
    }
    private val emptyBubbleCoordinator by lazy {
        FloatingEmptyBubbleCoordinator(
            context = applicationContext,
            llmClient = llmClient ?: LlmClient(applicationContext).also { llmClient = it },
            floatingTranslationCacheStore = floatingTranslationCacheStore,
            settingsStore = settingsStore
        )
    }
    private val floatingBubbleTranslationCoordinator by lazy {
        FloatingBubbleTranslationCoordinator(
            llmClient = llmClient ?: LlmClient(applicationContext).also { llmClient = it },
            floatingTranslationCacheStore = floatingTranslationCacheStore,
            settingsStore = settingsStore
        )
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var controllerRoot: LinearLayout? = null
    private var controllerLayoutParams: WindowManager.LayoutParams? = null
    private var controllerMenuPanel: LinearLayout? = null
    private var detectionOverlayView: FloatingDetectionOverlayView? = null
    private var detectionLayoutParams: WindowManager.LayoutParams? = null
    private val screenCaptureSession by lazy {
        ProjectionCaptureSession(applicationContext) {
            scope.launch(Dispatchers.Main) {
                clearCurrentSession()
                releaseProjection()
                Toast.makeText(
                    this@FloatingBallOverlayService,
                    R.string.floating_capture_not_ready,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    private var textDetector: TextDetector? = null
    private var mangaOcr: MangaOcr? = null
    private var llmClient: LlmClient? = null
    private var detectJob: Job? = null
    private var bubbleDragEnabled = false
    private var bubbleDragToggleButton: AppCompatButton? = null
    private var editModeToggleButton: AppCompatButton? = null
    private var addBubbleButton: AppCompatButton? = null
    private var confirmEditButton: AppCompatButton? = null
    private var cancelEditButton: AppCompatButton? = null
    private var progressStatusView: TextView? = null
    private var currentSession: TranslationResult? = null
    private var editSessionSnapshot: TranslationResult? = null
    private var currentSessionBitmap: Bitmap? = null
    private var editModeEnabled = false
    private var createBubbleModeEnabled = false
    private var editSessionDirty = false
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
        clearCurrentSession()
        releaseProjection()
        removeOverlay()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun canDrawOverlays(): Boolean {
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
        val bubbleDragButton = createMenuButton().apply {
            setOnClickListener { applyBubbleDragEnabled(!bubbleDragEnabled) }
        }
        val editButton = createMenuButton().apply {
            setOnClickListener { toggleEditMode() }
        }
        val addButton = createMenuButton().apply {
            text = getString(R.string.overlay_add_bubble_button)
            setOnClickListener { toggleCreateBubbleMode() }
        }
        val confirmButton = createMenuButton().apply {
            text = getString(R.string.overlay_confirm_button)
            setOnClickListener {
                controllerMenuPanel?.visibility = View.GONE
                confirmEditSession()
            }
        }
        val cancelButton = createMenuButton().apply {
            text = getString(R.string.overlay_cancel_button)
            setOnClickListener {
                controllerMenuPanel?.visibility = View.GONE
                cancelEditSession()
            }
        }
        val exitButton = createMenuButton().apply {
            text = getString(R.string.overlay_exit_button)
            setOnClickListener { stopSelf() }
        }
        bubbleDragToggleButton = bubbleDragButton
        editModeToggleButton = editButton
        addBubbleButton = addButton
        confirmEditButton = confirmButton
        cancelEditButton = cancelButton
        updateBubbleDragToggleButton()
        updateEditModeToggleButton()
        updateEditButtons()
        menuPanel.addView(
            bubbleDragButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        menuPanel.addView(
            editButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6f * density).toInt()
            }
        )
        menuPanel.addView(
            addButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6f * density).toInt()
            }
        )
        menuPanel.addView(
            confirmButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6f * density).toInt()
            }
        )
        menuPanel.addView(
            cancelButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6f * density).toInt()
            }
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
        controllerMenuPanel = menuPanel
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
            buildDetectionFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        overlay.setBubbleDragEnabled(bubbleDragEnabled)
        overlay.setBubbleOpacity(settingsStore.loadTranslationBubbleOpacity())
        overlay.setEditMode(editModeEnabled)
        overlay.setCreateBubbleMode(createBubbleModeEnabled)
        overlay.onBubblesChanged = { bubbles ->
            val session = currentSession
            if (session != null) {
                currentSession = session.copy(bubbles = bubbles)
            }
        }
        overlay.onBubbleDelete = { bubbleId ->
            val session = currentSession
            if (session != null) {
                currentSession = session.copy(bubbles = session.bubbles.filterNot { it.id == bubbleId })
                syncOverlaySession()
            }
        }
        overlay.onManualBubbleCreated = { rect ->
            appendManualBubble(rect)
        }
        overlay.onEditDirtyChanged = { dirty ->
            editSessionDirty = dirty
            updateEditButtons()
        }
        windowManager.addView(overlay, params)
        AppLogger.log("FloatingOCR", "Detection overlay added dragEnabled=$bubbleDragEnabled")
        detectionOverlayView = overlay
        detectionLayoutParams = params
        syncOverlaySession()
    }

    private fun ensureWindowManager() {
        if (!this::windowManager.isInitialized) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
    }

    private fun buildDetectionFlags(): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!bubbleDragEnabled && !editModeEnabled) {
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
        val newFlags = buildDetectionFlags()
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
        bubbleDragToggleButton?.text = getString(
            R.string.overlay_drag_bubble_option_format,
            if (bubbleDragEnabled) getString(R.string.common_on) else getString(R.string.common_off)
        )
    }

    private fun updateEditModeToggleButton() {
        editModeToggleButton?.text = getString(
            R.string.overlay_edit_mode_option_format,
            if (editModeEnabled) getString(R.string.common_on) else getString(R.string.common_off)
        )
    }

    private fun updateEditButtons() {
        val isEditing = editModeEnabled
        addBubbleButton?.visibility = if (isEditing) View.VISIBLE else View.GONE
        confirmEditButton?.visibility = if (isEditing) View.VISIBLE else View.GONE
        cancelEditButton?.visibility = if (isEditing) View.VISIBLE else View.GONE
        addBubbleButton?.isEnabled = isEditing && currentSession != null
        confirmEditButton?.isEnabled = isEditing && currentSession != null
        cancelEditButton?.isEnabled = isEditing
        addBubbleButton?.alpha = if (addBubbleButton?.isEnabled == true) 1f else 0.5f
        confirmEditButton?.alpha = if (confirmEditButton?.isEnabled == true) 1f else 0.5f
        cancelEditButton?.alpha = if (cancelEditButton?.isEnabled == true) 1f else 0.5f
        addBubbleButton?.text = if (createBubbleModeEnabled) {
            getString(R.string.overlay_add_bubble_mode_active)
        } else {
            getString(R.string.overlay_add_bubble_button)
        }
    }

    private fun createMenuButton(): AppCompatButton {
        val density = resources.displayMetrics.density
        return AppCompatButton(this).apply {
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
        }
    }

    private fun toggleEditMode() {
        if (editModeEnabled) {
            cancelEditSession()
            controllerMenuPanel?.visibility = View.GONE
            return
        }
        val session = currentSession
        if (session == null) {
            Toast.makeText(this, R.string.overlay_edit_requires_detection, Toast.LENGTH_SHORT).show()
            return
        }
        editModeEnabled = true
        createBubbleModeEnabled = false
        editSessionDirty = false
        editSessionSnapshot = session.deepCopy()
        detectionOverlayView?.setEditMode(true)
        detectionOverlayView?.setCreateBubbleMode(false)
        refreshDetectionOverlayTouchability()
        updateEditModeToggleButton()
        updateEditButtons()
        Toast.makeText(this, R.string.overlay_edit_mode_enabled, Toast.LENGTH_SHORT).show()
    }

    private fun toggleCreateBubbleMode() {
        if (!editModeEnabled) return
        createBubbleModeEnabled = !createBubbleModeEnabled
        detectionOverlayView?.setCreateBubbleMode(createBubbleModeEnabled)
        updateEditButtons()
        if (createBubbleModeEnabled) {
            Toast.makeText(this, R.string.overlay_create_bubble_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelEditSession() {
        if (!editModeEnabled) return
        val restored = editSessionSnapshot?.deepCopy()
        editModeEnabled = false
        createBubbleModeEnabled = false
        editSessionDirty = false
        editSessionSnapshot = null
        currentSession = restored ?: currentSession
        detectionOverlayView?.setEditMode(false)
        detectionOverlayView?.setCreateBubbleMode(false)
        syncOverlaySession()
        refreshDetectionOverlayTouchability()
        updateEditModeToggleButton()
        updateEditButtons()
        Toast.makeText(this, R.string.overlay_edit_canceled, Toast.LENGTH_SHORT).show()
    }

    private fun finishEditSession(showToast: Boolean) {
        editModeEnabled = false
        createBubbleModeEnabled = false
        editSessionDirty = false
        editSessionSnapshot = null
        detectionOverlayView?.setEditMode(false)
        detectionOverlayView?.setCreateBubbleMode(false)
        refreshDetectionOverlayTouchability()
        updateEditModeToggleButton()
        updateEditButtons()
        if (showToast) {
            Toast.makeText(this, R.string.overlay_edit_applied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmEditSession() {
        if (!editModeEnabled) return
        val session = currentSession ?: run {
            finishEditSession(showToast = false)
            return
        }
        val bitmapSnapshot = currentSessionBitmap?.let { source ->
            runCatching { source.copy(source.config ?: Bitmap.Config.ARGB_8888, false) }.getOrNull()
        } ?: run {
            showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
            Toast.makeText(this, R.string.floating_capture_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        showProgressStatus(R.string.overlay_empty_bubble_translating)
        detectJob?.cancel()
        detectJob = scope.launch(Dispatchers.Default) {
            try {
                val outcome = emptyBubbleCoordinator.process(
                    bitmap = bitmapSnapshot,
                    baseTranslation = session,
                    timeoutMs = FLOATING_TRANSLATE_TIMEOUT_MS.toInt(),
                    retryCount = FLOATING_TRANSLATE_RETRY_COUNT,
                    floatPromptAsset = FLOAT_PROMPT_ASSET,
                    floatVlPromptAsset = FLOAT_VL_PROMPT_ASSET,
                    maxVlConcurrency = MAX_FLOATING_VL_TRANSLATE_CONCURRENCY
                )
                withContext(Dispatchers.Main) {
                    if (outcome.requiresVlModel) {
                        showProgressStatus(R.string.floating_vl_model_required, autoHide = true)
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            R.string.floating_vl_model_required,
                            Toast.LENGTH_LONG
                        ).show()
                        return@withContext
                    }
                    if (outcome.timedOut) {
                        showProgressStatus(R.string.floating_translate_timeout, autoHide = true)
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            R.string.floating_translate_timeout,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@withContext
                    }
                    currentSession = outcome.translation
                    syncOverlaySession()
                    finishEditSession(showToast = false)
                    showProgressStatus(R.string.overlay_empty_bubble_translated, autoHide = true)
                    Toast.makeText(
                        this@FloatingBallOverlayService,
                        R.string.overlay_empty_bubble_translated,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                bitmapSnapshot.recycle()
            }
        }
    }

    private fun appendManualBubble(rect: RectF) {
        val session = currentSession ?: return
        val nextId = (session.bubbles.maxOfOrNull { it.id } ?: -1) + 1
        val bubble = BubbleTranslation(nextId, RectF(rect), "", BubbleSource.MANUAL)
        currentSession = session.copy(bubbles = session.bubbles + bubble)
        createBubbleModeEnabled = false
        syncOverlaySession()
        detectionOverlayView?.setCreateBubbleMode(false)
        updateEditButtons()
    }

    private fun syncOverlaySession() {
        val session = currentSession
        if (session == null) {
            detectionOverlayView?.clearDetections()
            updateEditButtons()
            return
        }
        detectionOverlayView?.setTranslationSession(
            session.width,
            session.height,
            session.bubbles
        )
        updateEditButtons()
    }

    private fun refreshDetectionOverlayTouchability() {
        val params = detectionLayoutParams ?: return
        val newFlags = buildDetectionFlags()
        if (params.flags == newFlags) return
        params.flags = newFlags
        try {
            windowManager.updateViewLayout(detectionOverlayView, params)
        } catch (_: Exception) {
        }
        ensureControllerOnTop()
    }

    private fun replaceCurrentSessionBitmap(bitmap: Bitmap?) {
        currentSessionBitmap?.recycle()
        currentSessionBitmap = bitmap
    }

    private fun clearCurrentSession() {
        currentSession = null
        editSessionSnapshot = null
        editModeEnabled = false
        createBubbleModeEnabled = false
        editSessionDirty = false
        detectionOverlayView?.setEditMode(false)
        detectionOverlayView?.setCreateBubbleMode(false)
        detectionOverlayView?.clearDetections()
        replaceCurrentSessionBitmap(null)
        refreshDetectionOverlayTouchability()
        updateEditModeToggleButton()
        updateEditButtons()
    }

    private fun showUsageTip() {
        Toast.makeText(this, R.string.floating_usage_tip, Toast.LENGTH_LONG).show()
    }

    private fun prepareProjection(resultCode: Int, data: Intent) {
        AppLogger.log("FloatingOCR", "Preparing projection")
        releaseProjection()
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return
        if (!screenCaptureSession.prepare(manager, resultCode, data, resources.displayMetrics, PixelFormat.RGBA_8888)) {
            AppLogger.log("FloatingOCR", "Projection preparation failed")
        }
    }

    private fun runTextDetection() {
        if (detectJob?.isActive == true) return
        if (editModeEnabled) {
            finishEditSession(showToast = false)
        }
        if (!screenCaptureSession.isReady()) {
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
                bitmap = screenCaptureSession.captureCurrentScreen()
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
                val client = llmClient ?: LlmClient(applicationContext).also { llmClient = it }
                val floatingSettings = settingsStore.loadFloatingTranslateApiSettings()
                val floatingApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings()
                val useVlDirectTranslate =
                    floatingSettings.useVlDirectTranslate &&
                        client.isConfigured(floatingApiSettings)
                val vlOutcome = if (useVlDirectTranslate) {
                    withContext(Dispatchers.Main) {
                        showProgressStatus(
                            getString(R.string.floating_progress_vl_translating, deduplicatedRects.size)
                        )
                    }
                    floatingBubbleTranslationCoordinator.translateImageBubbles(
                        bitmap = bitmap,
                        bubbles = deduplicatedRects.mapIndexed { index, rect ->
                            BubbleTranslation(
                                id = index,
                                rect = rect,
                                text = "",
                                source = BubbleSource.TEXT_DETECTOR
                            )
                        },
                        timeoutMs = FLOATING_TRANSLATE_TIMEOUT_MS.toInt(),
                        retryCount = FLOATING_TRANSLATE_RETRY_COUNT,
                        promptAsset = FLOAT_VL_PROMPT_ASSET,
                        apiSettings = floatingApiSettings,
                        concurrency = floatingSettings.vlTranslateConcurrency,
                        maxConcurrency = MAX_FLOATING_VL_TRANSLATE_CONCURRENCY
                    )
                } else {
                    null
                }
                if (vlOutcome?.requiresVlModel == true) {
                    withContext(Dispatchers.Main) {
                        showProgressStatus(R.string.floating_vl_model_required, autoHide = true)
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            R.string.floating_vl_model_required,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                val translatedBubbles = if (useVlDirectTranslate) {
                    if (vlOutcome?.timedOut == true) {
                        null
                    } else {
                        vlOutcome?.bubbles ?: emptyList()
                    }
                } else {
                    val ocr = mangaOcr ?: runCatching {
                        MangaOcr(applicationContext)
                    }.getOrNull()?.also {
                        mangaOcr = it
                    }
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
                    floatingBubbleTranslationCoordinator.translateTextBubbles(
                        bubbles = bubbles,
                        timeoutMs = FLOATING_TRANSLATE_TIMEOUT_MS.toInt(),
                        retryCount = FLOATING_TRANSLATE_RETRY_COUNT,
                        promptAsset = FLOAT_PROMPT_ASSET,
                        apiSettings = floatingApiSettings,
                        language = TranslationLanguage.JA_TO_ZH
                    )
                }
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
                    currentSession = TranslationResult(
                        imageName = "",
                        width = bitmap.width,
                        height = bitmap.height,
                        bubbles = translatedBubbles
                    )
                    editSessionSnapshot = null
                    createBubbleModeEnabled = false
                    syncOverlaySession()
                    replaceCurrentSessionBitmap(bitmap)
                    bitmap = null
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
                    clearCurrentSession()
                    if (bubbleDragEnabled) {
                        applyBubbleDragEnabled(false)
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    menuPanel.visibility = if (menuPanel.visibility == View.VISIBLE) {
                        View.GONE
                    } else {
                        updateEditButtons()
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
                    if (!dragging) {
                        target.performClick()
                    }
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
        controllerMenuPanel = null
        detectionOverlayView = null
        detectionLayoutParams = null
        bubbleDragToggleButton = null
        editModeToggleButton = null
        addBubbleButton = null
        confirmEditButton = null
        cancelEditButton = null
        progressStatusView = null
    }

    private fun releaseProjection() {
        screenCaptureSession.release()
    }

    private fun TranslationResult.deepCopy(): TranslationResult {
        return copy(
            bubbles = bubbles.map { bubble ->
                bubble.copy(rect = RectF(bubble.rect))
            }
        )
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
        private const val FLOAT_VL_PROMPT_ASSET = "float_vl_bubble_prompts.json"
        private const val FLOATING_TRANSLATE_TIMEOUT_MS = 30_000L
        private const val FLOATING_TRANSLATE_RETRY_COUNT = 1
        private const val MAX_FLOATING_VL_TRANSLATE_CONCURRENCY = 16
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
