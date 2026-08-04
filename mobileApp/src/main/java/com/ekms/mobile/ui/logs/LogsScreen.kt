package com.ekms.mobile.ui.logs

import android.content.Intent
import android.os.Environment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ekms.mobile.data.MobileApiClient
import com.ekms.mobile.data.MobileApiException
import com.ekms.shared.api.ActivityLogRow
import com.ekms.shared.api.ActivitySummaryResponse
import com.ekms.shared.api.AuthUserProfile
import com.ekms.shared.api.ReportCategory
import com.ekms.shared.api.ReportFilterRequest
import com.ekms.shared.api.SiteDto
import com.ekms.shared.api.TerminalDto
import com.ekms.shared.domain.UserRole
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ALL_CATEGORIES = ReportCategory.entries.toList()

private val CATEGORY_LABELS = mapOf(
    ReportCategory.KEY_TAKE to "Key take",
    ReportCategory.KEY_RETURN to "Key return",
    ReportCategory.CABINET_REGISTRATION to "Cabinet reg.",
    ReportCategory.PERSONNEL_REGISTRATION to "Personnel reg.",
)

/**
 * Super Admin Activity Report — filters, summary, list, PDF export (parity with web portal).
 * Hidden for non–Super Admin roles (reports API is `requireSuperAdmin`).
 */
