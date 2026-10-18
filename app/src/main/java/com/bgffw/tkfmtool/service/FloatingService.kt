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
import com.bgffw.tkfmtool.BuildConfig
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
import com.bgffw.tkfmtool.core.RecruitTimeParser
import com.bgffw.tkfmtool.core.SettingsRepository
import com.bgffw.tkfmtool.core.TagEntry
import com.bgffw.tkfmtool.core.TagMatcher
import com.bgffw.tkfmtool.core.TagVocabulary
import com.bgffw.tkfmtool.ui.ResultPanel
import com.bgffw.tkfmtool.ui.SettingsActivity
import com.bgffw.tkfmtool.util.dp
import com.bgffw.tkfmtool.util.overlayWindowType
import kotlinx.coroutines.CancellationException
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
    private var autoFlowJob: Job? = null

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

        autoFlowJob?.cancel()
        autoFlowJob = null

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
                startAutoFlow(best, entries)
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
        startAutoFlow(matcher, lastEntries)
    }

    /**
     * 自动选择的核心流程：先点词条，等界面切到「招募时间」页，再拨时间。
     *
     * 原版把这两步放在两个线程里并行，结果前半段全在等界面、白耗掉时间预算。
     * 这里改成串行，并且加了重入保护——连点悬浮按钮时不会叠出两套点击。
     */
    private fun startAutoFlow(matcher: TagMatcher, entries: List<TagEntry>) {
        if (autoFlowJob?.isActive == true) return

        val autoTime = settings.autoSetTime
        val sureSr = TagMatcher.isSureSR(matcher)

        autoFlowJob = scope.launch {
            clickTags(matcher.tags, entries)
            if (!autoTime) return@launch
            delay(TIME_PICK_SETTLE_MS)
            applyRecruitTime(sureSr)
        }
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
                dumpDebugImage(crop, "tag_region.png")

                // 精准模型：把裁剪图放大一倍再识别，让笔画之间的间隙更清楚；
                // 快速模型：原尺寸直送。见 ACCURATE_MODEL_SCALE 的注释。
                val input = rescale(
                    crop,
                    if (settings.ocrAccurate) ACCURATE_MODEL_SCALE else 1f
                )
                val inputWidth = input.width
                val inputHeight = input.height

                val ocrStartedAt = SystemClock.elapsedRealtime()
                val blocks = try {
                    ocr.recognize(input)
                } finally {
                    if (input !== crop) runCatching { input.recycle() }
                    if (crop !== bitmap) runCatching { crop.recycle() }
                }
                Log.d(
                    TAG,
                    "ocr ${if (settings.ocrAccurate) "accurate" else "fast"} " +
                        "input=${inputWidth}x${inputHeight} crop=${cropWidth}x$cropHeight " +
                        "took=${SystemClock.elapsedRealtime() - ocrStartedAt}ms " +
                        "blocks=${blocks.size}"
                )

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

    /** 按比例缩放；factor 为 1 时直接返回原图，避免多一次拷贝。 */
    private fun rescale(source: Bitmap, factor: Float): Bitmap {
        if (factor == 1f) return source
        val width = (source.width * factor).roundToInt().coerceAtLeast(1)
        val height = (source.height * factor).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    /**
     * 把裁剪出来的区域落盘，用于排查「区域裁错 / 坐标偏了」这类问题。
     *
     * 只在 debug 包写文件，位置：
     * `/sdcard/Android/data/com.bgffw.tkfmtool.debug/files/debug/`
     * 取回：`adb pull /sdcard/Android/data/com.bgffw.tkfmtool.debug/files/debug/`
     */
    private fun dumpDebugImage(bitmap: Bitmap, name: String) {
        if (!BuildConfig.DEBUG) return
        try {
            val dir = getExternalFilesDir("debug") ?: return
            if (!dir.exists() && !dir.mkdirs()) return
            java.io.File(dir, name).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "dump $name failed", t)
        }
    }

    // ---------------------------------------------------------------- 招募时间

    /**
     * 把招募时间拨到设定值。
     *
     * 原版是裸的 `while (读值 != 目标) { 点一下; sleep(1000) }`，靠 10 秒定时器兜底中断。
     * 那套节奏在 AutoX 的 PaddleOCR 上够用，本工程每轮要「截图 + ML Kit 识别」，
     * 一圈就要一两秒，10 秒只够点两三次，于是经常停在中间值。
     *
     * 这里改成两段式预算：
     * * 还没读到数字时（界面没切过来），用较短的间隔快速轮询，最多等 [TIME_WAIT_TIMEOUT_MS]；
     * * 一旦读到数字，进入调整阶段，每次点击后短暂等待再重读，最多 [TIME_ADJUST_TIMEOUT_MS]。
     */
    private suspend fun applyRecruitTime(haveSr: Boolean) {
        val target = if (haveSr) settings.haveSrTime else settings.noneSrTime
        val screenCapture = capture ?: return
        val mapper = Geometry.Mapper(screenWidth, screenHeight)
        val tapX = mapper.timeButtonX()
        val tapY = mapper.timeButtonY()

        val startedAt = SystemClock.elapsedRealtime()
        var firstSeenAt = -1L
        var lastValue: String? = null
        var stuckCount = 0
        var missCount = 0
        var round = 0
        var tapCount = 0

        Log.d(TAG, "adjust recruit time start, target=$target tap=($tapX,$tapY)")

        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (firstSeenAt < 0) {
                if (now - startedAt > TIME_WAIT_TIMEOUT_MS) {
                    Log.d(TAG, "recruit time panel never appeared (unreadable=$missCount)")
                    return
                }
            } else if (now - firstSeenAt > TIME_ADJUST_TIMEOUT_MS) {
                Log.d(
                    TAG,
                    "adjust recruit time timeout, last=$lastValue target=$target taps=$tapCount"
                )
                return
            }

            round++
            val current = readRecruitTime(screenCapture, mapper)

            if (current == null) {
                missCount++
                // 每 5 次打一条，避免刷屏但能看出是不是一直读不到
                if (missCount % 5 == 1) {
                    Log.d(TAG, "recruit time unreadable (#$missCount)")
                }
                delay(TIME_RETRY_DELAY_MS)
                continue
            }

            if (firstSeenAt < 0) firstSeenAt = SystemClock.elapsedRealtime()
            if (current == target) {
                Log.d(TAG, "recruit time reached $target in $round rounds, taps=$tapCount")
                return
            }

            stuckCount = if (current == lastValue) stuckCount + 1 else 0
            lastValue = current

            if (stuckCount >= 3) {
                Log.w(
                    TAG,
                    "value stuck at $current after $stuckCount taps; " +
                        "tap point ($tapX,$tapY) may be wrong for this resolution"
                )
            }

            Log.d(TAG, "round#$round current=$current target=$target stuck=$stuckCount")
            Clicker.tap(tapX, tapY, Clicker.DEFAULT_PRESS_MS)
            tapCount++
            delay(TIME_AFTER_TAP_DELAY_MS)
        }
    }

    private suspend fun readRecruitTime(
        screenCapture: ScreenCapture,
        mapper: Geometry.Mapper
    ): String? = withContext(Dispatchers.Default) {
        val bitmap = screenCapture.capture(TIME_CAPTURE_TIMEOUT_MS) ?: return@withContext null
        try {
            val bitmapRect = mapper.toBitmapRect(mapper.timeRect(), bitmap.width, bitmap.height)
            val left = bitmapRect.left.coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
            val top = bitmapRect.top.coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
            val width = bitmapRect.width.coerceAtMost(bitmap.width - left).coerceAtLeast(1)
            val height = bitmapRect.height.coerceAtMost(bitmap.height - top).coerceAtLeast(1)

            val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
            dumpDebugImage(crop, "time_region.png")
            val blocks = try {
                ocr.recognize(crop)
            } finally {
                runCatching { crop.recycle() }
            }

            RecruitTimeParser.parse(blocks)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "read recruit time failed", t)
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

        /**
         * 精准模型的输入放大倍数。
         *
         * ML Kit 的中文识别器对字高有下限，词条区放大一倍后笔画之间分得更开，
         * 「闇／間」「妨／防」这类形近字更稳；代价是像素量变 4 倍、耗时上升。
         * 快速模型不做任何缩放，原尺寸直送。
         */
        private const val ACCURATE_MODEL_SCALE = 2f

        // 招募时间调节的节奏参数
        private const val TIME_WAIT_TIMEOUT_MS = 12_000L      // 等「招募时间」界面出现
        private const val TIME_ADJUST_TIMEOUT_MS = 20_000L    // 界面已出现后允许调整的时长
        private const val TIME_RETRY_DELAY_MS = 300L          // 还没读到数字时的重试间隔
        private const val TIME_AFTER_TAP_DELAY_MS = 500L      // 点一下之后等界面刷新的时间
        private const val TIME_PICK_SETTLE_MS = 600L          // 选完词条到时间页出现的缓冲
        private const val TIME_CAPTURE_TIMEOUT_MS = 800L      // 单次截图取帧超时

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
