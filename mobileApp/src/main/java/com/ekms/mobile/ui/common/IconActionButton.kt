package com.ekms.mobile.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R

/**
 * mobileApp UX rework Phase M-D — icon+label action button, mirroring terminalApp's
 * `IconActionButton` pattern (icon+label together, never icon-only). One enum owns
 * icon/label/tone per type, same as terminalApp, so adding a new call site never means
 * touching an existing one. Icons are Lucide (`com.composables:icons-lucide-android`,
 * replacing mobileApp's former `material-icons-extended` dependency — see
 * CLAUDE_MOBILE.md) rendered via `painterResource` (XML vector drawables), not
 * `ImageVector`, since the Android-only Lucide artifact ships drawables, not Compose icons.
 */
enum class MobileActionButtonType {
    /** X icon, neutral/outlined tone — dismiss/decline without applying anything. */
    CANCEL,

    /** Check icon, success (tertiary-mapped) tone — confirm/accept the pending action. */
    CONFIRM,

    /** Plus icon, primary tone — create/submit a new record. */
    ADD,

    /** Shield-off icon, error tone — revokes/removes something already granted, not just a
     *  plain cancel; reserved for genuinely destructive-consequence actions (e.g. Revoke PIN). */
    DESTRUCTIVE,

    /** Log-out icon, neutral/outlined tone — ends the current session. */
    SIGN_OUT,
}

private data class ActionButtonSpec(val iconRes: Int, val defaultLabel: String, val filled: Boolean)

@Composable
fun IconActionButton(
    type: MobileActionButtonType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
) {
    val spec = when (type) {
        MobileActionButtonType.CANCEL -> ActionButtonSpec(R.drawable.lucide_ic_x, "Cancel", filled = false)
        MobileActionButtonType.CONFIRM -> ActionButtonSpec(R.drawable.lucide_ic_check, "Confirm", filled = true)
        MobileActionButtonType.ADD -> ActionButtonSpec(R.drawable.lucide_ic_plus, "Add", filled = true)
        MobileActionButtonType.DESTRUCTIVE -> ActionButtonSpec(R.drawable.lucide_ic_shield_off, "Remove", filled = true)
        MobileActionButtonType.SIGN_OUT -> ActionButtonSpec(R.drawable.lucide_ic_log_out, "Sign out", filled = false)
    }
    val containerColor = when (type) {
        MobileActionButtonType.CANCEL, MobileActionButtonType.SIGN_OUT -> MaterialTheme.colorScheme.surfaceContainerHigh
        MobileActionButtonType.CONFIRM -> MaterialTheme.colorScheme.tertiary
        MobileActionButtonType.ADD -> MaterialTheme.colorScheme.primary
        MobileActionButtonType.DESTRUCTIVE -> MaterialTheme.colorScheme.error
    }
    val contentColor = when (type) {
        MobileActionButtonType.CANCEL, MobileActionButtonType.SIGN_OUT -> MaterialTheme.colorScheme.onSurface
        MobileActionButtonType.CONFIRM -> Color.White
        MobileActionButtonType.ADD -> MaterialTheme.colorScheme.onPrimary
        MobileActionButtonType.DESTRUCTIVE -> MaterialTheme.colorScheme.onError
    }
    val shape = RoundedCornerShape(20.dp)
    val resolvedLabel = label ?: spec.defaultLabel

    if (spec.filled) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        ) {
            Icon(painterResource(spec.iconRes), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(resolvedLabel)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        ) {
            Icon(painterResource(spec.iconRes), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(resolvedLabel)
        }
    }
}
