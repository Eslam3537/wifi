package com.example

import com.example.ui.theme.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {

    @Test
    fun testThemeModeEnumParsing() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromKey("system"))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromKey("light"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromKey("dark"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromKey("unknown_value"))
    }

    @Test
    fun testThemeModeCycle() {
        fun nextMode(current: AppThemeMode): AppThemeMode = when (current) {
            AppThemeMode.SYSTEM -> AppThemeMode.LIGHT
            AppThemeMode.LIGHT -> AppThemeMode.DARK
            AppThemeMode.DARK -> AppThemeMode.SYSTEM
        }

        var mode = AppThemeMode.SYSTEM
        mode = nextMode(mode)
        assertEquals(AppThemeMode.LIGHT, mode)
        mode = nextMode(mode)
        assertEquals(AppThemeMode.DARK, mode)
        mode = nextMode(mode)
        assertEquals(AppThemeMode.SYSTEM, mode)
    }
}
