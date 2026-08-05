package com.ekms.mobile.data

import android.content.Context
import android.content.SharedPreferences

/**
 * DARK is the first-launch default (no persisted preference yet, fresh install) — deliberately
 * not SYSTEM, per explicit design decision. SYSTEM/LIGHT are both still fully supported, explicit
 * user choices via the toggle; only the out-of-box default changed. An existing install that
 * already persisted a preference (including a prior SYSTEM default from before this change) is
 * unaffected — [fromStored] only falls back to this default when [preferences] has no stored
 * value at all (or a corrupted/unrecognized one, treated the same as "no valid preference").
 * Otherwise mirrors terminalApp's `TerminalThemeMode`/`TerminalThemePreferences` pattern exactly
 * (see `terminalApp/.../data/TerminalThemePreferences.kt`) — mobileApp UX rework Phase M-B.
 */
enum class MobileThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromStored(value: String?): MobileThemeMode =
            entries.find { it.name == value } ?: DARK
    }
}

/**
 * Device-local dark/light preference, same `SharedPreferences`-per-concern pattern as
 * [com.ekms.mobile.data.MobileApiClient]'s own token/profile storage. Purely a per-device
 * display preference — never backend-synced.
 */
class MobileThemePreferences(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: MobileThemeMode
        get() = MobileThemeMode.fromStored(preferences.getString(KEY_MODE, null))
        set(value) {
            preferences.edit().putString(KEY_MODE, value.name).apply()
        }

    private companion object {
        const val PREFS_NAME = "ekms_mobile_theme"
        const val KEY_MODE = "mode"
    }
}
