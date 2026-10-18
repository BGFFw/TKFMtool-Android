package com.bgffw.tkfmtool.core

import kotlin.math.roundToInt

/** 一个整数像素矩形（避免依赖 android.graphics，便于单元测试）。 */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 坐标映射。
 *
 * 原版把 1080×2400 当成设计分辨率，用 `device.width / 1080` 与
 * `device.height / 2400` 两个比例把所有写死的坐标等比缩放到当前机器上。
 * 这里保持完全相同的做法，于是同一套常量在任意分辨率上都能对齐。
 */
object Geometry {

    const val DESIGN_WIDTH = 1080f
    const val DESIGN_HEIGHT = 2400f

    // 词条区域（识图裁剪范围），来自原版 captureAndOcr()
    const val TAG_LEFT = 42f
    const val TAG_TOP = 833f
    const val TAG_RIGHT = 1033f
    const val TAG_BOTTOM = 1137f

    // 招募时间数字区域（updateTime 的裁剪范围）
    const val TIME_LEFT = 55f
    const val TIME_TOP = 436f
    const val TIME_RIGHT = 396f
    const val TIME_BOTTOM = 724f

    // 招募时间调节按钮（keepTime 里 press 的坐标）
    const val TIME_BUTTON_X = 229.6f
    const val TIME_BUTTON_Y = 671.7f

    class Mapper(val screenWidth: Int, val screenHeight: Int) {

        val scaleX: Float = if (screenWidth > 0) screenWidth / DESIGN_WIDTH else 1f
        val scaleY: Float = if (screenHeight > 0) screenHeight / DESIGN_HEIGHT else 1f

        fun tagRect(): PixelRect = PixelRect(
            (TAG_LEFT * scaleX).roundToInt(),
            (TAG_TOP * scaleY).roundToInt(),
            (TAG_RIGHT * scaleX).roundToInt(),
            (TAG_BOTTOM * scaleY).roundToInt()
        )

        fun timeRect(): PixelRect = PixelRect(
            (TIME_LEFT * scaleX).roundToInt(),
            (TIME_TOP * scaleY).roundToInt(),
            (TIME_RIGHT * scaleX).roundToInt(),
            (TIME_BOTTOM * scaleY).roundToInt()
        )

        fun timeButtonX(): Float = TIME_BUTTON_X * scaleX

        fun timeButtonY(): Float = TIME_BUTTON_Y * scaleY

        /**
         * 把「屏幕坐标矩形」换算到截图 bitmap 的坐标系。
         * MediaProjection 的输出一般就等于屏幕尺寸，这种情况下是恒等变换；
         * 少数机型/横竖屏切换时两者可能不等，这里做一次安全缩放。
         */
        fun toBitmapRect(rect: PixelRect, bitmapWidth: Int, bitmapHeight: Int): PixelRect {
            if (bitmapWidth <= 0 || bitmapHeight <= 0) return rect
            if (bitmapWidth == screenWidth && bitmapHeight == screenHeight) return rect

            val kx = bitmapWidth.toFloat() / screenWidth
            val ky = bitmapHeight.toFloat() / screenHeight

            val left = (rect.left * kx).roundToInt().coerceIn(0, bitmapWidth - 1)
            val top = (rect.top * ky).roundToInt().coerceIn(0, bitmapHeight - 1)
            val right = (rect.right * kx).roundToInt().coerceIn(left + 1, bitmapWidth)
            val bottom = (rect.bottom * ky).roundToInt().coerceIn(top + 1, bitmapHeight)
            return PixelRect(left, top, right, bottom)
        }
    }
}
