package com.ekms.mobile.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ekms.mobile.ui.common.CompanionCard

@Composable
fun AlertsScreen(onNotice: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CompanionCard("No critical alert in local demo", "Backend data will replace this sample list.") {
            onNotice("No action is sent from this local companion demo.")
        }
    }
}
