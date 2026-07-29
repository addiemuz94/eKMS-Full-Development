package com.ekms.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ekms.mobile.data.MobileApiClient
import com.ekms.mobile.ui.access.AccessScreen
import com.ekms.mobile.ui.alerts.AlertsScreen
import com.ekms.mobile.ui.auth.LoginScreen
import com.ekms.mobile.ui.dashboard.DashboardScreen
import com.ekms.mobile.ui.digitalkey.DigitalKeyComingSoonDialog
import com.ekms.mobile.ui.nav.MobileDestination
import com.ekms.mobile.ui.terminals.TerminalsScreen
import com.ekms.mobile.ui.theme.EkmsMobileTheme

/**
 * Super Admin Mobile is deliberately a companion: it exposes personal Digital Key status,
 * approvals, alerts and terminal monitoring, but no full CRUD.
 *
 * Navigation-Compose replaces the previous single tab-enum/when-block: each bottom-bar
 * destination is its own screen composable under `ui/<area>/`, wired through one `NavHost`.
 * Digital Key is intentionally NOT one of those destinations — it never navigates, it only
 * opens `DigitalKeyComingSoonDialog` (see that file for why: phone-as-card is not currently
 * buildable against this hardware, tracked in CLAUDE.md's Hardware Feature Findings section).
 *
 * Gated on [MobileApiClient.isAuthenticated] — `LoginScreen` for a fresh/signed-out session, the
 * existing bottom-nav Scaffold once signed in. The Access tab now shows the real Key Access
 * Request feature (role-dispatched — see `AccessScreen`); Dashboard/Terminals/Alerts still show
 * local demo data, wiring them to real backend data remains separate, deferred follow-up work.
 */
@Composable
fun SuperAdminCompanionApp() {
    EkmsMobileTheme {
        val applicationContext = LocalContext.current.applicationContext
        val apiClient = remember(applicationContext) { MobileApiClient(applicationContext) }
        var authenticated by remember { mutableStateOf(apiClient.isAuthenticated) }

        if (!authenticated) {
            LoginScreen(apiClient = apiClient, onLoginSuccess = { authenticated = true })
            return@EkmsMobileTheme
        }

        val navController = rememberNavController()
        var notice by remember { mutableStateOf<String?>(null) }
        var showDigitalKeyDialog by remember { mutableStateOf(false) }

        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        Scaffold(
            topBar = {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("eKMS Digital Key", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Super Admin Companion", style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = {
                            apiClient.clearSession()
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
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
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
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = horizontalPadding, vertical = 18.dp),
                ) {
                    notice?.let { message ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Text(message, modifier = Modifier.padding(14.dp))
                        }
                    }

                    NavHost(navController = navController, startDestination = MobileDestination.DASHBOARD.route) {
                        composable(MobileDestination.DASHBOARD.route) {
                            DashboardScreen(
                                onOpenTerminals = { navController.navigate(MobileDestination.TERMINALS.route) },
                                onOpenAccess = { navController.navigate(MobileDestination.ACCESS.route) },
                                onNotice = { notice = it },
                            )
                        }
                        composable(MobileDestination.TERMINALS.route) {
                            TerminalsScreen()
                        }
                        composable(MobileDestination.ACCESS.route) {
                            AccessScreen(apiClient = apiClient, profile = apiClient.profile, onNotice = { notice = it })
                        }
                        composable(MobileDestination.ALERTS.route) {
                            AlertsScreen(onNotice = { notice = it })
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

private val MobileDestination.icon: ImageVector
    get() = when (this) {
        MobileDestination.DASHBOARD -> Icons.Filled.Home
        MobileDestination.TERMINALS -> Icons.Filled.Dns
        MobileDestination.ACCESS -> Icons.Filled.VpnKey
        MobileDestination.ALERTS -> Icons.Filled.Notifications
    }
