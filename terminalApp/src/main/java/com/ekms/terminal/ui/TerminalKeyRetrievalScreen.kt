package com.ekms.terminal.ui

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
import com.ekms.terminal.ui.theme.DataReadoutTextStyle
import com.ekms.terminal.ui.theme.StatusTone

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
 * Selecting an available key hands off to the Key Take Flow (CLAUDE.md
 * "Terminal App UX Baseline (Production)" §1, `TerminalKeyTakeScreen`) —
 * a dedicated full-screen takeover, the same pattern Section 3's return
 * flow already uses, so this grid is never visible again until that flow
 * ends. There is therefore no in-grid "pending/releasing" state to track
 * here; a key already marked in [takenKeyIds] is the only reason a cell
 * is disabled.
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
        onClick = if (selectable) { { onTakeKey(key!!) } } else null,
        contentPadding = 10.dp,
    ) {
        Text(
            text = "Node $nodeAddress",
            style = MaterialTheme.typography.labelSmall.merge(DataReadoutTextStyle),
        )
        Text(
            text = key?.displayName ?: "Empty",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
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
        .mapNotNull { slot -> slot.managedKeyId?.let { keyId -> keyById[keyId] }?.let { key -> slot.nodeAddress to key } }
        .sortedBy { (nodeAddress, _) -> nodeAddress }

    if (rows.isEmpty()) {
        Text("No key is available for retrieval right now.")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (nodeAddress, key) ->
            val taken = key.id in takenKeyIds
            val selectable = !taken
            StatusRingCard(
                tone = if (selectable) StatusTone.NORMAL else StatusTone.INACTIVE,
                onClick = if (selectable) { { onTakeKey(key) } } else null,
            ) {
                Text(
                    text = "Node $nodeAddress · ${key.displayName}",
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
