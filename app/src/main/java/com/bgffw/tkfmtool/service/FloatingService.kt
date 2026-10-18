package com.bgffw.tkfmtool.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bgffw.tkfmtool.MainActivity
import com.bgffw.tkfmtool.R
import com.bgffw.tkfmtool.capture.ScreenCapture
import com.bgffw.tkfmtool.core.CharacterDatabase
import com.bgffw.tkfmtool.core.Clicker
import com.bgffw.tkfmtool.core.CorrectionPipeline
import com.bgffw.tkfmtool.core.Geometry
import com.bgffw.tkfmtool.core.MlKitOcrEngine
import com.bgffw.tkfmtool.core.OcrEngine
import com.bgffw.tkfmtool.core.OcrText
import com.bgffw.tkfmtool.core.SettingsRepository
import com.bgffw.tkfmtool.core.TagEntry
import com.bgffw.tkfmtool.core.TagMatcher
import com.bgffw.tkfmtool.core.TagVocabulary
import com.bgffw.tkfmtool.ui.ResultPanel
import com.bgffw.tkfmtool.ui.SettingsActivity
import com.bgffw.tkfmtool.util.dp
import com.bgffw.tkfmtool.util.overlayWindowType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮窗前台服务：整个工具的运行容器。
 *
 * 对应原版 `run.js` + `TKFMtool.js` 的组合：
 * * 悬浮按钮（拖动 / 长按开设置 / 点击开始识图）
 * * 屏幕截图 + 识图 + 词条匹配
 * * 自动点击词条、自动调节招募时间
 * * 结果面板
 */
