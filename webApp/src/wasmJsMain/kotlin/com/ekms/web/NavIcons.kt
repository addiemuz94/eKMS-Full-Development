package com.ekms.web

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Lightweight vector icons for Wasm (avoids material-icons-extended OOM). */
internal object NavIcons {
    val Dashboard: ImageVector by lazy {
        materialIcon("Dashboard") {
            // grid of 4 squares
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 3f); horizontalLineToRelative(8f); verticalLineToRelative(8f); horizontalLineToRelative(-8f); close()
                moveTo(13f, 3f); horizontalLineToRelative(8f); verticalLineToRelative(8f); horizontalLineToRelative(-8f); close()
                moveTo(3f, 13f); horizontalLineToRelative(8f); verticalLineToRelative(8f); horizontalLineToRelative(-8f); close()
                moveTo(13f, 13f); horizontalLineToRelative(8f); verticalLineToRelative(8f); horizontalLineToRelative(-8f); close()
            }
        }
    }

    val Apartment: ImageVector by lazy {
        materialIcon("Apartment") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 11f); verticalLineTo(3f); horizontalLineTo(7f); verticalLineToRelative(4f); horizontalLineTo(3f)
                verticalLineToRelative(14f); horizontalLineToRelative(8f); verticalLineToRelative(-4f); horizontalLineToRelative(2f); verticalLineToRelative(4f)
                horizontalLineToRelative(8f); verticalLineTo(11f); horizontalLineToRelative(-4f); close()
                moveTo(7f, 19f); horizontalLineTo(5f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(7f, 15f); horizontalLineTo(5f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(7f, 11f); horizontalLineTo(5f); verticalLineTo(9f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(11f, 19f); horizontalLineTo(9f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(11f, 15f); horizontalLineTo(9f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(11f, 11f); horizontalLineTo(9f); verticalLineTo(9f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(11f, 7f); horizontalLineTo(9f); verticalLineTo(5f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(15f, 19f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(15f, 15f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(15f, 11f); horizontalLineToRelative(-2f); verticalLineTo(9f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(15f, 7f); horizontalLineToRelative(-2f); verticalLineTo(5f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(19f, 19f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(19f, 15f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
            }
        }
    }

    val Devices: ImageVector by lazy {
        materialIcon("Devices") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 6f); horizontalLineToRelative(18f); verticalLineTo(4f); horizontalLineTo(4f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(11f); horizontalLineTo(0f)
                verticalLineToRelative(3f); horizontalLineToRelative(14f); verticalLineToRelative(-3f); horizontalLineTo(4f); verticalLineTo(6f); close()
                moveTo(23f, 8f); horizontalLineToRelative(-6f); curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                verticalLineToRelative(10f); curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f); horizontalLineToRelative(6f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f); verticalLineTo(9f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f); close()
                moveTo(22f, 17f); horizontalLineToRelative(-4f); verticalLineToRelative(-7f); horizontalLineToRelative(4f); verticalLineToRelative(7f); close()
            }
        }
    }

    val People: ImageVector by lazy {
        materialIcon("People") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 11f); curveToRelative(1.66f, 0f, 2.99f, -1.34f, 2.99f, -3f)
                reflectiveCurveTo(17.66f, 5f, 16f, 5f); curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
                reflectiveCurveToRelative(1.34f, 3f, 3f, 3f); close()
                moveTo(8f, 11f); curveToRelative(1.66f, 0f, 2.99f, -1.34f, 2.99f, -3f)
                reflectiveCurveTo(9.66f, 5f, 8f, 5f); curveTo(6.34f, 5f, 5f, 6.34f, 5f, 8f)
                reflectiveCurveToRelative(1.34f, 3f, 3f, 3f); close()
                moveTo(8f, 13f); curveToRelative(-2.33f, 0f, -7f, 1.17f, -7f, 3.5f)
                verticalLineTo(19f); horizontalLineToRelative(14f); verticalLineToRelative(-2.5f)
                curveToRelative(0f, -2.33f, -4.67f, -3.5f, -7f, -3.5f); close()
                moveTo(16f, 13f); curveToRelative(-0.29f, 0f, -0.62f, 0.02f, -0.97f, 0.05f)
                curveToRelative(1.16f, 0.84f, 1.97f, 1.97f, 1.97f, 3.45f); verticalLineTo(19f)
                horizontalLineToRelative(6f); verticalLineToRelative(-2.5f); curveToRelative(0f, -2.33f, -4.67f, -3.5f, -7f, -3.5f); close()
            }
        }
    }

    val Key: ImageVector by lazy {
        materialIcon("Key") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12.65f, 10f); curveTo(11.83f, 7.67f, 9.61f, 6f, 7f, 6f)
                curveToRelative(-3.31f, 0f, -6f, 2.69f, -6f, 6f); reflectiveCurveToRelative(2.69f, 6f, 6f, 6f)
                curveToRelative(2.61f, 0f, 4.83f, -1.67f, 5.65f, -4f); horizontalLineTo(17f)
                verticalLineToRelative(4f); horizontalLineToRelative(4f); verticalLineToRelative(-4f); horizontalLineToRelative(2f)
                verticalLineToRelative(-4f); horizontalLineTo(12.65f); close()
                moveTo(7f, 14f); curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
                reflectiveCurveToRelative(0.9f, -2f, 2f, -2f); reflectiveCurveToRelative(2f, 0.9f, 2f, 2f)
                reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f); close()
            }
        }
    }

    val Lock: ImageVector by lazy {
        materialIcon("Lock") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18f, 8f); horizontalLineToRelative(-1f); verticalLineTo(6f)
                curveToRelative(0f, -2.76f, -2.24f, -5f, -5f, -5f); reflectiveCurveTo(7f, 3.24f, 7f, 6f)
                verticalLineToRelative(2f); horizontalLineTo(6f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(10f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(12f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(10f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
                moveTo(12f, 17f); curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
                reflectiveCurveToRelative(0.9f, -2f, 2f, -2f); reflectiveCurveToRelative(2f, 0.9f, 2f, 2f)
                reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f); close()
                moveTo(9f, 8f); verticalLineTo(6f); curveToRelative(0f, -1.66f, 1.34f, -3f, 3f, -3f)
                reflectiveCurveToRelative(3f, 1.34f, 3f, 3f); verticalLineToRelative(2f); horizontalLineTo(9f); close()
            }
        }
    }

    val Event: ImageVector by lazy {
        materialIcon("Event") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 12f); horizontalLineToRelative(-5f); verticalLineToRelative(5f); horizontalLineToRelative(5f); verticalLineToRelative(-5f); close()
                moveTo(16f, 1f); verticalLineToRelative(2f); horizontalLineTo(8f); verticalLineTo(1f); horizontalLineTo(6f)
                verticalLineToRelative(2f); horizontalLineTo(5f); curveToRelative(-1.11f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                lineTo(3f, 19f); curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f); horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); horizontalLineToRelative(-1f); verticalLineTo(1f)
                horizontalLineToRelative(-2f); close()
                moveTo(19f, 19f); horizontalLineTo(5f); verticalLineTo(8f); horizontalLineToRelative(14f); verticalLineToRelative(11f); close()
            }
        }
    }

    val Schedule: ImageVector by lazy {
        materialIcon("Schedule") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(11.99f, 2f); curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f)
                reflectiveCurveToRelative(4.47f, 10f, 9.99f, 10f); curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                reflectiveCurveTo(17.52f, 2f, 11.99f, 2f); close()
                moveTo(12f, 20f); curveToRelative(-4.42f, 0f, -8f, -3.58f, -8f, -8f)
                reflectiveCurveToRelative(3.58f, -8f, 8f, -8f); reflectiveCurveToRelative(8f, 3.58f, 8f, 8f)
                reflectiveCurveToRelative(-3.58f, 8f, -8f, 8f); close()
                moveTo(12.5f, 7f); horizontalLineTo(11f); verticalLineToRelative(6f); lineToRelative(5.25f, 3.15f)
                lineToRelative(0.75f, -1.23f); lineToRelative(-4.5f, -2.67f); close()
            }
        }
    }

    val Security: ImageVector by lazy {
        materialIcon("Security") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 1f); lineTo(3f, 5f); verticalLineToRelative(6f)
                curveToRelative(0f, 5.55f, 3.84f, 10.74f, 9f, 12f); curveToRelative(5.16f, -1.26f, 9f, -6.45f, 9f, -12f)
                verticalLineTo(5f); lineToRelative(-9f, -4f); close()
                moveTo(12f, 11.99f); horizontalLineToRelative(7f)
                curveToRelative(-0.53f, 4.12f, -3.28f, 7.79f, -7f, 8.94f); verticalLineTo(12f)
                horizontalLineTo(5f); verticalLineTo(6.3f); lineToRelative(7f, -3.11f); verticalLineToRelative(8.8f); close()
            }
        }
    }

    val Groups: ImageVector by lazy {
        materialIcon("Groups") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 12.75f); curveToRelative(1.63f, 0f, 3.45f, 0.39f, 4.86f, 1.11f)
                curveToRelative(0.9f, 0.46f, 1.48f, 1.28f, 1.48f, 2.18f); verticalLineTo(18f)
                horizontalLineTo(5.66f); verticalLineToRelative(-1.96f)
                curveToRelative(0f, -0.9f, 0.58f, -1.72f, 1.48f, -2.18f)
                curveToRelative(1.41f, -0.72f, 3.23f, -1.11f, 4.86f, -1.11f); close()
                moveTo(4.34f, 14.12f); curveToRelative(0.82f, -0.42f, 1.76f, -0.74f, 2.77f, -0.94f)
                curveToRelative(-0.45f, -0.5f, -0.77f, -1.12f, -0.77f, -1.81f)
                curveToRelative(0f, -0.94f, 0.49f, -1.77f, 1.23f, -2.26f)
                curveTo(6.57f, 9.03f, 5.53f, 9.5f, 4.66f, 10.23f)
                curveToRelative(-0.99f, 0.82f, -1.16f, 2.29f, -0.32f, 3.89f); close()
                moveTo(19.66f, 14.12f); curveToRelative(0.84f, -1.6f, 0.67f, -3.07f, -0.32f, -3.89f)
                curveToRelative(-0.87f, -0.73f, -1.91f, -1.2f, -2.91f, -1.12f)
                curveToRelative(0.74f, 0.49f, 1.23f, 1.32f, 1.23f, 2.26f)
                curveToRelative(0f, 0.69f, -0.32f, 1.31f, -0.77f, 1.81f)
                curveToRelative(1.01f, 0.2f, 1.95f, 0.52f, 2.77f, 0.94f); close()
                moveTo(12f, 6f); curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f); reflectiveCurveToRelative(-2f, -0.9f, -2f, -2f)
                reflectiveCurveToRelative(0.9f, -2f, 2f, -2f); close()
            }
        }
    }

    val Category: ImageVector by lazy {
        materialIcon("Category") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f); lineToRelative(-5.5f, 9f); horizontalLineToRelative(11f); close()
                moveTo(17.5f, 13f); curveToRelative(-1.93f, 0f, -3.5f, 1.57f, -3.5f, 3.5f)
                reflectiveCurveToRelative(1.57f, 3.5f, 3.5f, 3.5f); reflectiveCurveToRelative(3.5f, -1.57f, 3.5f, -3.5f)
                reflectiveCurveToRelative(-1.57f, -3.5f, -3.5f, -3.5f); close()
                moveTo(3f, 21.5f); horizontalLineToRelative(8f); verticalLineToRelative(-8f); horizontalLineTo(3f); verticalLineToRelative(8f); close()
            }
        }
    }

    val Sync: ImageVector by lazy {
        materialIcon("Sync") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 4f); verticalLineTo(1f); lineTo(8f, 5f); lineToRelative(4f, 4f)
                verticalLineTo(6f); curveToRelative(3.31f, 0f, 6f, 2.69f, 6f, 6f)
                curveToRelative(0f, 1.01f, -0.25f, 1.97f, -0.7f, 2.8f); lineToRelative(1.46f, 1.46f)
                curveTo(19.54f, 15.03f, 20f, 13.57f, 20f, 12f)
                curveToRelative(0f, -4.42f, -3.58f, -8f, -8f, -8f); close()
                moveTo(12f, 18f); curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
                curveToRelative(0f, -1.01f, 0.25f, -1.97f, 0.7f, -2.8f); lineTo(5.24f, 7.74f)
                curveTo(4.46f, 8.97f, 4f, 10.43f, 4f, 12f); curveToRelative(0f, 4.42f, 3.58f, 8f, 8f, 8f)
                verticalLineToRelative(3f); lineToRelative(4f, -4f); lineToRelative(-4f, -4f); verticalLineToRelative(3f); close()
            }
        }
    }

    val Swap: ImageVector by lazy {
        materialIcon("Swap") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6.99f, 11f); lineTo(3f, 15f); lineToRelative(3.99f, 4f); verticalLineToRelative(-3f)
                horizontalLineTo(14f); verticalLineToRelative(-2f); horizontalLineTo(6.99f); verticalLineToRelative(-3f); close()
                moveTo(21f, 9f); lineToRelative(-3.99f, -4f); verticalLineToRelative(3f); horizontalLineTo(10f)
                verticalLineToRelative(2f); horizontalLineToRelative(7.01f); verticalLineToRelative(3f); lineTo(21f, 9f); close()
            }
        }
    }

    val List: ImageVector by lazy {
        materialIcon("List") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 13f); horizontalLineToRelative(2f); verticalLineToRelative(-2f); horizontalLineTo(3f); verticalLineToRelative(2f); close()
                moveTo(3f, 17f); horizontalLineToRelative(2f); verticalLineToRelative(-2f); horizontalLineTo(3f); verticalLineToRelative(2f); close()
                moveTo(3f, 9f); horizontalLineToRelative(2f); verticalLineTo(7f); horizontalLineTo(3f); verticalLineToRelative(2f); close()
                moveTo(7f, 13f); horizontalLineToRelative(14f); verticalLineToRelative(-2f); horizontalLineTo(7f); verticalLineToRelative(2f); close()
                moveTo(7f, 17f); horizontalLineToRelative(14f); verticalLineToRelative(-2f); horizontalLineTo(7f); verticalLineToRelative(2f); close()
                moveTo(7f, 7f); verticalLineToRelative(2f); horizontalLineToRelative(14f); verticalLineTo(7f); horizontalLineTo(7f); close()
            }
        }
    }

    val Notes: ImageVector by lazy {
        materialIcon("Notes") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 18f); horizontalLineToRelative(12f); verticalLineToRelative(-2f); horizontalLineTo(3f); verticalLineToRelative(2f); close()
                moveTo(3f, 6f); verticalLineToRelative(2f); horizontalLineToRelative(18f); verticalLineTo(6f); horizontalLineTo(3f); close()
                moveTo(3f, 13f); horizontalLineToRelative(18f); verticalLineToRelative(-2f); horizontalLineTo(3f); verticalLineToRelative(2f); close()
            }
        }
    }

    val Verified: ImageVector by lazy {
        materialIcon("Verified") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 1f); lineTo(3f, 5f); verticalLineToRelative(6f)
                curveToRelative(0f, 5.55f, 3.84f, 10.74f, 9f, 12f); curveToRelative(5.16f, -1.26f, 9f, -6.45f, 9f, -12f)
                verticalLineTo(5f); lineToRelative(-9f, -4f); close()
                moveTo(10f, 17f); lineToRelative(-4f, -4f); lineToRelative(1.41f, -1.41f)
                lineTo(10f, 14.17f); lineToRelative(6.59f, -6.59f); lineTo(18f, 9f); lineToRelative(-8f, 8f); close()
            }
        }
    }

    val Settings: ImageVector by lazy {
        materialIcon("Settings") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19.14f, 12.94f)
                curveToRelative(0.04f, -0.31f, 0.06f, -0.63f, 0.06f, -0.94f)
                curveToRelative(0f, -0.31f, -0.02f, -0.63f, -0.06f, -0.94f); lineToRelative(2.03f, -1.58f)
                curveToRelative(0.18f, -0.14f, 0.23f, -0.41f, 0.12f, -0.61f); lineToRelative(-1.92f, -3.32f)
                curveToRelative(-0.12f, -0.22f, -0.37f, -0.29f, -0.59f, -0.22f); lineToRelative(-2.39f, 0.96f)
                curveToRelative(-0.5f, -0.38f, -1.03f, -0.7f, -1.62f, -0.94f); lineTo(14.4f, 2.81f)
                curveToRelative(-0.04f, -0.24f, -0.24f, -0.41f, -0.48f, -0.41f); horizontalLineToRelative(-3.84f)
                curveToRelative(-0.24f, 0f, -0.43f, 0.17f, -0.47f, 0.41f); lineTo(9.25f, 5.35f)
                curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f); lineTo(5.24f, 5.33f)
                curveToRelative(-0.22f, -0.08f, -0.47f, 0f, -0.59f, 0.22f); lineTo(2.74f, 8.87f)
                curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f); lineToRelative(2.03f, 1.58f)
                curveTo(4.84f, 11.37f, 4.8f, 11.69f, 4.8f, 12f); reflectiveCurveToRelative(0.02f, 0.63f, 0.06f, 0.94f)
                lineToRelative(-2.03f, 1.58f); curveToRelative(-0.18f, 0.14f, -0.23f, 0.41f, -0.12f, 0.61f)
                lineToRelative(1.92f, 3.32f); curveToRelative(0.12f, 0.22f, 0.37f, 0.29f, 0.59f, 0.22f)
                lineToRelative(2.39f, -0.96f); curveToRelative(0.5f, 0.38f, 1.03f, 0.7f, 1.62f, 0.94f)
                lineToRelative(0.36f, 2.54f); curveToRelative(0.05f, 0.24f, 0.24f, 0.41f, 0.48f, 0.41f)
                horizontalLineToRelative(3.84f); curveToRelative(0.24f, 0f, 0.44f, -0.17f, 0.47f, -0.41f)
                lineToRelative(0.36f, -2.54f); curveToRelative(0.59f, -0.24f, 1.13f, -0.56f, 1.62f, -0.94f)
                lineToRelative(2.39f, 0.96f); curveToRelative(0.22f, 0.08f, 0.47f, 0f, 0.59f, -0.22f)
                lineToRelative(1.92f, -3.32f); curveToRelative(0.12f, -0.22f, 0.07f, -0.47f, -0.12f, -0.61f)
                lineToRelative(-2.01f, -1.58f); close()
                moveTo(12f, 15.6f); curveToRelative(-1.98f, 0f, -3.6f, -1.62f, -3.6f, -3.6f)
                reflectiveCurveToRelative(1.62f, -3.6f, 3.6f, -3.6f); reflectiveCurveToRelative(3.6f, 1.62f, 3.6f, 3.6f)
                reflectiveCurveToRelative(-1.62f, 3.6f, -3.6f, 3.6f); close()
            }
        }
    }

    val Build: ImageVector by lazy {
        materialIcon("Build") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(22.7f, 19.4f); lineToRelative(-1.4f, 1.4f)
                curveToRelative(-0.4f, 0.4f, -1f, 0.4f, -1.4f, 0f); lineTo(11.6f, 12.5f)
                curveToRelative(-1.4f, 0.6f, -3.1f, 0.3f, -4.2f, -0.8f)
                curveToRelative(-1.4f, -1.4f, -1.5f, -3.5f, -0.4f, -5.1f)
                lineToRelative(2.6f, 2.6f); lineToRelative(1.8f, -1.8f); lineTo(8.9f, 4.5f)
                curveToRelative(1.6f, -1.1f, 3.7f, -1f, 5.1f, 0.4f)
                curveToRelative(1.1f, 1.1f, 1.4f, 2.8f, 0.8f, 4.2f)
                lineToRelative(8.3f, 8.3f); curveToRelative(0.4f, 0.4f, 0.4f, 1f, 0f, 1.4f); close()
            }
        }
    }

    val Delete: ImageVector by lazy {
        materialIcon("Delete") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 9f); verticalLineToRelative(10f); horizontalLineTo(8f); verticalLineTo(9f); horizontalLineToRelative(8f)
                moveToRelative(-1.5f, -6f); horizontalLineToRelative(-5f); lineToRelative(-1f, 1f); horizontalLineTo(5f)
                verticalLineToRelative(2f); horizontalLineToRelative(14f); verticalLineTo(4f); horizontalLineToRelative(-3.5f); lineToRelative(-1f, -1f); close()
                moveTo(18f, 7f); horizontalLineTo(6f); verticalLineToRelative(12f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(8f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(7f); close()
            }
        }
    }

    val Menu: ImageVector by lazy {
        materialIcon("Menu") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 18f); horizontalLineToRelative(18f); verticalLineToRelative(-2f); horizontalLineTo(3f); verticalLineToRelative(2f); close()
                moveTo(3f, 13f); horizontalLineToRelative(18f); verticalLineToRelative(-2f); horizontalLineTo(3f); verticalLineToRelative(2f); close()
                moveTo(3f, 6f); verticalLineToRelative(2f); horizontalLineToRelative(18f); verticalLineTo(6f); horizontalLineTo(3f); close()
            }
        }
    }

    val MenuOpen: ImageVector by lazy {
        materialIcon("MenuOpen") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 18f); horizontalLineToRelative(13f); verticalLineToRelative(-2f); horizontalLineTo(3f); verticalLineToRelative(2f); close()
                moveTo(3f, 13f); horizontalLineToRelative(10f); verticalLineToRelative(-2f); horizontalLineTo(3f); verticalLineToRelative(2f); close()
                moveTo(3f, 6f); verticalLineToRelative(2f); horizontalLineToRelative(13f); verticalLineTo(6f); horizontalLineTo(3f); close()
                moveTo(21f, 15.59f); lineTo(17.42f, 12f); lineTo(21f, 8.41f)
                lineTo(19.59f, 7f); lineToRelative(-5f, 5f); lineToRelative(5f, 5f); close()
            }
        }
    }

    val ExpandMore: ImageVector by lazy {
        materialIcon("ExpandMore") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16.59f, 8.59f); lineTo(12f, 13.17f); lineTo(7.41f, 8.59f)
                lineTo(6f, 10f); lineToRelative(6f, 6f); lineToRelative(6f, -6f); close()
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        materialIcon("ChevronRight") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 6f); lineTo(8.59f, 7.41f); lineTo(13.17f, 12f)
                lineToRelative(-4.58f, 4.59f); lineTo(10f, 18f); lineToRelative(6f, -6f); close()
            }
        }
    }
}

private fun materialIcon(name: String, block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        name = name,
    ).apply(block).build()
