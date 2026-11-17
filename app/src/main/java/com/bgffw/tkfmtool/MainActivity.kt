package com.bgffw.tkfmtool

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bgffw.tkfmtool.accessibility.TkfmAccessibilityService
import com.bgffw.tkfmtool.core.AppVersion
import com.bgffw.tkfmtool.core.CharacterDatabase
import com.bgffw.tkfmtool.core.SettingsRepository
import com.bgffw.tkfmtool.core.UpdateChecker
import com.bgffw.tkfmtool.core.UpdateInfo
import com.bgffw.tkfmtool.service.FloatingService
import com.bgffw.tkfmtool.ui.UserCorrectActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * 启动页：授权引导 + 启动/停止助手 + 检查更新。
 *
 * 原版由 AutoX.js 负责申请截图权限（`requestScreenCapture()`），
 * 原生版改成标准的 MediaProjection 授权流程；
 * 悬浮窗与无障碍同样需要用户手动开启，这里做了状态展示与一键跳转。
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        private const val UPDATE_TAG = "UpdateCheck"
        private const val DIRECT_TAG = "LaunchDirect"
    }

    private lateinit var settings: SettingsRepository
    private lateinit var tvOverlay: TextView
    private lateinit var tvA11y: TextView
    private lateinit var tvNotif: TextView

    /**
     * 冷启动时是否要「直接打开悬浮窗」。
     *
     * 录屏授权成功后据此直接关掉首页；一旦给出结果就消费掉，
     * 避免后续正常从首页启动时被误关。
     */
    private var directLaunchRequested = false

    /** 已经拉起过系统录屏弹窗，避免 onResume 重复触发。 */
    private var captureRequested = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        captureRequested = false
        val data: Intent? = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            FloatingService.start(this, result.resultCode, data)
            Toast.makeText(this, R.string.hint_running, Toast.LENGTH_SHORT).show()
            if (directLaunchRequested) {
                // 直接启动模式：悬浮窗已经起来了，首页没必要再露出来
                Log.d(DIRECT_TAG, "capture granted, start service and close home")
                directLaunchRequested = false
                finish()
            } else {
                refreshState()
            }
        } else {
            Toast.makeText(this, R.string.hint_capture_denied, Toast.LENGTH_SHORT).show()
            // 直接启动模式下被拒绝也不停在首页，交给用户自己决定要不要再点一次
            if (directLaunchRequested) {
                Log.d(DIRECT_TAG, "capture denied, close home")
                directLaunchRequested = false
                finish()
            } else {
                refreshState()
            }
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        CharacterDatabase.load(this)
        settings = SettingsRepository(this)

        tvOverlay = findViewById(R.id.tvOverlay)
        tvA11y = findViewById(R.id.tvA11y)
        tvNotif = findViewById(R.id.tvNotif)

        findViewById<View>(R.id.rowOverlay).setOnClickListener { openOverlaySettings() }
        findViewById<View>(R.id.rowA11y).setOnClickListener { openAccessibilitySettings() }
        findViewById<View>(R.id.rowNotif).setOnClickListener { requestNotification() }

        findViewById<View>(R.id.btnStart).setOnClickListener { startAssistant() }
        findViewById<View>(R.id.btnStop).setOnClickListener {
            FloatingService.stop(this)
            Toast.makeText(this, R.string.hint_stopped, Toast.LENGTH_SHORT).show()
            refreshState()
        }
        findViewById<View>(R.id.btnCorrect).setOnClickListener {
            startActivity(Intent(this, UserCorrectActivity::class.java))
        }
        findViewById<View>(R.id.btnCheckUpdate).setOnClickListener { checkUpdate(manual = true) }

        // 只在真正冷启动时自动查一次；从设置页返回等重建不再打扰
        if (savedInstanceState == null) {
            checkUpdate(manual = false)

            // 设置里开了「启动应用时直接打开悬浮窗」就不在首页停留，
            // 直接把录屏授权弹出来；授权通过后起悬浮窗并关掉首页。
            if (settings.launchDirect) {
                directLaunchRequested = true
                tryDirectLaunch()
            }
        }
    }

    // ---------------------------------------------------------------- 检查更新

    /**
     * @param manual 用户主动点「检查更新」：无论结果如何都给出反馈；
     *               启动时的自动检查只在确实有新版本且未被跳过时才弹窗
     */
    private fun checkUpdate(manual: Boolean) {
        if (manual) {
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            val info = UpdateChecker.check().getOrNull()
            val localVersion = BuildConfig.VERSION_NAME

            if (info == null) {
                Log.d(UPDATE_TAG, "update check failed")
                if (manual) {
                    Toast.makeText(this@MainActivity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            if (!AppVersion.isNewer(info.version, localVersion)) {
                Log.d(
                    UPDATE_TAG,
                    "no update: local=$localVersion remote=${info.version}"
                )
                if (manual) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_up_to_date, AppVersion.normalize(localVersion)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            Log.d(
                UPDATE_TAG,
                "update found: local=$localVersion remote=${info.version} " +
                    "manual=$manual skipped=${settings.skippedUpdateVersion}"
            )

            if (!manual && !UpdateChecker.shouldPrompt(info, localVersion, settings.skippedUpdateVersion)) {
                Log.d(UPDATE_TAG, "prompt suppressed (version skipped)")
                return@launch
            }

            showUpdateDialog(info, localVersion)
        }
    }

    private fun showUpdateDialog(info: UpdateInfo, localVersion: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_title, info.version))
            .setMessage(
                getString(
                    R.string.update_message,
                    AppVersion.normalize(localVersion),
                    info.version
                )
            )
            .setPositiveButton(R.string.update_now) { _, _ -> openRelease(info) }
            .setNeutralButton(R.string.update_skip_version) { _, _ ->
                // 记下这个版本，之后启动不再为它弹窗；出更新的版本仍会提示
                settings.skippedUpdateVersion = info.version
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun openRelease(info: UpdateInfo) {
        val url = info.releaseUrl.ifBlank { UpdateChecker.RELEASES_PAGE }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()

        // 去权限设置页兜一圈回来：条件齐了就把直接启动接着走完，
        // 用户不用回到首页再点一次「启动悬浮窗」。
        if (directLaunchRequested &&
            !captureRequested &&
            !isFinishing &&
            Settings.canDrawOverlays(this) &&
            TkfmAccessibilityService.isRunning()
        ) {
            requestCapture()
        }
    }

    private fun startAssistant() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.hint_need_overlay, Toast.LENGTH_SHORT).show()
            openOverlaySettings()
            return
        }

        if (!TkfmAccessibilityService.isRunning()) {
            promptAccessibility()
            return
        }

        requestCapture()
    }

    /**
     * 冷启动「直接打开悬浮窗」。
     *
     * 三个前置条件缺一不可，任何一个不满足就先补权限，补完在 [onResume] 里接着走：
     * * 悬浮窗权限没给 —— 按钮都挂不上，跳到授权页；
     * * 无障碍没开 —— 能识图但点不动，问一句「去开启 / 仍然启动」；
     * * 录屏授权 —— 只能由 Activity 发起，结果回到 [projectionLauncher]。
     *
     * 三者齐全时用户只会看到一次系统录屏弹窗，然后就是悬浮窗，全程不经过首页。
     */
    private fun tryDirectLaunch() {
        // 悬浮窗已经在跑：不必再要一次录屏授权，直接把首页收掉
        if (FloatingService.running) {
            Log.d(DIRECT_TAG, "already running, close home")
            directLaunchRequested = false
            finish()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.d(DIRECT_TAG, "overlay missing, fall back to home")
            Toast.makeText(this, R.string.hint_need_overlay, Toast.LENGTH_SHORT).show()
            openOverlaySettings()
            return
        }

        if (!TkfmAccessibilityService.isRunning()) {
            Log.d(DIRECT_TAG, "accessibility missing, prompt")
            promptAccessibility()
            return
        }

        Log.d(DIRECT_TAG, "all conditions met, requesting capture")
        requestCapture()
    }

    private fun promptAccessibility() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hint_need_accessibility_title)
            .setMessage(R.string.hint_need_accessibility_message)
            .setPositiveButton(R.string.hint_go_settings) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(R.string.hint_continue_anyway) { _, _ -> requestCapture() }
            .show()
    }

    private fun requestCapture() {
        if (captureRequested) return

        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Toast.makeText(this, R.string.toast_capture_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            captureRequested = true
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } catch (t: Throwable) {
            captureRequested = false
            Toast.makeText(this, R.string.toast_capture_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshState() {
        setState(tvOverlay, Settings.canDrawOverlays(this))
        setState(tvA11y, TkfmAccessibilityService.isRunning())
        setState(tvNotif, NotificationManagerCompat.from(this).areNotificationsEnabled())
    }

    private fun setState(view: TextView, granted: Boolean) {
        view.setText(if (granted) R.string.state_granted else R.string.state_denied)
        view.setTextColor(ContextCompat.getColor(this, if (granted) R.color.ok else R.color.warn))
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            openAppNotificationSettings()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            openAppNotificationSettings()
        } else {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        runCatching { startActivity(intent) }
    }
}
