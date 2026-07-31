package com.ekms.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.mobile.data.MobileNetworkStatus

@Composable
fun ConnectionStatusChip(
    network: MobileNetworkStatus,
    serverReachable: Boolean,
    syncing: Boolean = false,
) {
    val text = when {
        syncing -> "Syncing…"
        !network.hasInternet -> "Offline"
        serverReachable -> "Connected"
        else -> "Reconnecting…"
    }
    val container = when {
        syncing -> MaterialTheme.colorScheme.secondaryContainer
        !network.hasInternet || !serverReachable -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val foreground = when {
        syncing -> MaterialTheme.colorScheme.onSecondaryContainer
        !network.hasInternet || !serverReachable -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Text(
        text = text,
        color = foreground,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
