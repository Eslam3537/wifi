package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.domain.privilege.PermissionManager
import com.example.ui.LanguageManager
import com.example.ui.MainScreen
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        viewModel.onPermissionsResult(allGranted)
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        val lang = LanguageManager.getSavedLanguage(newBase)
        val localizedContext = LanguageManager.applyLocale(newBase, lang)
        super.attachBaseContext(localizedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val lang = LanguageManager.getSavedLanguage(this)
        LanguageManager.applyLocale(this, lang)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Automatically request all required permissions on initial launch
        requestAllRequiredPermissions()

        setContent {
            val themeMode by viewModel.currentThemeMode.collectAsState()
            MyApplicationTheme(themeMode = themeMode) {
                MainScreen(
                    viewModel = viewModel,
                    onRequestPermissions = { requestAllRequiredPermissions() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updatePermissionStatus()
    }

    private fun requestAllRequiredPermissions() {
        val missing = PermissionManager.getMissingRuntimePermissions(this)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        } else {
            viewModel.onPermissionsResult(true)
        }
    }
}

