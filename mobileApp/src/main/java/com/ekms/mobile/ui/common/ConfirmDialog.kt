package com.ekms.mobile.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/**
 * mobileApp UX rework Phase M-E: the shared [HazeState] `.hazeSource(state)` is attached to in
 * `SuperAdminCompanionApp.kt`'s main Scaffold content. Nullable/defaults to null so
 * [ConfirmDialog] degrades to a plain (unblurred) dialog rather than crashing if it's ever
 * rendered without a provider above it — this should not happen in practice (provided once at
 * the app root, above every screen that can open a confirm dialog), but there's no reason to
 * make a defensive null-check fail loudly here when a plain dialog is a perfectly fine fallback.
 */
val LocalHazeState: ProvidableCompositionLocal<HazeState?> = compositionLocalOf { null }

/**
 * Themed confirm/cancel gate for destructive or hard-to-undo actions — mobileApp UX rework
 * Phase M-C, mirroring web's `ConfirmDialog`/`useConfirm()` pattern (`web/src/components/ui/
 * ConfirmDialog.tsx`): never dismisses on a stray backdrop tap ([DialogProperties.dismissOnClickOutside]
 * false), only the two explicit buttons (or the system back gesture, which maps to Cancel) close
 * it. Deliberately pure UI — the caller's [onConfirm] is expected to already own busy-state/
 * network-call handling exactly as before this pass (see [ConfirmRequest]); this dialog only
 * gates *whether* that existing call site fires, it does not wrap or change it.
 *
 * Phase M-E glassmorphism: blurs the app content behind it via the shared [LocalHazeState].
 * Per Haze's own documented dialog caveat, deliberately NOT using Haze's built-in tint feature
 * here (`tints = emptyList()` overrides whatever default tint the style would otherwise carry)
 * — using it would double up with this dialog's own scrim, since Android renders a Dialog in a
 * separate window with its own dim/backdrop. The frosted look instead comes from blur alone
 * plus this dialog's own `containerColor` set to a translucent surface tone directly.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String = "Confirm",
    cancelLabel: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hazeState = LocalHazeState.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = if (hazeState != null) {
            Modifier.hazeEffect(state = hazeState) { tints = emptyList() }
        } else {
            Modifier
        },
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}

/**
 * One pending confirm request — screens hold a single `remember { mutableStateOf<ConfirmRequest?>
 * (null) }` and set it instead of calling a destructive action directly; [ConfirmDialogHost]
 * renders the gate when non-null. Keeps every one of Phase M-C's 5 call sites to a one-line
 * change (wrap the existing onClick body in a `ConfirmRequest { ... }` instead of calling it
 * inline) rather than hand-rolling dialog state per screen.
 */
data class ConfirmRequest(
    val title: String,
    val body: String,
    val confirmLabel: String = "Confirm",
    val cancelLabel: String = "Cancel",
    val onConfirm: () -> Unit,
)

@Composable
fun ConfirmDialogHost(request: ConfirmRequest?, onDismiss: () -> Unit) {
    if (request != null) {
        ConfirmDialog(
            title = request.title,
            body = request.body,
            confirmLabel = request.confirmLabel,
            cancelLabel = request.cancelLabel,
            onConfirm = request.onConfirm,
            onDismiss = onDismiss,
        )
    }
}
