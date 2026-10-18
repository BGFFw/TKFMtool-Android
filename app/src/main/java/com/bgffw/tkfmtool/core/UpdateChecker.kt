package com.bgffw.tkfmtool.core

import android.util.Log
import com.bgffw.tkfmtool.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 一次「检查更新」拿到的信息。 */
data class UpdateInfo(
    val version: String,
    val releaseUrl: String,
    val downloadUrl: String?,
    val notes: String
)

/**
 * 通过 GitHub Releases 判断有没有新版本。
 *
 * 用的是公开 API，无需 token；未认证请求每小时 60 次，
 * 启动时查一次 + 手动查询，完全够用。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    const val REPO = "BGFFw/TKFMtool-Android"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /**
     * 查询最新 Release。
     *
     * @return 成功时给出 [UpdateInfo]；网络或解析失败返回 failure
     */
    suspend fun check(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub API 强制要求 User-Agent
                setRequestProperty("User-Agent", "TKFMtool/${BuildConfig.VERSION_NAME}")
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "check update http $code")
                return@withContext Result.failure(IOException("HTTP $code"))
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tag = json.optString("tag_name")
            val htmlUrl = json.optString("html_url").ifEmpty { RELEASES_PAGE }
            val notes = json.optString("body")

            var downloadUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null && assets.length() > 0) {
                val first = assets.optJSONObject(0)
                val url = first?.optString("browser_download_url").orEmpty()
                if (url.isNotEmpty()) downloadUrl = url
            }

            Result.success(
                UpdateInfo(
                    version = AppVersion.normalize(tag),
                    releaseUrl = htmlUrl,
                    downloadUrl = downloadUrl,
                    notes = notes
                )
            ).also {
                Log.d(TAG, "latest release tag='$tag' normalized='${AppVersion.normalize(tag)}'")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "check update failed: ${t.message}")
            Result.failure(t)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * 是否应该弹窗提示。
     *
     * 纯函数，方便单测：
     * * 没有新版本 → 不提示
     * * 用户已经「跳过此版本」→ 不提示
     * * 跳过的是更旧的版本，而现在有更新的版本 → 仍然提示
     */
    fun shouldPrompt(info: UpdateInfo?, localVersion: String, skippedVersion: String?): Boolean {
        if (info == null) return false
        if (!AppVersion.isNewer(info.version, localVersion)) return false
        if (!skippedVersion.isNullOrBlank() && AppVersion.compare(skippedVersion, info.version) == 0) {
            return false
        }
        return true
    }
}
