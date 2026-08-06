package com.ekms.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ekms.shared.domain.KeySlot
import com.ekms.shared.domain.ManagedKey
import com.ekms.terminal.ui.theme.StatusTone

/**
 * Key Menu — CLAUDE.md "Terminal App UX Baseline (Production)" multi-key take flow, reached
 * by a Technician/Vendor immediately after login instead of the Super Admin's full unfiltered
 * [TerminalKeyRetrievalScreen]/cabinet grid. Shows only [authorizedKeys] (already filtered by
 * the caller against `AccessGrant` — this screen does no authorization logic of its own, it
 * just displays what it's given) as selectable boxes, supports multi-select, and reuses the
 * same [KeyDisplayMode]/[SoftSegmented] Layout Display / List Display toggle
 * [TerminalKeyRetrievalScreen] already established, rather than a new toggle component.
 *
 * Unlike the cabinet grid (which enumerates every physical node address, assigned or not),
 * this screen only ever shows the specific keys the user may take — node addresses are an
 * implementation detail here, not something a Technician/Vendor needs to see.
 *
 * Phase 9B: applies the Phase 9A design-system pattern to this screen. [SoftSegmented] needed
 * no changes — it already resolved every color via `MaterialTheme.colorScheme`, confirmed by
 * reading it rather than assumed. Grid tiles ([KeyMenuBox]) now use [SoftScanTile] (extended
 * with a `selected` param rather than forked); the confirm button uses [IconActionButton]
 * ([ActionButtonType.ACCEPT]); the empty state ([KeyMenuEmptyState]) replaces a bare `Text` with
 * an icon+message card. [KeyMenuList] (the List Display alternative) is intentionally
 * untouched — it already used the theme-aware [StatusRingCard], and list-style rows are a
 * different affordance from tiles by design, same as [TerminalKeyRetrievalScreen]'s own list
 * view.
 */
