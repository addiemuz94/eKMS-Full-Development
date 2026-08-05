package com.ekms.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.composables.icons.lucide.R
import com.ekms.mobile.MainActivity
import com.ekms.mobile.data.MobileApiClient
import com.ekms.mobile.data.MobileThemeMode
import com.ekms.mobile.data.MobileThemePreferences
import com.ekms.mobile.data.rememberMobileNetworkStatus
import com.ekms.mobile.ui.access.AccessScreen
import com.ekms.mobile.ui.alerts.AlertsScreen
import com.ekms.mobile.ui.auth.LoginScreen
import com.ekms.mobile.ui.common.ConfirmDialog
import com.ekms.mobile.ui.common.ConnectionStatusChip
import com.ekms.mobile.ui.common.LocalHazeState
import com.ekms.mobile.ui.common.ThemeModeSegmentedControl
import com.ekms.mobile.ui.dashboard.DashboardScreen
import com.ekms.mobile.ui.digitalkey.DigitalKeyComingSoonDialog
import com.ekms.mobile.ui.logs.LogsScreen
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.ekms.mobile.ui.nav.MobileDestination
import com.ekms.mobile.ui.terminals.TerminalsScreen
import com.ekms.mobile.ui.theme.EkmsMobileTheme
import com.ekms.shared.domain.UserRole
import kotlinx.coroutines.delay

private const val LIVE_REFRESH_INTERVAL_MILLIS = 30_000L