@Composable
fun LogsScreen(
    apiClient: MobileApiClient,
    profile: AuthUserProfile?,
    refreshEpoch: Int = 0,
    onLiveStatus: (serverOk: Boolean, syncing: Boolean) -> Unit = { _, _ -> },
    onNotice: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (profile?.role != UserRole.SUPER_ADMIN) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Activity Report", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Activity logs and PDF export are available to Super Admin accounts only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    var sites by remember { mutableStateOf<List<SiteDto>>(emptyList()) }
    var terminals by remember { mutableStateOf<List<TerminalDto>>(emptyList()) }
    var siteId by remember { mutableStateOf<String?>(null) }
    var terminalId by remember { mutableStateOf<String?>(null) }
    var categories by remember { mutableStateOf(ALL_CATEGORIES) }
    var rangeDays by remember { mutableStateOf<Int?>(30) }
    var items by remember { mutableStateOf<List<ActivityLogRow>>(emptyList()) }
    var summary by remember { mutableStateOf<ActivitySummaryResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var exporting by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    fun fromEpoch(): Long? =
        rangeDays?.let { System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L }

    fun reload() {
        scope.launch {
            loading = true
            loadError = null
            onLiveStatus(true, true)
            try {
                if (sites.isEmpty()) {
                    sites = apiClient.listSites()
                    terminals = apiClient.listTerminals()
                }
                val cats = if (categories.size == ALL_CATEGORIES.size) null else categories
                val from = fromEpoch()
                items = apiClient.listActivityLogs(
                    siteId = siteId,
                    terminalId = terminalId,
                    fromEpochMillis = from,
                    categories = cats,
                )
                summary = apiClient.getActivitySummary(
                    siteId = siteId,
                    terminalId = terminalId,
                    fromEpochMillis = from,
                    categories = cats,
                )
                onLiveStatus(true, false)
            } catch (e: MobileApiException) {
                loadError = e.message
                onLiveStatus(e.status !in 500..599, false)
                onNotice(e.message)
            } catch (e: Exception) {
                loadError = e.message ?: "Failed to load activity"
                onLiveStatus(false, false)
                onNotice(loadError!!)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(refreshEpoch, siteId, terminalId, categories, rangeDays) {
        reload()
    }

    val cabinetOptions = remember(siteId, terminals) {
        if (siteId == null) terminals else terminals.filter { it.siteId == siteId }
    }

    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Activity Report", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Key take/return, cabinet registration, and personnel registration.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Period", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", null to "All").forEach { (days, label) ->
                FilterChip(
                    selected = rangeDays == days,
                    onClick = { rangeDays = days },
                    label = { Text(label) },
                )
            }
        }

        Text("Unit", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = siteId == null,
                onClick = {
                    siteId = null
                    terminalId = null
                },
                label = { Text("All") },
            )
            sites.forEach { site ->
                FilterChip(
                    selected = siteId == site.id,
                    onClick = {
                        siteId = site.id
                        terminalId = null
                    },
                    label = { Text(site.name) },
                )
            }
        }

        if (cabinetOptions.isNotEmpty()) {
            Text("Cabinet", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = terminalId == null,
                    onClick = { terminalId = null },
                    label = { Text("All") },
                )
                cabinetOptions.forEach { term ->
                    FilterChip(
                        selected = terminalId == term.id,
                        onClick = { terminalId = term.id },
                        label = { Text(term.name) },
                    )
                }
            }
        }

        Text("Categories", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ALL_CATEGORIES.forEach { cat ->
                FilterChip(
                    selected = categories.contains(cat),
                    onClick = {
                        categories = if (categories.contains(cat)) {
                            val next = categories - cat
                            if (next.isEmpty()) categories else next
                        } else {
                            categories + cat
                        }
                    },
                    label = { Text(CATEGORY_LABELS[cat] ?: cat.name) },
                )
            }
        }

        summary?.let { sum ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryChip("Total", sum.total)
                ALL_CATEGORIES.forEach { cat ->
                    SummaryChip(CATEGORY_LABELS[cat] ?: cat.name, sum.byCategory[cat] ?: 0)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { reload() }, enabled = !loading) {
                Text("Refresh")
            }
            Button(
                onClick = {
                    scope.launch {
                        exporting = true
                        try {
                            val cats = if (categories.size == ALL_CATEGORIES.size) null else categories
                            val job = apiClient.createActivityLogsExport(
                                ReportFilterRequest(
                                    siteId = siteId,
                                    terminalId = terminalId,
                                    fromEpochMillis = fromEpoch(),
                                    categories = cats,
                                    limit = 500,
                                ),
                            )
                            val path = job.downloadPath
                                ?: throw MobileApiException(0, "Export job missing download path")
                            val bytes = apiClient.downloadReportExportBytes(path)
                            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                                ?: context.filesDir
                            val file = File(dir, "activity-logs-${System.currentTimeMillis()}.pdf")
                            file.writeBytes(bytes)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val view = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/pdf")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(view, "Open activity PDF"))
                            onNotice("PDF ready (${job.rowCount} rows)")
                        } catch (e: Exception) {
                            onNotice(e.message ?: "PDF export failed")
                        } finally {
                            exporting = false
                        }
                    }
                },
                enabled = !exporting && !loading,
            ) {
                Text(if (exporting) "Exporting…" else "Export PDF")
            }
        }

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(8.dp))
        }
        loadError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        if (!loading && items.isEmpty()) {
            Text("No activity matches these filters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        items.forEach { row ->
            ActivityRowCard(row, dateFmt)
        }
    }
}

@Composable
private fun SummaryChip(label: String, value: Int) {
    Card {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ActivityRowCard(row: ActivityLogRow, dateFmt: SimpleDateFormat) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                CATEGORY_LABELS[row.category] ?: row.category.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(row.eventType, fontWeight = FontWeight.SemiBold)
            Text(dateFmt.format(Date(row.occurredAtEpochMillis)), style = MaterialTheme.typography.bodySmall)
            val where = listOfNotNull(row.siteName, row.terminalName).joinToString(" · ")
            if (where.isNotBlank()) {
                Text(where, style = MaterialTheme.typography.bodySmall)
            }
            val actor = row.actorName ?: row.actorUserId
            if (!actor.isNullOrBlank()) {
                Text("Actor: $actor", style = MaterialTheme.typography.bodySmall)
            }
            if (!row.detail.isNullOrBlank()) {
                Text(row.detail!!, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
