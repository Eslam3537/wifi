package com.example.feature.router_control.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.update.AppReleaseInfo
import com.example.domain.update.AppUpdateManager
import com.example.domain.update.AppUpdateState
import com.example.feature.router_control.data.RouterRepository
import com.example.feature.router_control.data.RouterRepositoryImpl
import com.example.feature.router_control.model.RouterConnectedDevice
import com.example.feature.router_control.model.RouterCredentials
import com.example.feature.router_control.model.RouterUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RouterControlViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: RouterRepository = RouterRepositoryImpl(application),
    val appUpdateManager: AppUpdateManager = AppUpdateManager(application)
) : AndroidViewModel(application) {

    companion object {
        fun provideFactory(
            application: Application,
            repository: RouterRepository? = null,
            updateManager: AppUpdateManager? = null
        ): androidx.lifecycle.ViewModelProvider.Factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                val repo = repository ?: RouterRepositoryImpl(application)
                val mgr = updateManager ?: AppUpdateManager(application)
                return RouterControlViewModel(application, repo, mgr) as T
            }
        }
    }

    val uiState: StateFlow<RouterUiState> = repository.uiState
    val appUpdateState: StateFlow<AppUpdateState> = appUpdateManager.updateState

    private val _credentialsInput = MutableStateFlow(
        repository.getSavedCredentials() ?: RouterCredentials()
    )
    val credentialsInput: StateFlow<RouterCredentials> = _credentialsInput.asStateFlow()

    private val _showPassword = MutableStateFlow(false)
    val showPassword: StateFlow<Boolean> = _showPassword.asStateFlow()

    private val _userFeedbackMessage = MutableStateFlow<String?>(null)
    val userFeedbackMessage: StateFlow<String?> = _userFeedbackMessage.asStateFlow()

    init {
        // Auto-fill or probe if credentials previously saved
        val saved = repository.getSavedCredentials()
        if (saved != null) {
            _credentialsInput.value = saved
        }
    }

    fun updateGatewayIp(ip: String) {
        _credentialsInput.value = _credentialsInput.value.copy(gatewayIp = ip)
    }

    fun updateUsername(user: String) {
        _credentialsInput.value = _credentialsInput.value.copy(username = user)
    }

    fun updatePassword(pass: String) {
        _credentialsInput.value = _credentialsInput.value.copy(password = pass)
    }

    fun toggleRemember(remember: Boolean) {
        _credentialsInput.value = _credentialsInput.value.copy(remember = remember)
    }

    fun toggleShowPassword() {
        _showPassword.value = !_showPassword.value
    }

    fun login() {
        val creds = _credentialsInput.value
        viewModelScope.launch {
            repository.login(creds)
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            repository.refreshData()
        }
    }

    fun restartRouter() {
        viewModelScope.launch {
            repository.restartRouter()
        }
    }

    fun changeWifiPassword(newPassword: String, newSsid: String? = null) {
        viewModelScope.launch {
            repository.changeWifiPassword(newPassword, newSsid)
        }
    }

    fun toggleDeviceBlock(device: RouterConnectedDevice) {
        viewModelScope.launch {
            repository.toggleDeviceBlock(device)
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
        }
    }

    /**
     * Requirement: "عايزك تضيف زياده التحقق من تحديث التطبيق"
     * Triggers an in-app check for new GitHub releases / APK updates.
     */
    fun checkForAppUpdates() {
        viewModelScope.launch {
            _userFeedbackMessage.value = "جاري التحقق من وجود تحديثات جديدة للتطبيق…"
            val result = appUpdateManager.checkForUpdates(silent = false)
            result.onSuccess { release ->
                if (release == null) {
                    _userFeedbackMessage.value = "أنت تستخدم أحدث إصدار من التطبيق (${appUpdateManager.currentVersionName})"
                } else {
                    _userFeedbackMessage.value = "يوجد تحديث جديد: ${release.tagName}"
                }
            }.onFailure { err ->
                _userFeedbackMessage.value = "فشل التحقق من التحديث: ${err.message}"
            }
        }
    }

    fun clearFeedbackMessage() {
        _userFeedbackMessage.value = null
    }

    fun downloadAppUpdate(url: String) {
        viewModelScope.launch {
            appUpdateManager.downloadUpdate(url)
        }
    }

    fun installAppUpdate(file: java.io.File) {
        appUpdateManager.installApk(file)
    }

    fun dismissAppUpdateDialog() {
        appUpdateManager.resetState()
    }

    fun canRequestPackageInstalls(): Boolean = appUpdateManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        appUpdateManager.openInstallPermissionSettings()
    }
}