/**
 * Mobile companion shell: login gate, bottom navigation, and tab screens.
 *
 * NavHost must NOT sit inside a verticalScroll parent — that broke returning to Overview.
 * Each tab scrolls its own content. Digital Key opens a dialog and does not navigate.
 *
 * [refreshEpoch] bumps on resume and every 30s so tabs re-fetch portal changes while open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperAdminCompanionApp(
    openTabRequest: String? = null,
    onOpenTabConsumed: () -> Unit = {},
) {
    val applicationContext = LocalContext.current.applicationContext
    // Phase M-B: device-local theme preference, same SYSTEM/LIGHT/DARK shape as terminalApp's
    // TerminalThemeMode. Resolved above EkmsMobileTheme so the whole app (including the
    // pre-auth LoginScreen) honors it, not just the authenticated shell.
    val themePreferences = remember(applicationContext) { MobileThemePreferences(applicationContext) }
    var themeMode by remember { mutableStateOf(themePreferences.mode) }
    val systemInDarkTheme = isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        MobileThemeMode.SYSTEM -> systemInDarkTheme
        MobileThemeMode.LIGHT -> false
        MobileThemeMode.DARK -> true
    }
    fun setThemeMode(mode: MobileThemeMode) {
        themeMode = mode
        themePreferences.mode = mode
    }

    EkmsMobileTheme(darkTheme = isDarkTheme) {
        // Boot splash — shown once per process (cold start of this composable), before the auth
        // gate below, so it's visible for both signed-out and already-signed-in launches.
        // Branding-only (see MobileBootSplashScreen's own doc): no hardware diagnostics, no
        // failure/retry state — mirrors terminalApp's screen shape, not its content.
        var showBootSplash by remember { mutableStateOf(true) }
        if (showBootSplash) {
            MobileBootSplashScreen(
                isDarkTheme = isDarkTheme,
                onContinue = { showBootSplash = false },
            )
            return@EkmsMobileTheme
        }

        val apiClient = remember(applicationContext) { MobileApiClient(applicationContext) }
        var authenticated by remember { mutableStateOf(apiClient.isAuthenticated) }
        var profile by remember { mutableStateOf(apiClient.profile) }
        // Set by MobileApiClient.onSessionExpired below (a 401 that survived one refresh
        // attempt) — shown once on LoginScreen, then cleared on either a fresh manual login or
        // an explicit sign-out, so it never reappears for an unrelated later sign-out.
        var sessionExpiredMessage by remember { mutableStateOf<String?>(null) }
        // Reassigned every recomposition — cheap (just replaces a lambda reference on a stable
        // object) and always closes over the current state setters above, so there's no stale
        // capture risk from doing this inline rather than inside a LaunchedEffect.
        apiClient.onSessionExpired = {
            authenticated = false
            profile = null
            sessionExpiredMessage = "Your session expired. Please sign in again."
        }

        if (!authenticated) {
            LoginScreen(
                apiClient = apiClient,
                onLoginSuccess = { signedIn ->
                    profile = signedIn
                    authenticated = true
                    sessionExpiredMessage = null
                },
                themeMode = themeMode,
                onThemeModeChange = ::setThemeMode,
                sessionExpiredMessage = sessionExpiredMessage,
            )
            return@EkmsMobileTheme
        }

        val navController = rememberNavController()
        // Phase M-E: single shared HazeState for the whole authenticated shell — the main
        // Scaffold content below is the hazeSource; the overflow menu (this file) and every
        // ConfirmDialog (this file's Sign-out one, plus Access-tab screens' via LocalHazeState)
        // are hazeEffect consumers of it. One instance covers the whole app regardless of which
        // tab is active, since a Dialog/Popup renders above the entire window, not just the
        // current tab's own content.
        val hazeState = rememberHazeState()
        var notice by remember { mutableStateOf<String?>(null) }
        var showDigitalKeyDialog by remember { mutableStateOf(false) }
        var showOverflowMenu by remember { mutableStateOf(false) }
        var showSignOutConfirm by remember { mutableStateOf(false) }
        var refreshEpoch by remember { mutableIntStateOf(0) }
        var serverReachable by remember { mutableStateOf(true) }
        var syncing by remember { mutableStateOf(false) }
        val networkStatus = rememberMobileNetworkStatus()
        val lifecycleOwner = LocalLifecycleOwner.current

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshEpoch++
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(authenticated) {
            while (authenticated) {
                delay(LIVE_REFRESH_INTERVAL_MILLIS)
                refreshEpoch++
            }
        }

        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        fun goToTab(route: String) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        // A tapped push notification requests a tab (currently only Alerts) via MainActivity's
        // intent extra; wait for auth so a cold start (app not already running) lands on the
        // tab only after login succeeds instead of being dropped.
        LaunchedEffect(openTabRequest, authenticated) {
            if (authenticated && openTabRequest == MainActivity.TAB_ALERTS) {
                goToTab(MobileDestination.ALERTS.route)
                onOpenTabConsumed()
            }
        }

        // Phase M-E: provides the shared HazeState to every ConfirmDialog reachable from here —
        // this file's own Sign-out dialog, and (via LocalHazeState) the ones in the Access-tab
        // screens reached through NavHost below, without threading a hazeState param through
        // their function signatures.
        CompositionLocalProvider(LocalHazeState provides hazeState) {
        Scaffold(
            modifier = Modifier.hazeSource(state = hazeState),
            topBar = {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "eKMS Digital Key",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                companionSubtitle(profile?.role, profile?.displayName),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        ConnectionStatusChip(
                            network = networkStatus,
                            serverReachable = serverReachable,
                            syncing = syncing,
                        )
                        // Phase M-A: overflow menu replacing the bare top-bar Sign out button —
                        // now also hosts Digital Key's dialog trigger (previously mismodeled as
                        // a 6th bottom-nav item that never actually navigated) and the Phase M-B
                        // theme toggle. Bottom nav shrinks back to real content destinations only.
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(painterResource(R.drawable.lucide_ic_ellipsis_vertical), contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                // Phase M-E: same shared-HazeState pairing as ConfirmDialog, tried
                                // first with no extra styling (see CLAUDE_MOBILE.md) — DropdownMenu's
                                // Popup is architecturally the same separate-window shape as Dialog,
                                // but Haze's own official cross-window sample only names Dialog by
                                // name, not DropdownMenu/Popup, so this is genuinely unconfirmed
                                // until hardware-tested.
                                modifier = Modifier.hazeEffect(state = hazeState),
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Theme", style = MaterialTheme.typography.labelLarge)
                                            ThemeModeSegmentedControl(
                                                mode = themeMode,
                                                onModeChange = ::setThemeMode,
                                            )
                                        }
                                    },
                                    onClick = {},
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Digital Key") },
                                    leadingIcon = { Icon(painterResource(R.drawable.lucide_ic_smartphone), contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showDigitalKeyDialog = true
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Sign out") },
                                    leadingIcon = { Icon(painterResource(R.drawable.lucide_ic_log_out), contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showSignOutConfirm = true
                                    },
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = {
                val destinations = if (profile?.role == UserRole.SUPER_ADMIN) {
                    MobileDestination.entries
                } else {
                    MobileDestination.entries.filter { it != MobileDestination.LOGS }
                }
                // Phase M-A: shrunk back to the 5 real content destinations only — Digital Key
                // (which never navigated, only opened a dialog) moved into the top-bar overflow
                // menu above.
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { goToTab(destination.route) },
                            icon = { Icon(painterResource(destination.icon), contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
        ) { padding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                val horizontalPadding = if (maxWidth < 520.dp) 16.dp else 24.dp
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 720.dp)
                        .align(Alignment.TopCenter)
                        .padding(horizontal = horizontalPadding),
                ) {
                    notice?.let { message ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Text(message, modifier = Modifier.padding(14.dp))
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = MobileDestination.DASHBOARD.route,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 12.dp),
                    ) {
                        composable(MobileDestination.DASHBOARD.route) {
                            DashboardScreen(
                                apiClient = apiClient,
                                profile = profile,
                                refreshEpoch = refreshEpoch,
                                onLiveStatus = { ok, busy ->
                                    serverReachable = ok
                                    syncing = busy
                                },
                                onOpenTerminals = { goToTab(MobileDestination.TERMINALS.route) },
                                onOpenAccess = { goToTab(MobileDestination.ACCESS.route) },
                                onOpenAlerts = { goToTab(MobileDestination.ALERTS.route) },
                                onNotice = { notice = it },
                            )
                        }
                        composable(MobileDestination.TERMINALS.route) {
                            TerminalsScreen(
                                apiClient = apiClient,
                                refreshEpoch = refreshEpoch,
                                onLiveStatus = { ok, busy ->
                                    serverReachable = ok
                                    syncing = busy
                                },
                                onNotice = { notice = it },
                            )
                        }
                        composable(MobileDestination.ACCESS.route) {
                            AccessScreen(
                                apiClient = apiClient,
                                profile = profile ?: apiClient.profile,
                                refreshEpoch = refreshEpoch,
                                onLiveStatus = { ok, busy ->
                                    serverReachable = ok
                                    syncing = busy
                                },
                                onNotice = { notice = it },
                            )
                        }
                        composable(MobileDestination.ALERTS.route) {
                            AlertsScreen(
                                apiClient = apiClient,
                                profile = profile ?: apiClient.profile,
                                refreshEpoch = refreshEpoch,
                                onLiveStatus = { ok, busy ->
                                    serverReachable = ok
                                    syncing = busy
                                },
                                onOpenAccess = { goToTab(MobileDestination.ACCESS.route) },
                                onNotice = { notice = it },
                            )
                        }
                        composable(MobileDestination.LOGS.route) {
                            LogsScreen(
                                apiClient = apiClient,
                                profile = profile ?: apiClient.profile,
                                refreshEpoch = refreshEpoch,
                                onLiveStatus = { ok, busy ->
                                    serverReachable = ok
                                    syncing = busy
                                },
                                onNotice = { notice = it },
                            )
                        }
                    }
                }
            }
        }

        if (showDigitalKeyDialog) {
            DigitalKeyComingSoonDialog(onDismiss = { showDigitalKeyDialog = false })
        }

        // Phase M-C: Sign out is now confirm-gated instead of firing immediately on tap.
        if (showSignOutConfirm) {
            ConfirmDialog(
                title = "Sign out?",
                body = "You'll need to sign in again to use this app.",
                confirmLabel = "Sign out",
                onConfirm = {
                    apiClient.clearSession()
                    profile = null
                    authenticated = false
                    sessionExpiredMessage = null
                },
                onDismiss = { showSignOutConfirm = false },
            )
        }
        } // CompositionLocalProvider(LocalHazeState)
    }
}

private fun companionSubtitle(role: UserRole?, displayName: String?): String {
    val roleLabel = when (role) {
        UserRole.SUPER_ADMIN -> "Super Admin"
        UserRole.REGIONAL_ADMIN -> "Regional Admin"
        UserRole.TECHNICIAN -> "Technician"
        UserRole.VENDOR -> "Vendor"
        UserRole.GOD_ADMIN -> "God Admin"
        null -> "Companion"
    }
    return if (displayName.isNullOrBlank()) roleLabel else "$roleLabel · $displayName"
}

private val MobileDestination.icon: Int
    get() = when (this) {
        MobileDestination.DASHBOARD -> R.drawable.lucide_ic_house
        MobileDestination.TERMINALS -> R.drawable.lucide_ic_server
        MobileDestination.ACCESS -> R.drawable.lucide_ic_key_round
        MobileDestination.ALERTS -> R.drawable.lucide_ic_bell
        MobileDestination.LOGS -> R.drawable.lucide_ic_clipboard_list
    }
