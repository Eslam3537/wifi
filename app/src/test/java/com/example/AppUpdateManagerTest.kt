package com.example

import com.example.domain.update.AppReleaseInfo
import com.example.domain.update.AppUpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppUpdateManagerTest {

    @Test
    fun testAppReleaseInfoCreation() {
        val info = AppReleaseInfo(
            tagName = "debug-apk-build-10-1",
            title = "Debug APK Build #10",
            notes = "Added in-app update feature",
            downloadUrl = "https://github.com/Eslam3537/wifi/releases/latest/download/app-debug.apk",
            sizeBytes = 15_000_000L,
            publishedAt = "2026-09-23T10:00:00Z",
            isNewer = true
        )

        assertEquals("debug-apk-build-10-1", info.tagName)
        assertEquals("Debug APK Build #10", info.title)
        assertTrue(info.isNewer)
        assertEquals(15_000_000L, info.sizeBytes)
    }

    @Test
    fun testUpdateStates() {
        val idle = AppUpdateState.Idle
        val checking = AppUpdateState.Checking
        val upToDate = AppUpdateState.UpToDate
        val downloading = AppUpdateState.Downloading(progress = 0.75f, downloadedBytes = 750L, totalBytes = 1000L)
        val ready = AppUpdateState.ReadyToInstall(File("/fake/path/app-debug.apk"))
        val error = AppUpdateState.Error("Network failure")

        assertTrue(idle is AppUpdateState.Idle)
        assertTrue(checking is AppUpdateState.Checking)
        assertTrue(upToDate is AppUpdateState.UpToDate)
        assertEquals(0.75f, downloading.progress, 0.001f)
        assertEquals(750L, downloading.downloadedBytes)
        assertEquals("Network failure", error.message)
    }
}
