package com.ekms.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ekms.mobile.data.MobileApiClient
import com.ekms.mobile.data.rememberMobileNetworkStatus
import com.ekms.mobile.ui.access.AccessScreen
import com.ekms.mobile.ui.alerts.AlertsScreen
import com.ekms.mobile.ui.auth.LoginScreen
import com.ekms.mobile.ui.common.ConnectionStatusChip
import com.ekms.mobile.ui.dashboard.DashboardScreen
import com.ekms.mobile.ui.digitalkey.DigitalKeyComingSoonDialog
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
@Composable
fun SuperAdminCompanionApp() {
    EkmsMobileTheme {
        val applicationContext = LocalContext.current.applicationContext
        val apiClient = remember(applicationContext) { MobileApiClient(applicationContext) }
        var authenticated by remember { mutableStateOf(apiClient.isAuthenticated) }
        var profile by remember { mutableStateOf(apiClient.profile) }

        if (!authenticated) {
            LoginScreen(
                apiClient = apiClient,
                onLoginSuccess = { signedIn ->
                    profile = signedIn
                    authenticated = true
                },
            )
            return@EkmsMobileTheme
        }

        val navController = rememberNavController()
        var notice by remember { mutableStateOf<String?>(null) }
        var showDigitalKeyDialog by remember { mutableStateOf(false) }
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

        Scaffold(
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
                        TextButton(onClick = {
                            apiClient.clearSession()
                            profile = null
                            authenticated = false
                        }) {
                            Text("Sign out")
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    MobileDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { goToTab(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                    NavigationBarItem(
                        selected = false,
                        onClick = { showDigitalKeyDialog = true },
                        icon = { Icon(Icons.Filled.PhoneAndroid, contentDescription = "Digital Key") },
                        label = { Text("Digital Key") },
                    )
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
                    }
                }
            }
        }

        if (showDigitalKeyDialog) {
            DigitalKeyComingSoonDialog(onDismiss = { showDigitalKeyDialog = false })
        }
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

private val MobileDestination.icon: ImageVector
    get() = when (this) {
        MobileDestination.DASHBOARD -> Icons.Filled.Home
        MobileDestination.TERMINALS -> Icons.Filled.Dns
        MobileDestination.ACCESS -> Icons.Filled.VpnKey
        MobileDestination.ALERTS -> Icons.Filled.Notifications
    }
