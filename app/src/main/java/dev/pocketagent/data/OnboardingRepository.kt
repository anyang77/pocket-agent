package dev.pocketagent.data

import android.content.Context

class OnboardingRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pocket_agent_onboarding", Context.MODE_PRIVATE)

    fun isCompleted(): Boolean = preferences.getBoolean("completed", false)

    fun isWelcomeAccepted(): Boolean = preferences.getBoolean("welcome_read_v1", false)

    fun acceptWelcome() {
        preferences.edit().putBoolean("welcome_read_v1", true).apply()
    }

    fun complete() {
        preferences.edit().putBoolean("completed", true).apply()
    }
}
