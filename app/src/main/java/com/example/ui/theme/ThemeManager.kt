package com.example.ui.theme

import android.content.Context
import com.example.R

enum class AppThemeMode(val key: String, val titleRes: Int) {
    SYSTEM("system", R.string.theme_system),
    LIGHT("light", R.string.theme_light),
    DARK("dark", R.string.theme_dark);

    companion object {
        fun fromKey(key: String): AppThemeMode {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: SYSTEM
        }
    }
}

object ThemeManager {
    private const val PREFS_NAME = "net_manager_prefs"
    private const val KEY_THEME = "app_theme_mode"

    fun getSavedThemeMode(context: Context): AppThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_THEME, AppThemeMode.SYSTEM.key) ?: AppThemeMode.SYSTEM.key
        return AppThemeMode.fromKey(key)
    }

    fun saveThemeMode(context: Context, themeMode: AppThemeMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME, themeMode.key).apply()
    }
}
