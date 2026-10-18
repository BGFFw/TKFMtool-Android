package com.bgffw.tkfmtool.core

import com.bgffw.tkfmtool.accessibility.TkfmAccessibilityService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 串行化的点击器。
 *
 * `dispatchGesture` 同一时刻只允许一个手势，所以这里用互斥锁排队，
 * 并等上一次手势真正结束后再派发下一次。原版用的是阻塞式 `press()`，效果一致。
 */
object Clicker {

    private val mutex = Mutex()

    /** 一次按压的默认时长，对应原版 `press(x, y, 50)` 的 50 毫秒。 */
    const val DEFAULT_PRESS_MS = 50L

    fun isAvailable(): Boolean = TkfmAccessibilityService.isRunning()

    suspend fun tap(x: Float, y: Float, durationMs: Long = DEFAULT_PRESS_MS): Boolean {
        val service = TkfmAccessibilityService.instance ?: return false

        return mutex.withLock {
            suspendCancellableCoroutine { cont ->
                val queued = service.performTap(x, y, durationMs) {
                    if (cont.isActive) cont.resume(true)
                }
                if (!queued && cont.isActive) cont.resume(false)
            }
        }
    }
}