class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var settings: SettingsRepository
    private lateinit var ocr: OcrEngine
    private lateinit var vocabulary: List<String>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var capture: ScreenCapture? = null
    private var button: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var panel: ResultPanel? = null
    private var hintView: TextView? = null

    private var screenWidth = 0
    private var screenHeight = 0

    private var working = false
    private var timeAdjustJob: Job? = null

    @Volatile
    private var lastEntries: List<TagEntry> = emptyList()

    // 悬浮按钮的拖拽 / 长按状态
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var longPressed = false

    private val longPressRunnable = Runnable {
        longPressed = true
        openSettings()
    }

    private val hideHintRunnable = Runnable { removeHint() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = SettingsRepository(this)
        CharacterDatabase.load(this)
        vocabulary = TagVocabulary.build(CharacterDatabase.rows)
        ocr = MlKitOcrEngine()

        val size = ScreenCapture.screenSize(this)
        screenWidth = size.first
        screenHeight = size.second

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        if (button == null) addFloatingButton()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.let { projectionData(it) }

        if (resultCode == Activity.RESULT_OK && data != null) {
            startCapture(resultCode, data)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        instance = null

        timeAdjustJob?.cancel()
        timeAdjustJob = null

        scope.cancel()

        panel?.dismiss()
        panel = null

        removeHint()
        handler.removeCallbacks(longPressRunnable)

        button?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        button = null
        buttonParams = null

        capture?.release()
        capture = null

        runCatching { ocr.close() }

        super.onDestroy()
    }

    // ---------------------------------------------------------------- 悬浮按钮

    private fun addFloatingButton() {
        val view = TextView(this).apply {
            text = getString(R.string.start_button_text)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@FloatingService, R.color.brand_dark))
            setBackgroundResource(R.drawable.bg_float_button)
            isClickable = true
            isFocusable = false
            includeFontPadding = false
        }

        val width = dp(88)
        val height = dp(40)
        val params = WindowManager.LayoutParams(
            width,
            height,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - width).coerceAtLeast(0)
            y = (screenHeight / 2).coerceAtLeast(0)
        }

        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    longPressed = false
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (!longPressed) {
                        params.x = (startX + dx).roundToInt()
                        params.y = (startY + dy).roundToInt()
                        runCatching { windowManager.updateViewLayout(touched, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    val still = abs(event.rawX - downRawX) < dp(5) &&
                        abs(event.rawY - downRawY) < dp(5)
                    if (!longPressed && still) onButtonClicked()
                    longPressed = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    longPressed = false
                    true
                }

                else -> false
            }
        }

        try {
            windowManager.addView(view, params)
            button = view
            buttonParams = params
        } catch (t: Throwable) {
            Log.w(TAG, "add floating button failed", t)
            showHint(getString(R.string.hint_need_overlay))
        }
    }

    private fun setButtonText(text: String) {
        button?.text = text
    }

    private fun onButtonClicked() {
        startRecognize()
    }

    private fun openSettings() {
        try {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (t: Throwable) {
            showHint(getString(R.string.hint_settings_failed))
        }
    }

    // ---------------------------------------------------------------- 提示气泡

    private fun showHint(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { showHint(message) }
            return
        }
        removeHint()

        val view = TextView(this).apply {
            text = message
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xCC000000.toInt())
            }
            includeFontPadding = false
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = ((buttonParams?.y ?: (screenHeight / 2)) + dp(48)).coerceAtLeast(0)
        }

        try {
            windowManager.addView(view, params)
            hintView = view
            handler.postDelayed(hideHintRunnable, HINT_DURATION_MS)
        } catch (t: Throwable) {
            hintView = null
        }
    }

    private fun removeHint() {
        handler.removeCallbacks(hideHintRunnable)
        val view = hintView ?: return
        hintView = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    // ---------------------------------------------------------------- 截图通道

    private fun startCapture(resultCode: Int, data: Intent) {
        capture?.release()
        capture = null

        val screenCapture = ScreenCapture(this)
        if (screenCapture.start(resultCode, data)) {
            capture = screenCapture
            val size = ScreenCapture.screenSize(this)
            screenWidth = size.first
            screenHeight = size.second
        } else {
            showHint(getString(R.string.hint_capture_denied))
        }
    }

    // ---------------------------------------------------------------- 主流程

    private fun startRecognize() {
        if (working) return

        val screenCapture = capture
        if (screenCapture == null) {
            showHint(getString(R.string.toast_capture_not_ready))
            return
        }

        working = true
        setButtonText(getString(R.string.start_button_busy))

        scope.launch {
            try {
                val entries = recognize(screenCapture)
                handleResult(entries)
            } catch (t: Throwable) {
                Log.w(TAG, "recognize failed", t)
                showHint(
                    getString(
                        if (t is CaptureFailure) R.string.toast_capture_failed else R.string.toast_error
                    )
                )
            } finally {
                working = false
                setButtonText(getString(R.string.start_button_text))
            }
        }
    }

    private fun handleResult(entries: List<TagEntry>) {
        if (entries.isEmpty()) {
            showHint(getString(R.string.toast_ocr_empty))
            return
        }

        val texts = entries.map { it.text }
        settings.lastResult = texts
        lastEntries = entries

        val matchers = TagMatcher.build(texts, CharacterDatabase.rows)
        val mode = settings.mode
        val best = matchers.firstOrNull()

        if (mode != SettingsRepository.MODE_RESULT_ONLY) {
            if (best == null) {
                showHint(getString(R.string.toast_no_match))
            } else {
                // 与原版一致：设时间与选词条是两条并行任务
                if (settings.autoSetTime) {
                    timeAdjustJob?.cancel()
                    timeAdjustJob = scope.launch {
                        applyRecruitTime(TagMatcher.isSureSR(best))
                    }
                }
                scope.launch { clickTags(best.tags, entries) }
            }
        }

        if (mode == SettingsRepository.MODE_RESULT_ONLY ||
            mode == SettingsRepository.MODE_RESULT_AND_AUTO
        ) {
            showPanel(matchers, texts)
        }
    }

    private fun showPanel(matchers: List<TagMatcher>, detected: List<String>) {
        if (panel == null) {
            panel = ResultPanel(
                context = this,
                windowManager = windowManager,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                onPick = ::onPickRow
            )
        }
        panel?.show(matchers, detected)
    }

    private fun onPickRow(matcher: TagMatcher) {
        val entries = lastEntries
        if (settings.autoSetTime) {
            timeAdjustJob?.cancel()
            timeAdjustJob = scope.launch { applyRecruitTime(TagMatcher.isSureSR(matcher)) }
        }
        scope.launch { clickTags(matcher.tags, entries) }
    }

    // ---------------------------------------------------------------- 识图

    private suspend fun recognize(screenCapture: ScreenCapture): List<TagEntry> =
        withContext(Dispatchers.Default) {
            val bitmap = screenCapture.capture() ?: throw CaptureFailure()
            try {
                val mapper = Geometry.Mapper(screenWidth, screenHeight)
                val bitmapRect = mapper.toBitmapRect(
                    mapper.tagRect(),
                    bitmap.width,
                    bitmap.height
                )

                val left = bitmapRect.left.coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
                val top = bitmapRect.top.coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
                val cropWidth = bitmapRect.width.coerceAtMost(bitmap.width - left).coerceAtLeast(1)
                val cropHeight = bitmapRect.height.coerceAtMost(bitmap.height - top).coerceAtLeast(1)

                val crop = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
                val input = if (settings.ocrAccurate) crop else downscale(crop, FAST_MODEL_SCALE)

                val blocks = try {
                    ocr.recognize(input)
                } finally {
                    if (input !== crop) runCatching { input.recycle() }
                    if (crop !== bitmap) runCatching { crop.recycle() }
                }

                // OCR 坐标 → 裁剪图坐标 → 屏幕坐标
                val scaleBack = crop.width.toFloat() / input.width.toFloat()
                val screenScaleX = screenWidth.toFloat() / bitmap.width.toFloat()
                val screenScaleY = screenHeight.toFloat() / bitmap.height.toFloat()

                val localized = blocks.map { block ->
                    OcrText(
                        text = block.text,
                        left = ((left + block.left * scaleBack) * screenScaleX).roundToInt(),
                        top = ((top + block.top * scaleBack) * screenScaleY).roundToInt(),
                        right = ((left + block.right * scaleBack) * screenScaleX).roundToInt(),
                        bottom = ((top + block.bottom * scaleBack) * screenScaleY).roundToInt()
                    )
                }

                CorrectionPipeline.apply(
                    blocks = localized,
                    corrections = settings.corrections,
                    vocabulary = vocabulary,
                    smartSnap = settings.smartCorrect
                )
            } finally {
                runCatching { bitmap.recycle() }
            }
        }

    private fun downscale(source: Bitmap, factor: Float): Bitmap {
        val width = (source.width * factor).roundToInt().coerceAtLeast(1)
        val height = (source.height * factor).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    // ---------------------------------------------------------------- 招募时间

    private suspend fun applyRecruitTime(haveSr: Boolean) {
        val target = if (haveSr) settings.haveSrTime else settings.noneSrTime
        val screenCapture = capture ?: return
        val mapper = Geometry.Mapper(screenWidth, screenHeight)
        val tapX = mapper.timeButtonX()
        val tapY = mapper.timeButtonY()
        val deadline = SystemClock.elapsedRealtime() + TIME_ADJUST_TIMEOUT_MS

        while (SystemClock.elapsedRealtime() < deadline) {
            val current = readRecruitTime(screenCapture, mapper)
            if (current == target) return
            if (current != null && VALID_TIMES.contains(current)) {
                Clicker.tap(tapX, tapY, Clicker.DEFAULT_PRESS_MS)
            }
            delay(1000L)
        }
    }

    private suspend fun readRecruitTime(
        screenCapture: ScreenCapture,
        mapper: Geometry.Mapper
    ): String? = withContext(Dispatchers.Default) {
        val bitmap = screenCapture.capture(1_500L) ?: return@withContext null
        try {
            val bitmapRect = mapper.toBitmapRect(mapper.timeRect(), bitmap.width, bitmap.height)
            val left = bitmapRect.left.coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
            val top = bitmapRect.top.coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
            val width = bitmapRect.width.coerceAtMost(bitmap.width - left).coerceAtLeast(1)
            val height = bitmapRect.height.coerceAtMost(bitmap.height - top).coerceAtLeast(1)

            val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
            val blocks = try {
                ocr.recognize(crop)
            } finally {
                runCatching { crop.recycle() }
            }

            val joined = blocks.joinToString(" ") { it.text }
            var value = DIGITS_REGEX.find(joined)?.value ?: return@withContext null
            // 原版特判：OCR 把 09 读成 60
            if (value == "60") value = "09"
            if (value.length == 1) value = "0$value"
            if (!VALID_TIMES.contains(value)) return@withContext null
            value
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    // ---------------------------------------------------------------- 点击

    private suspend fun clickTags(tags: List<String>, entries: List<TagEntry>) {
        if (!Clicker.isAvailable()) {
            showHint(getString(R.string.toast_accessibility_off))
            return
        }
        for (point in tagPositions(tags, entries)) {
            Clicker.tap(point.first, point.second, Clicker.DEFAULT_PRESS_MS)
            delay(60L)
        }
    }

    /**
     * 原版 `tagstoposition`：点击词条框的**右下角**，
     * 即 `bounds.right + 裁剪原点`、`bounds.bottom + 裁剪原点`。
     * 这里 OCR 坐标已经换算成屏幕坐标，直接用 right/bottom。
     */
    private fun tagPositions(
        tags: List<String>,
        entries: List<TagEntry>
    ): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>(tags.size)
        for (tag in tags) {
            val hit = entries.firstOrNull { it.text == tag } ?: continue
            out.add(hit.block.right.toFloat() to hit.block.bottom.toFloat())
        }
        return out
    }

    // ---------------------------------------------------------------- 通知

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
        }
    }

    private fun projectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    private class CaptureFailure : IllegalStateException("screen capture failed")

    companion object {
        private const val TAG = "FloatingService"

        private const val ACTION_START = "com.bgffw.tkfmtool.action.START"
        private const val ACTION_STOP = "com.bgffw.tkfmtool.action.STOP"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "tkfm_service"
        private const val NOTIFICATION_ID = 1001

        private const val LONG_PRESS_MS = 500L
        private const val HINT_DURATION_MS = 2_000L
        private const val TIME_ADJUST_TIMEOUT_MS = 10_000L
        private const val FAST_MODEL_SCALE = 0.75f

        private val DIGITS_REGEX = Regex("\\d{1,2}")
        private val VALID_TIMES = (1..9).map { it.toString().padStart(2, '0') }

        @Volatile
        var instance: FloatingService? = null
            private set

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, FloatingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FloatingService::class.java))
            }
        }
    }
}
