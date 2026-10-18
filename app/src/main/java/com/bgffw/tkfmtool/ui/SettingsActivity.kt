package com.bgffw.tkfmtool.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bgffw.tkfmtool.R
import com.bgffw.tkfmtool.core.SettingsRepository
import com.bgffw.tkfmtool.service.FloatingService
import com.bgffw.tkfmtool.util.convertToChineseNumber
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 长按悬浮按钮弹出的设置菜单。
 *
 * 对应原版 `run.js` 里 `dialogs.select("设置", [...9 项...])` 那一整套级联对话框。
 * 原版是在 Service 里直接弹 AutoX 的悬浮对话框；这里改成一个透明 Activity，
 * 避免 Service 弹窗在不同 ROM 上被拦（原版 README 里 MIUI 闪退就是这个原因）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)
        showMainMenu()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showMainMenu()
    }

    private fun showMainMenu() {
        val items = arrayOf(
            getString(R.string.settings_mode),
            getString(R.string.settings_autotime),
            getString(R.string.settings_time),
            getString(R.string.settings_ocr),
            getString(R.string.settings_smart_correct),
            getString(R.string.settings_correct),
            getString(R.string.settings_last),
            getString(R.string.settings_about),
            getString(R.string.settings_exit)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setItems(items) { _, which -> onMainItem(which) }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun onMainItem(index: Int) {
        when (index) {
            0 -> showModeMenu()
            1 -> showAutoTimeMenu()
            2 -> showTimeMenu()
            3 -> showOcrMenu()
            4 -> showSmartCorrectMenu()
            5 -> {
                startActivity(Intent(this, UserCorrectActivity::class.java))
                finish()
            }
            6 -> showLastResult()
            7 -> showAbout()
            8 -> exitApp()
            else -> finish()
        }
    }

    private fun showModeMenu() {
        val options = arrayOf(
            getString(R.string.mode_0),
            getString(R.string.mode_1),
            getString(R.string.mode_2)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(
                getString(
                    R.string.settings_mode_title,
                    convertToChineseNumber(settings.mode + 1)
                )
            )
            .setSingleChoiceItems(options, settings.mode) { dialog, which ->
                settings.mode = which
                dialog.dismiss()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showAutoTimeMenu() {
        val options = arrayOf(
            getString(R.string.on_off_on),
            getString(R.string.on_off_off)
        )
        val checked = if (settings.autoSetTime) 0 else 1
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_autotime)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                settings.autoSetTime = which == 0
                dialog.dismiss()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showTimeMenu() {
        val labels = (1..9).map { it.toString() }.toTypedArray()
        val checked = ((settings.noneSrTime.toIntOrNull() ?: 9) - 1)
            .coerceIn(0, labels.size - 1)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.time_none_sr)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.noneSrTime = (which + 1).toString()
                dialog.dismiss()
                showHaveSrTimeMenu()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showHaveSrTimeMenu() {
        val labels = (1..9).map { it.toString() }.toTypedArray()
        val checked = ((settings.haveSrTime.toIntOrNull() ?: 9) - 1)
            .coerceIn(0, labels.size - 1)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.time_have_sr)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.haveSrTime = (which + 1).toString()
                dialog.dismiss()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * 原生版新增：ML Kit 不接管线程数，原版的「CPU 核心数目」在这里没有意义，
     * 已整项移除，改由「词表智能纠正」提供实际可用的识图调节。
     */
    private fun showSmartCorrectMenu() {
        val options = arrayOf(
            getString(R.string.on_off_on),
            getString(R.string.on_off_off)
        )
        val checked = if (settings.smartCorrect) 0 else 1
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_smart_correct)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                settings.smartCorrect = which == 0
                dialog.dismiss()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showOcrMenu() {
        val options = arrayOf(
            getString(R.string.ocr_accurate),
            getString(R.string.ocr_fast)
        )
        val checked = if (settings.ocrAccurate) 0 else 1

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_ocr)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                settings.ocrAccurate = which == 0
                dialog.dismiss()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showLastResult() {
        val result = settings.lastResult
        val message = if (result.isEmpty()) {
            getString(R.string.last_result_empty)
        } else {
            getString(R.string.last_result_body, result.joinToString(", "))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_last)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_about)
            .setMessage(R.string.about_message)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun exitApp() {
        FloatingService.stop(this)
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
