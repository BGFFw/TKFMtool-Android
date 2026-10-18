package com.bgffw.tkfmtool.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bgffw.tkfmtool.R
import com.bgffw.tkfmtool.core.TagMatcher
import com.bgffw.tkfmtool.util.dp
import com.bgffw.tkfmtool.util.overlayWindowType

/**
 * 识图结果面板。
 *
 * 对应原版 `tags_show.js` 里的 floaty 窗口：
 * 覆盖屏幕下半部分，表头「选择 | 角色清单」，每行一个词条组合，
 * 2☆ 行标红、3☆ 行标金，点击某行即按该组合自动选人。
 *
 * 相比原版的两处改动：
 * * 复用一个窗口（原版每次识图都新开一个 floaty 窗口，会层层叠加）
 * * 点击某行后自动收起面板，避免遮挡游戏和被重复点击
 */
class ResultPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onPick: (TagMatcher) -> Unit
) {

    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean
        get() = root != null

    fun show(matchers: List<TagMatcher>, detected: List<String>) {
        dismiss()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.setBackgroundColor(ContextCompat.getColor(context, R.color.panel_bg))

        container.addView(
            buildTopBar(detected),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        container.addView(
            buildTable(matchers),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                9f
            )
        )

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (screenHeight / 2).coerceAtLeast(context.dp(220)),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenHeight / 2
        }

        try {
            windowManager.addView(container, layoutParams)
            root = container
            params = layoutParams
        } catch (t: Throwable) {
            root = null
            params = null
        }
    }

    fun dismiss() {
        val view = root ?: return
        root = null
        params = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun buildTopBar(detected: List<String>): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
        }

        val tip = TextView(context).apply {
            text = if (detected.isEmpty()) {
                context.getString(R.string.panel_title)
            } else {
                context.getString(R.string.panel_title) + "：" + detected.joinToString(" / ")
            }
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(tip)

        val hint = TextView(context).apply {
            text = context.getString(R.string.panel_tip)
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 11f
            includeFontPadding = false
            setPadding(0, 0, context.dp(10), 0)
        }
        bar.addView(hint)

        val close = TextView(context).apply {
            text = context.getString(R.string.panel_close)
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(14).toFloat()
                setColor(0x33FFFFFF)
                setStroke(context.dp(1), 0x99FFFFFF.toInt())
            }
            isClickable = true
            setOnClickListener { dismiss() }
        }
        bar.addView(close)

        return bar
    }

    private fun buildTable(matchers: List<TagMatcher>): View {
        val table = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (matchers.isEmpty()) {
            table.addView(
                TextView(context).apply {
                    text = context.getString(R.string.panel_empty)
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(context.dp(16), context.dp(24), context.dp(16), context.dp(24))
                }
            )
        } else {
            table.addView(buildHeaderRow())
            table.addView(horizontalDivider())
            matchers.forEachIndexed { index, matcher ->
                table.addView(buildRow(matcher, index))
                table.addView(horizontalDivider())
            }
        }

        return ScrollView(context).apply {
            isFillViewport = false
            addView(table)
        }
    }

    private fun buildHeaderRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.panel_header))
        }
        row.addView(
            makeCell(
                text = context.getString(R.string.panel_col_pick),
                weight = 1f,
                bold = true,
                center = true
            )
        )
        row.addView(verticalDivider())
        row.addView(
            makeCell(
                text = context.getString(R.string.panel_col_match),
                weight = 3f,
                bold = true,
                center = true
            )
        )
        return row
    }

    private fun buildRow(matcher: TagMatcher, index: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isClickable = true
            if (index % 2 == 1) setBackgroundColor(0x14FFFFFF)
            setOnClickListener {
                // 先收起面板再执行，避免遮挡游戏和被连点
                dismiss()
                onPick(matcher)
            }
        }

        row.addView(makeCell(matcher.tags.joinToString("\n"), 1f, center = false))
        row.addView(verticalDivider())
        row.addView(makeCell(buildMatchesText(matcher), 3f, center = false))

        return row
    }

    private fun makeCell(
        text: CharSequence,
        weight: Float,
        bold: Boolean = false,
        center: Boolean = false
    ): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = if (center) Gravity.CENTER else Gravity.START
        includeFontPadding = false
        setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            weight
        )
    }

    /**
     * 原版把匹配到的角色行直接 `join("\n")` 出来，行内用逗号连接整行字段，
     * 然后给以 `2☆` / `3☆` 开头的行整行染色。这里保持一致。
     */
    private fun buildMatchesText(matcher: TagMatcher): CharSequence {
        val text = matcher.matches.joinToString("\n") { it.joinToString(",") } + "\n"
        if (text.isBlank()) return text

        val spannable = SpannableString(text)
        val colorStar2 = ContextCompat.getColor(context, R.color.star2)
        val colorStar3 = ContextCompat.getColor(context, R.color.star3)

        var lineStart = 0
        for (i in text.indices) {
            if (text[i] != '\n') continue
            val line = text.substring(lineStart, i)
            val color = when {
                line.startsWith("2☆") -> colorStar2
                line.startsWith("3☆") -> colorStar3
                else -> null
            }
            if (color != null) {
                spannable.setSpan(
                    ForegroundColorSpan(color),
                    lineStart,
                    i + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            lineStart = i + 1
        }
        return spannable
    }

    private fun verticalDivider(): View = View(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.panel_divider))
        layoutParams = LinearLayout.LayoutParams(context.dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
    }

    private fun horizontalDivider(): View = View(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.panel_divider))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1))
    }
}

