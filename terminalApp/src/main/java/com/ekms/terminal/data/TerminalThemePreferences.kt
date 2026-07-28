package com.ekms.terminal.data

import android.content.Context
import android.content.SharedPreferences

/**
 * SYSTEM is the first-launch default (follows the device's dark/light setting); LIGHT/DARK
 * are an explicit local override once the operator has touched the toggle at least once.
 */
enum class TerminalThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromStored(value: String?): TerminalThemeMode =
            entries.find { it.name == value } ?: SYSTEM
    }
}

/**
 * Device-local dark/light preference (Phase 9 design-system rework) — same
 * SharedPreferences-per-concern pattern as [TerminalApiClient]'s own base-URL/token
 * storage. Deliberately not one of [com.ekms.shared.api.CabinetSettingsDto]'s five
 * backend-synced fields: this is purely a per-device display preference, same
 * local-only footing as serverAddress/activationCode.
 */
class TerminalThemePreferences(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: TerminalThemeMode
        get() = TerminalThemeMode.fromStored(preferences.getString(KEY_MODE, null))
        set(value) {
            preferences.edit().putString(KEY_MODE, value.name).apply()
        }

    private companion object {
        const val PREFS_NAME = "ekms_terminal_theme"
        const val KEY_MODE = "mode"
    }
}
