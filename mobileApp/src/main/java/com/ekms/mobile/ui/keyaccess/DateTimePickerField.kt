package com.ekms.mobile.ui.keyaccess

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val displayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm")

/**
 * Tap-to-open Material date picker, then time picker. Stores a local-zone epoch millis.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerField(
    label: String,
    epochMillis: Long,
    onEpochMillisChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }

    val zoned = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val display = displayFmt.format(zoned)

    fun openPicker() {
        showDate = true
    }

    OutlinedTextField(
        value = display,
        onValueChange = {},
        readOnly = true,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = ::openPicker),
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = ::openPicker) {
                Icon(Icons.Filled.Schedule, contentDescription = "Pick date and time")
            }
        },
        supportingText = { Text("Tap to choose date, then time") },
    )

    if (showDate) {
        // Material DatePicker uses UTC midnight millis for calendar days.
        val utcDateMillis = zoned.toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = utcDateMillis)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = dateState.selectedDateMillis
                        if (millis != null) {
                            pendingDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            showDate = false
                            showTime = true
                        }
                    },
                ) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTime) {
        val timeState = rememberTimePickerState(
            initialHour = zoned.hour,
            initialMinute = zoned.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = pendingDate ?: zoned.toLocalDate()
                        val combined = LocalDateTime.of(
                            date,
                            LocalTime.of(timeState.hour, timeState.minute),
                        )
                        onEpochMillisChange(combined.atZone(zone).toInstant().toEpochMilli())
                        showTime = false
                        pendingDate = null
                    },
                ) { Text("Set") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTime = false
                        pendingDate = null
                    },
                ) { Text("Cancel") }
            },
            title = { Text("Select time") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        pendingDate?.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
                            ?: "Selected date",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TimePicker(state = timeState)
                }
            },
        )
    }
}

fun defaultPickupEpochMillis(): Long =
    LocalDateTime.now()
        .plusHours(1)
        .withMinute(0)
        .withSecond(0)
        .withNano(0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

fun defaultReturnEpochMillis(): Long =
    LocalDateTime.now()
        .plusHours(5)
        .withMinute(0)
        .withSecond(0)
        .withNano(0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
