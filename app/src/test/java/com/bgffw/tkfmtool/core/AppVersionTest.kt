package com.bgffw.tkfmtool.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号比较。
 *
 * 关键点：debug 包的 versionName 是 `2.0.8-debug`，而 GitHub tag 是 `v2.0.8`，
 * 两者必须判为同一个版本，否则每次启动都会误报更新。
 */
class AppVersionTest {

    @Test
    fun normalizeStripsPrefixAndSuffix() {
        assertEquals("2.0.8", AppVersion.normalize("v2.0.8"))
        assertEquals("2.0.8", AppVersion.normalize("2.0.8-debug"))
        assertEquals("2.0.8", AppVersion.normalize("V2.0.8-DEBUG"))
        assertEquals("2.0.8", AppVersion.normalize("  v2.0.8  "))
        assertEquals("", AppVersion.normalize(null))
        assertEquals("", AppVersion.normalize("   "))
    }

    @Test
    fun parseHandlesValidAndInvalid() {
        assertEquals(listOf(2, 0, 8), AppVersion.parse("2.0.8"))
        assertEquals(listOf(2, 0, 8), AppVersion.parse("v2.0.8-debug"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("abc"))
        assertNull(AppVersion.parse("2"))
        assertNull(AppVersion.parse("2.x.8"))
    }

    @Test
    fun debugBuildIsNotSeenAsNewerOrOlder() {
        // 本地 2.0.8-debug 与远端 v2.0.8 视为同一版，不应提示更新
        assertEquals(0, AppVersion.compare("v2.0.8", "2.0.8-debug"))
        assertFalse(AppVersion.isNewer("v2.0.8", "2.0.8-debug"))
    }

    @Test
    fun newerAndOlderAreDetected() {
        assertTrue(AppVersion.isNewer("v2.1.0", "2.0.8-debug"))
        assertTrue(AppVersion.isNewer("v2.0.10", "2.0.9"))
        assertFalse(AppVersion.isNewer("v2.0.7", "2.0.8-debug"))
        assertFalse(AppVersion.isNewer("v2.0.8", "2.0.8-debug"))
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertEquals(0, AppVersion.compare("2.0", "2.0.0"))
        assertTrue(AppVersion.isNewer("2.0.1", "2.0"))
    }

    @Test
    fun unparsableVersionsNeverReportUpdate() {
        assertEquals(0, AppVersion.compare("abc", "2.0.8"))
        assertEquals(0, AppVersion.compare("2.0.8", ""))
        assertFalse(AppVersion.isNewer("abc", "2.0.8"))
    }
}
