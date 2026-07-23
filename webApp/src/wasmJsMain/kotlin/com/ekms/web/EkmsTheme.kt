package com.ekms.web

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Soft blue admin palette — bright and calm. */
internal object EkmsColors {
    val Paper = Color(0xFFF5F8FC)
    val Ink = Color(0xFF1E3A5F)
    val Wash = Color(0xFFE8F0F8)
    val Hairline = Color(0xFFD4E0EE)
    val Accent = Color(0xFF3B82F6)
    val AccentDark = Color(0xFF2563EB)
    val AccentSoft = Color(0xFFDBEAFE)
    val Online = Color(0xFF0D9488)
    val Offline = Color(0xFFC46B5A)
    val Panel = Color(0xFFFFFFFF)
    val Muted = Color(0xFF64748B)
    val TopBar = Color(0xFFFFFFFF)
}

private val EkmsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private val EkmsLightScheme = lightColorScheme(
    primary = EkmsColors.Accent,
    onPrimary = Color.White,
    primaryContainer = EkmsColors.AccentSoft,
    onPrimaryContainer = EkmsColors.Ink,
    secondary = EkmsColors.Ink,
    onSecondary = Color.White,
    secondaryContainer = EkmsColors.Wash,
    onSecondaryContainer = EkmsColors.Ink,
    tertiary = EkmsColors.Online,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD0EDEB),
    onTertiaryContainer = EkmsColors.Ink,
    background = EkmsColors.Paper,
    onBackground = EkmsColors.Ink,
    surface = EkmsColors.Panel,
    onSurface = EkmsColors.Ink,
    surfaceVariant = EkmsColors.Wash,
    onSurfaceVariant = EkmsColors.Muted,
    outline = EkmsColors.Hairline,
    outlineVariant = EkmsColors.Hairline,
    error = EkmsColors.Offline,
    onError = Color.White,
    errorContainer = Color(0xFFF8E6E2),
    onErrorContainer = EkmsColors.Offline,
)

internal object EkmsFonts {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    var sans: FontFamily = FontFamily.SansSerif
        private set
    var serif: FontFamily = FontFamily.Serif
        private set

    suspend fun load() {
        if (_ready.value) return
        val client = HttpClient()
        try {
            suspend fun bytes(name: String): ByteArray =
                client.get("./fonts/$name").readRawBytes()

            sans = FontFamily(
                Font("PlusJakartaSans-Regular", bytes("PlusJakartaSans-Regular.ttf"), FontWeight.Normal),
                Font("PlusJakartaSans-Medium", bytes("PlusJakartaSans-Medium.ttf"), FontWeight.Medium),
                Font("PlusJakartaSans-SemiBold", bytes("PlusJakartaSans-SemiBold.ttf"), FontWeight.SemiBold),
                Font("PlusJakartaSans-Bold", bytes("PlusJakartaSans-Bold.ttf"), FontWeight.Bold),
            )
            serif = FontFamily(
                Font("Literata-SemiBold", bytes("Literata-SemiBold.ttf"), FontWeight.SemiBold),
                Font("Literata-Bold", bytes("Literata-Bold.ttf"), FontWeight.Bold),
            )
            _ready.value = true
        } catch (_: Throwable) {
            _ready.value = true
        } finally {
            client.close()
        }
    }
}

private fun ekmsTypography(sans: FontFamily, serif: FontFamily) = Typography(
    displayLarge = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp, color = EkmsColors.Ink),
    displayMedium = TextStyle(fontFamily = serif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, color = EkmsColors.Ink),
    headlineLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, color = EkmsColors.Ink),
    headlineMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, color = EkmsColors.Ink),
    headlineSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, color = EkmsColors.Ink),
    titleLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, color = EkmsColors.Ink),
    titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, color = EkmsColors.Ink),
    titleSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, color = EkmsColors.Ink),
    bodyLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, color = EkmsColors.Ink),
    bodyMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = EkmsColors.Ink),
    bodySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = EkmsColors.Muted),
    labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, color = EkmsColors.Ink),
    labelMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, color = EkmsColors.Muted),
    labelSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, color = EkmsColors.Muted),
)

@Composable
internal fun EkmsTheme(content: @Composable () -> Unit) {
    val ready by EkmsFonts.ready.collectAsState()
    val typography = if (ready) {
        ekmsTypography(EkmsFonts.sans, EkmsFonts.serif)
    } else {
        ekmsTypography(FontFamily.SansSerif, FontFamily.Serif)
    }
    MaterialTheme(
        colorScheme = EkmsLightScheme,
        typography = typography,
        shapes = EkmsShapes,
        content = content,
    )
}
