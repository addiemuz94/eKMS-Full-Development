package com.ekms.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "root") {
        val fontsReady by EkmsFonts.ready.collectAsState()
        LaunchedEffect(Unit) {
            EkmsFonts.load()
        }
        EkmsTheme {
            if (!fontsReady) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(EkmsColors.Paper),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = EkmsColors.Accent)
                }
            } else {
                EkmsWebApp()
            }
        }
    }
}
