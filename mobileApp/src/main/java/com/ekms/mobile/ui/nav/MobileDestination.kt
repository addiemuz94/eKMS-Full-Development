package com.ekms.mobile.ui.nav

/**
 * Real NavHost destinations. Digital Key is deliberately not a destination here — it opens an
 * in-app "coming soon" dialog from the bottom bar instead of navigating (see
 * `ui/digitalkey/DigitalKeyComingSoonDialog.kt` and `SuperAdminCompanionApp`'s bottom bar wiring).
 */
enum class MobileDestination(val route: String, val label: String) {
    DASHBOARD("dashboard", "Overview"),
    TERMINALS("terminals", "Terminals"),
    ACCESS("access", "Access"),
    ALERTS("alerts", "Alerts"),
}
