package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
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
            Text(
                text = if (selectableKeys.isEmpty()) {
                    "No keys are currently assigned to your account."
                } else {
                    "Select one or more keys, then confirm to take them one at a time."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (selectableKeys.isNotEmpty()) {
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

                SoftPrimaryButton(
                    text = if (selectedKeyIds.isEmpty()) {
                        "Select a key to continue"
                    } else {
                        "Take ${selectedKeyIds.size} key" + if (selectedKeyIds.size == 1) "" else "s"
                    },
                    onClick = {
                        val chosen = selectableKeys.filter { it.id in selectedKeyIds }
                        if (chosen.isNotEmpty()) onConfirmSelection(chosen)
                    },
                    enabled = selectedKeyIds.isNotEmpty(),
                )
            }
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

/** "Medium-sized selection box" — bigger than the cabinet grid's compact node cells, name-first. */
@Composable
private fun KeyMenuBox(
    key: ManagedKey,
    taken: Boolean,
    selected: Boolean,
    onToggle: (ManagedKey) -> Unit,
) {
    val selectable = !taken
    StatusRingCard(
        tone = when {
            taken -> StatusTone.INACTIVE
            selected -> StatusTone.ATTENTION
            else -> StatusTone.NORMAL
        },
        onClick = if (selectable) { { onToggle(key) } } else null,
        contentPadding = 16.dp,
        modifier = Modifier.heightIn(min = 96.dp),
    ) {
        if (selected) {
            Text(
                text = "Selected",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        } else if (taken) {
            Text(text = "Taken", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            text = key.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
        )
    }
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
