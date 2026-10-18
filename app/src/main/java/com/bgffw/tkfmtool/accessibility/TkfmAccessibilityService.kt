package com.bgffw.tkfmtool.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 唯一的无障碍服务：负责把「屏幕坐标 + 按住时长」变成真实的手势。
 *
 * 等价于原版的 `press(x, y, 50)`（AutoX.js 的点击最终也走无障碍手势）。
 * 原版靠 `auto.waitFor()` 强制要求无障碍已开启，这里由服务实例是否存在来判定。
 */
class TkfmAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * 在 ([x], [y]) 处按 [durationMs] 毫秒。
     *
     * @param onFinished 手势结束（完成或被系统取消）后回调，用于串行化连续点击
     * @return 手势是否成功进入派发队列
     */
    fun performTap(
        x: Float,
        y: Float,
        durationMs: Long,
        onFinished: (() -> Unit)? = null
    ): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(1L, 60_000L)
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onFinished?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onFinished?.invoke()
            }
        }

        return try {
            dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            false
        }
    }

    companion object {
        @Volatile
        var instance: TkfmAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun performTapStatic(x: Float, y: Float, durationMs: Long): Boolean =
            instance?.performTap(x, y, durationMs) ?: false
    }
}
