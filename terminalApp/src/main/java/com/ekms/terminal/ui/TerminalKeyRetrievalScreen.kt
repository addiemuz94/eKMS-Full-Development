package com.ekms.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ekms.shared.domain.KeySlot
import com.ekms.shared.domain.ManagedKey
import com.ekms.shared.domain.ManagedTerminalOption
import com.ekms.terminal.hardware.VideoRecordingController
import com.ekms.terminal.ui.theme.StatusTone
import com.ekms.terminal.ui.theme.readout

/**
 * Smart Key Cabinet User Manual V2.1, Section 2 — Key retrieval (soft M3).
 * Layout / List labels and take-on-select behaviour unchanged.
 */
@Composable
fun TerminalKeyRetrievalScreen(
    padding: PaddingValues,
    terminal: ManagedTerminalOption,
    keys: List<ManagedKey>,
    slots: List<KeySlot>,
    takenKeyIds: Set<String>,
    videoRecordingEnabled: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    onTakeKey: (ManagedKey) -> Unit,
) {
    var displayMode by rememberSaveable { mutableStateOf(KeyDisplayMode.LAYOUT) }
    val keyById = remember(keys) { keys.associateBy { it.id } }
    val slotsByNode = remember(slots) { slots.associateBy { it.nodeAddress } }
    val videoRecorder = remember { VideoRecordingController() }

    val availableCount = remember(slots, keyById, takenKeyIds) {
        slots.count { slot ->
            val key = slot.managedKeyId?.let { keyById[it] }
            key != null && key.id !in takenKeyIds
        }
    }

    DisposableEffect(videoRecordingEnabled) {
        if (videoRecordingEnabled) {
            videoRecorder.start("key_retrieval")
        }
        onDispose { videoRecorder.stop() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
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
                text = "Take a key",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SoftStat("Available", availableCount.toString(), Modifier.weight(1f))
                SoftStat("Taken", takenKeyIds.size.toString(), Modifier.weight(1f))
                SoftStat("Slots", terminal.configuredSlotCount.toString(), Modifier.weight(1f))
            }

            SoftSegmented(
                leftLabel = "Layout Display",
                rightLabel = "List Display",
                leftSelected = displayMode == KeyDisplayMode.LAYOUT,
                onLeft = { displayMode = KeyDisplayMode.LAYOUT },
                onRight = { displayMode = KeyDisplayMode.LIST },
            )

            when (displayMode) {
                KeyDisplayMode.LAYOUT -> KeyLayoutGrid(
                    terminal = terminal,
                    keyById = keyById,
                    slotsByNode = slotsByNode,
                    takenKeyIds = takenKeyIds,
                    onTakeKey = onTakeKey,
                )

                KeyDisplayMode.LIST -> KeyRetrievalList(
                    slots = slots,
                    keyById = keyById,
                    takenKeyIds = takenKeyIds,
                    onTakeKey = onTakeKey,
                )
            }
        }
    }
}

private enum class KeyDisplayMode { LAYOUT, LIST }

@Composable
private fun SoftStat(label: String, value: String, modifier: Modifier = Modifier) {
    SoftCard(modifier = modifier, contentPadding = 12.dp) {
        Text(value, style = MaterialTheme.typography.titleLarge.readout(), fontWeight = FontWeight.Medium)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SoftSegmented(
    leftLabel: String,
    rightLabel: String,
    leftSelected: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SoftSegmentOption(leftLabel, leftSelected, onLeft, Modifier.weight(1f))
        SoftSegmentOption(rightLabel, !leftSelected, onRight, Modifier.weight(1f))
    }
}

@Composable
private fun SoftSegmentOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun KeyLayoutGrid(
    terminal: ManagedTerminalOption,
    keyById: Map<String, ManagedKey>,
    slotsByNode: Map<Int, KeySlot>,
    takenKeyIds: Set<String>,
    onTakeKey: (ManagedKey) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 640.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items((1..terminal.configuredSlotCount).toList()) { nodeAddress ->
            val key = slotsByNode[nodeAddress]?.managedKeyId?.let { keyById[it] }
            KeyNodeCell(
                nodeAddress = nodeAddress,
                key = key,
                taken = key != null && key.id in takenKeyIds,
                onTakeKey = onTakeKey,
            )
        }
    }
}

@Composable
private fun KeyNodeCell(
    nodeAddress: Int,
    key: ManagedKey?,
    taken: Boolean,
    onTakeKey: (ManagedKey) -> Unit,
) {
    val selectable = key != null && !taken
    StatusRingCard(
        tone = if (selectable) StatusTone.NORMAL else StatusTone.INACTIVE,
        onClick = if (selectable) {
            { onTakeKey(key!!) }
        } else {
            null
        },
        contentPadding = 12.dp,
    ) {
        if (selectable) {
            Text(
                text = "Free",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "%02d".format(nodeAddress),
            style = MaterialTheme.typography.headlineSmall.readout(),
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = key?.displayName ?: "Empty",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
        )
        if (taken) {
            Text("Taken", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun KeyRetrievalList(
    slots: List<KeySlot>,
    keyById: Map<String, ManagedKey>,
    takenKeyIds: Set<String>,
    onTakeKey: (ManagedKey) -> Unit,
) {
    val rows = slots
        .mapNotNull { slot ->
            slot.managedKeyId?.let { keyId -> keyById[keyId] }?.let { key -> slot.nodeAddress to key }
        }
        .sortedBy { (nodeAddress, _) -> nodeAddress }

    if (rows.isEmpty()) {
        Text("No key is available for retrieval right now.")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (nodeAddress, key) ->
            val taken = key.id in takenKeyIds
            StatusRingCard(
                tone = if (!taken) StatusTone.NORMAL else StatusTone.INACTIVE,
                onClick = if (!taken) {
                    { onTakeKey(key) }
                } else {
                    null
                },
            ) {
                Text(
                    text = "Node %02d · %s".format(nodeAddress, key.displayName),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (taken) "Taken" else "Available",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
