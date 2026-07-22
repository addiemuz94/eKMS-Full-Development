package com.ekms.terminal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.ekms.shared.domain.ManagedTerminalOption
import com.ekms.terminal.hardware.VideoRecordingController

/**
 * Smart Key Cabinet User Manual V2.1, Section 2 — Key retrieval.
 *
 * One retrieval process, however the caller reached it: an ordinary user is
 * routed here directly after login, an administrator reaches the same
 * screen through the management menu. Layout Display and List Display are
 * only two ways to browse the same key set — both call [onTakeKey] the
 * moment a key is selected, with no dialog or confirmation step in between.
 * There is no recording notice or any other banner on this screen: when
 * [videoRecordingEnabled] is true (the Admin Menu's "Key retrieval video"
 * toggle), a recording session starts and stops with this screen silently —
 * see [VideoRecordingController] for why start()/stop() are still no-ops.
 *
 * Keys and slots come from the shared ManagedKey/KeySlot model so this
 * screen and the Website eventually read the same backend-synced data;
 * no terminal-only key/slot type is introduced here.
 *
 * Selecting a key now sends a real Magnet Engage (0x13) command to its
 * node and confirms removal via Test Micro Switch (0x16) — see
 * `CabinetHardwareController.releaseKeyForPickup`, phase 9 — which takes
 * long enough to be visibly async. [pendingKeyId] identifies the key
 * currently mid-release, if any; every other key is disabled for tap while
 * it's set, both to avoid a confusing double-dispatch and to make the
 * phase-7 one-electromagnet-at-a-time guard a backstop rather than the
 * primary way concurrent releases are prevented.
 */
@Composable
fun TerminalKeyRetrievalScreen(
    padding: PaddingValues,
    terminal: ManagedTerminalOption,
    keys: List<ManagedKey>,
    slots: List<KeySlot>,
    takenKeyIds: Set<String>,
    pendingKeyId: String?,
    videoRecordingEnabled: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    onTakeKey: (ManagedKey) -> Unit,
) {
    var displayMode by rememberSaveable { mutableStateOf(KeyDisplayMode.LAYOUT) }
    val keyById = remember(keys) { keys.associateBy { it.id } }
    val slotsByNode = remember(slots) { slots.associateBy { it.nodeAddress } }
    val videoRecorder = remember { VideoRecordingController() }

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
            OutlinedButton(onClick = onBack) { Text(backLabel) }
            Text("Take a key", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DisplayModeButton(
                    label = "Layout Display",
                    selected = displayMode == KeyDisplayMode.LAYOUT,
                    onClick = { displayMode = KeyDisplayMode.LAYOUT },
                )
                DisplayModeButton(
                    label = "List Display",
                    selected = displayMode == KeyDisplayMode.LIST,
                    onClick = { displayMode = KeyDisplayMode.LIST },
                )
            }

            when (displayMode) {
                KeyDisplayMode.LAYOUT -> KeyLayoutGrid(
                    terminal = terminal,
                    keyById = keyById,
                    slotsByNode = slotsByNode,
                    takenKeyIds = takenKeyIds,
                    pendingKeyId = pendingKeyId,
                    onTakeKey = onTakeKey,
                )

                KeyDisplayMode.LIST -> KeyRetrievalList(
                    slots = slots,
                    keyById = keyById,
                    takenKeyIds = takenKeyIds,
                    pendingKeyId = pendingKeyId,
                    onTakeKey = onTakeKey,
                )
            }
        }
    }
}

private enum class KeyDisplayMode {
    LAYOUT,
    LIST,
}

@Composable
private fun DisplayModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun KeyLayoutGrid(
    terminal: ManagedTerminalOption,
    keyById: Map<String, ManagedKey>,
    slotsByNode: Map<Int, KeySlot>,
    takenKeyIds: Set<String>,
    pendingKeyId: String?,
    onTakeKey: (ManagedKey) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 640.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items((1..terminal.configuredSlotCount).toList()) { nodeAddress ->
            val key = slotsByNode[nodeAddress]?.managedKeyId?.let { keyById[it] }
            KeyNodeCell(
                nodeAddress = nodeAddress,
                key = key,
                taken = key != null && key.id in takenKeyIds,
                pending = key != null && key.id == pendingKeyId,
                anyPending = pendingKeyId != null,
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
    pending: Boolean,
    anyPending: Boolean,
    onTakeKey: (ManagedKey) -> Unit,
) {
    val selectable = key != null && !taken && !anyPending
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selectable) Modifier.clickable { onTakeKey(key!!) } else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = when {
                pending -> MaterialTheme.colorScheme.secondaryContainer
                selectable -> MaterialTheme.colorScheme.primaryContainer
                taken -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Node $nodeAddress", style = MaterialTheme.typography.labelSmall)
            Text(
                text = key?.displayName ?: "Empty",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            if (pending) {
                Text("Releasing…", style = MaterialTheme.typography.labelSmall)
            } else if (taken) {
                Text("Taken", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun KeyRetrievalList(
    slots: List<KeySlot>,
    keyById: Map<String, ManagedKey>,
    takenKeyIds: Set<String>,
    pendingKeyId: String?,
    onTakeKey: (ManagedKey) -> Unit,
) {
    val rows = slots
        .mapNotNull { slot -> slot.managedKeyId?.let { keyId -> keyById[keyId] }?.let { key -> slot.nodeAddress to key } }
        .sortedBy { (nodeAddress, _) -> nodeAddress }

    if (rows.isEmpty()) {
        Text("No key is available for retrieval right now.")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (nodeAddress, key) ->
            val taken = key.id in takenKeyIds
            val pending = key.id == pendingKeyId
            val selectable = !taken && pendingKeyId == null
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (selectable) Modifier.clickable { onTakeKey(key) } else Modifier),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        pending -> MaterialTheme.colorScheme.secondaryContainer
                        taken -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Node $nodeAddress · ${key.displayName}", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (pending) "Releasing…" else if (taken) "Taken" else "Available",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
