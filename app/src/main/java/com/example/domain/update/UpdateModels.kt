package com.example.domain.update

import java.io.File

data class AppReleaseInfo(
    val tagName: String,
    val title: String,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val publishedAt: String,
    val remoteVersionCode: Int,
    val remoteVersionName: String,
    val isNewer: Boolean
)

sealed interface AppUpdateState {
    object Idle : AppUpdateState
    object Checking : AppUpdateState
    data class UpdateAvailable(val release: AppReleaseInfo) : AppUpdateState
    object UpToDate : AppUpdateState
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : AppUpdateState
    data class ReadyToInstall(val apkFile: File) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

