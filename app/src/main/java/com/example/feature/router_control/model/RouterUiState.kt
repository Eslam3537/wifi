package com.example.feature.router_control.model

sealed interface RouterUiState {
    data object Idle : RouterUiState
    data class Loading(val message: String) : RouterUiState
    data class Connected(
        val status: RouterStatusInfo,
        val devices: List<RouterConnectedDevice>,
        val isPerformingAction: Boolean = false,
        val actionFeedback: String? = null
    ) : RouterUiState
    data class Error(
        val message: String,
        val canRetry: Boolean = true,
        val isWifiDisconnected: Boolean = false
    ) : RouterUiState
}