@Composable
fun TerminalKeyMenuScreen(
    padding: PaddingValues,
    authorizedKeys: List<ManagedKey>,
    slots: List<KeySlot>,
    takenKeyIds: Set<String>,
    backLabel: String,
    onBack: () -> Unit,
    onConfirmSelection: (List<ManagedKey>) -> Unit,
) {
    var displayMode by rememberSaveable { mutableStateOf(KeyDisplayMode.LAYOUT) }
    var selectedKeyIds by remember { mutableStateOf(emptySet<String>()) }

    // Only a key that is actually assigned to a physical slot can ever be taken — an
    // authorized-but-unassigned key would be a dead-end selection, so it's left out of the
    // menu entirely rather than shown as a box that can never be confirmed.
    val slotByKeyId = remember(slots) { slots.mapNotNull { slot -> slot.managedKeyId?.let { it to slot } }.toMap() }
    val selectableKeys = remember(authorizedKeys, slotByKeyId) {
        authorizedKeys.filter { key -> key.id in slotByKeyId }
    }

    fun toggleSelection(key: ManagedKey) {
        selectedKeyIds = if (key.id in selectedKeyIds) selectedKeyIds - key.id else selectedKeyIds + key.id
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(padding),
    ) {
        val horizontalPadding = if (maxWidth < 640.dp) 16.dp else 28.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1_080.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(onClick = onBack) { Text(backLabel) }
            Text(
                text = "Key Menu",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            if (selectableKeys.isEmpty()) {
                KeyMenuEmptyState()
            } else {
                Text(
                    text = "Select one or more keys, then confirm to take them one at a time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SoftSegmented(
                    leftLabel = "Layout Display",
                    rightLabel = "List Display",
                    leftSelected = displayMode == KeyDisplayMode.LAYOUT,
                    onLeft = { displayMode = KeyDisplayMode.LAYOUT },
                    onRight = { displayMode = KeyDisplayMode.LIST },
                )

                when (displayMode) {
                    KeyDisplayMode.LAYOUT -> KeyMenuGrid(
                        keys = selectableKeys,
                        takenKeyIds = takenKeyIds,
                        selectedKeyIds = selectedKeyIds,
                        onToggle = ::toggleSelection,
                    )

                    KeyDisplayMode.LIST -> KeyMenuList(
                        keys = selectableKeys,
                        takenKeyIds = takenKeyIds,
                        selectedKeyIds = selectedKeyIds,
                        onToggle = ::toggleSelection,
                    )
                }

                // Phase 9B: IconActionButton(ACCEPT) in place of the old SoftPrimaryButton —
                // this is exactly the "confirm a pending selection" case IconActionButton's
                // ACCEPT type was designed for (see IconActionButton.kt).
                IconActionButton(
                    type = ActionButtonType.ACCEPT,
                    label = if (selectedKeyIds.isEmpty()) {
                        "Select a key to continue"
                    } else {
                        "Take ${selectedKeyIds.size} key" + if (selectedKeyIds.size == 1) "" else "s"
                    },
                    onClick = {
                        val chosen = selectableKeys.filter { it.id in selectedKeyIds }
                        if (chosen.isNotEmpty()) onConfirmSelection(chosen)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedKeyIds.isNotEmpty(),
                )
            }
        }
    }
}

/**
 * Phase 9B: replaces the old bare `Text("No keys are currently assigned...")` with an actual
 * designed empty state (icon + title + message), same tokens as the rest of the screen — no
 * hardcoded colors, everything from `MaterialTheme.colorScheme`.
 */
@Composable
private fun KeyMenuEmptyState() {
    SoftCard(contentPadding = 28.dp) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Readability pass: 56dp -> 67dp box, corner 18dp -> 22dp (both x1.2).
            Box(
                modifier = Modifier
                    .size(67.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "No keys assigned",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "No keys are currently assigned to your account. Contact a Super Admin if this seems wrong.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun KeyMenuGrid(
    keys: List<ManagedKey>,
    takenKeyIds: Set<String>,
    selectedKeyIds: Set<String>,
    onToggle: (ManagedKey) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 640.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(keys, key = { it.id }) { key ->
            KeyMenuBox(
                key = key,
                taken = key.id in takenKeyIds,
                selected = key.id in selectedKeyIds,
                onToggle = onToggle,
            )
        }
    }
}

/**
 * Phase 9B: rebuilt on [SoftScanTile] (extended with `selected`, see its own doc) instead of
 * the plain [StatusRingCard] this used before — brings the Phase 9A icon+label/border/elevation
 * tile language to the Key Menu grid. `taken` isn't a new SoftScanTile param: a taken key is
 * shown (not hidden) but non-interactive (`onClick = null`) and dimmed via a plain
 * [androidx.compose.ui.draw.alpha] modifier at the call site — simpler than adding a third
 * visual state to the shared component for a state specific to this one screen.
 */
@Composable
private fun KeyMenuBox(
    key: ManagedKey,
    taken: Boolean,
    selected: Boolean,
    onToggle: (ManagedKey) -> Unit,
) {
    val selectable = !taken
    SoftScanTile(
        title = key.displayName,
        description = when {
            taken -> "Taken"
            selected -> "Selected"
            else -> "Available"
        },
        icon = Icons.Filled.VpnKey,
        selected = selected,
        onClick = if (selectable) { { onToggle(key) } } else null,
        modifier = Modifier
            .heightIn(min = 96.dp)
            .alpha(if (taken) 0.55f else 1f),
    )
}

@Composable
private fun KeyMenuList(
    keys: List<ManagedKey>,
    takenKeyIds: Set<String>,
    selectedKeyIds: Set<String>,
    onToggle: (ManagedKey) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { key ->
            val taken = key.id in takenKeyIds
            val selected = key.id in selectedKeyIds
            StatusRingCard(
                tone = when {
                    taken -> StatusTone.INACTIVE
                    selected -> StatusTone.ATTENTION
                    else -> StatusTone.NORMAL
                },
                onClick = if (!taken) { { onToggle(key) } } else null,
            ) {
                Text(text = key.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    text = when {
                        taken -> "Taken"
                        selected -> "Selected"
                        else -> "Available"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
