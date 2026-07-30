package com.ekms.terminal.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ekms.terminal.ui.theme.LocalAudioClick
import com.ekms.terminal.ui.theme.LocalEkmsColors

/**
 * Actions [IconActionButton] currently renders. Add new cases here (EDIT/DELETE/DONE/...)
 * as they're needed — icon/label/tone all resolve from this one enum inside the component,
 * so adding a case never requires touching call sites that already use a different one.
 */
enum class ActionButtonType {
    /** X icon, neutral/destructive tone — dismiss without applying anything. */
    CANCEL,

    /** Check icon, success tone — confirm/accept the pending action. */
    ACCEPT,

    /** + icon, primary tone — create a new record. */
    ADD,
}

private data class ActionButtonSpec(
    val icon: ImageVector,
    val defaultLabel: String,
    val filled: Boolean,
)

/**
 * One reusable icon+label action button (Phase 9 design-system rework), replacing ad hoc
 * Button()/TextButton() calls for CANCEL/ACCEPT/ADD-style actions. Icon+label together
 * deliberately, never icon-only — a short label keeps these legible on a kiosk touchscreen.
 * This pass wires it into a pilot Admin Menu confirm/cancel dialog only; it is not yet a
 * mass replacement for every button in the app (see CLAUDE.md Phase 9 scope).
 */
@Composable
fun IconActionButton(
    type: ActionButtonType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    // Click-sound opt-out escape hatch (added for the app-wide click-sound pass). Defaults to
    // true so every call site gets click feedback for free. Was briefly false at
    // TerminalCloseToDeadlineScreen's call site pending a product decision on whether a click
    // sound overlapping the Take/Return Flow beep-loop/voice-line HAL interaction (this device
    // has hit that bug class twice) was acceptable there — confirmed acceptable, so that call
    // site now uses this default too. Left in place as a general opt-out for any future call
    // site that needs one, not currently exercised by any of them.
    playSound: Boolean = true,
) {
    val colors = LocalEkmsColors.current
    val playClick = LocalAudioClick.current
    val wrappedOnClick: () -> Unit = { if (playSound) playClick(); onClick() }
    val spec = when (type) {
        ActionButtonType.CANCEL -> ActionButtonSpec(Icons.Filled.Close, "Cancel", filled = false)
        ActionButtonType.ACCEPT -> ActionButtonSpec(Icons.Filled.Check, "Confirm", filled = true)
        ActionButtonType.ADD -> ActionButtonSpec(Icons.Filled.Add, "Add", filled = true)
    }
    val containerColor = when (type) {
        ActionButtonType.CANCEL -> MaterialTheme.colorScheme.surfaceContainerHigh
        ActionButtonType.ACCEPT -> colors.success
        ActionButtonType.ADD -> colors.primary
    }
    val contentColor = when (type) {
        ActionButtonType.CANCEL -> MaterialTheme.colorScheme.onSurface
        ActionButtonType.ACCEPT, ActionButtonType.ADD -> Color.White
    }
    val shape = RoundedCornerShape(20.dp)
    val resolvedLabel = label ?: spec.defaultLabel

    if (spec.filled) {
        Button(
            onClick = wrappedOnClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = containerColor.copy(alpha = 0.38f),
                disabledContentColor = contentColor,
            ),
        ) {
            Icon(spec.icon, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(resolvedLabel)
        }
    } else {
        OutlinedButton(
            onClick = wrappedOnClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        ) {
            Icon(spec.icon, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(resolvedLabel)
        }
    }
}
