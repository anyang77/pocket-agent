package dev.pocketagent.data

import android.content.Context
import dev.pocketagent.ui.theme.ThemePreference

class AppearanceRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pocket_agent_appearance", Context.MODE_PRIVATE)

    fun loadThemePreference(): ThemePreference = ThemePreference.fromStorage(
        preferences.getString("theme_preference", null),
    )

    fun saveThemePreference(preference: ThemePreference) {
        preferences.edit().putString("theme_preference", preference.name).apply()
    }
}
