package com.bgffw.tkfmtool.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 决定「要不要弹更新提示」的规则。 */
class UpdateCheckerTest {

    private fun info(version: String) = UpdateInfo(
        version = version,
        releaseUrl = "https://github.com/BGFFw/TKFMtool-Android/releases/tag/v$version",
        downloadUrl = null,
        notes = ""
    )

    @Test
    fun noInfoMeansNoPrompt() {
        assertFalse(UpdateChecker.shouldPrompt(null, "2.0.8-debug", null))
    }

    @Test
    fun sameVersionDoesNotPrompt() {
        assertFalse(UpdateChecker.shouldPrompt(info("2.0.8"), "2.0.8-debug", null))
    }

    @Test
    fun olderRemoteDoesNotPrompt() {
        assertFalse(UpdateChecker.shouldPrompt(info("2.0.7"), "2.0.8-debug", null))
    }

    @Test
    fun newerRemotePrompts() {
        assertTrue(UpdateChecker.shouldPrompt(info("2.0.9"), "2.0.8-debug", null))
    }

    @Test
    fun skippedVersionIsNotPromptedAgain() {
        assertFalse(UpdateChecker.shouldPrompt(info("2.0.9"), "2.0.8-debug", "2.0.9"))
    }

    @Test
    fun skipOnlyAppliesToThatVersion() {
        // 跳过的是 2.0.9，现在远端出了 2.1.0，仍应提示
        assertTrue(UpdateChecker.shouldPrompt(info("2.1.0"), "2.0.8-debug", "2.0.9"))
    }

    @Test
    fun skipIsVersionAwareNotStringAware() {
        // 跳过记录写了 v 前缀也必须认得出来
        assertFalse(UpdateChecker.shouldPrompt(info("2.0.9"), "2.0.8-debug", "v2.0.9"))
    }
}
