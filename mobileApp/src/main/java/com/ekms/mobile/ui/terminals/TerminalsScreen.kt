package com.ekms.mobile.ui.terminals

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ekms.mobile.data.MobileApiClient
import com.ekms.shared.api.SiteDto
import com.ekms.shared.api.TerminalDto
import com.ekms.shared.domain.TerminalConnectionState
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Scoped terminal list + OSM map (osmdroid native View, see CLAUDE_MOBILE.md's Terminals-tab
 * map bug entry for why this replaced an earlier WebView+Leaflet implementation). Pin tap /
 * Directions opens the native Maps chooser — no in-app turn-by-turn.
 */
@Composable
fun TerminalsScreen(
    apiClient: MobileApiClient,
    refreshEpoch: Int = 0,
    onLiveStatus: (serverOk: Boolean, syncing: Boolean) -> Unit = { _, _ -> },
    onNotice: (String) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var terminals by remember { mutableStateOf<List<TerminalDto>>(emptyList()) }
    var sitesById by remember { mutableStateOf<Map<String, SiteDto>>(emptyMap()) }
    var hasData by remember { mutableStateOf(false) }

    LaunchedEffect(refreshEpoch) {
        val showSpinner = !hasData
        if (showSpinner) loading = true
        onLiveStatus(true, true)
        loadError = null
        try {
            val sites = apiClient.listSites()
            sitesById = sites.associateBy { it.id }
            terminals = apiClient.listTerminals()
            hasData = true
            onLiveStatus(true, false)
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to load terminals."
            onLiveStatus(false, false)
            if (!hasData) onNotice(loadError!!)
        } finally {
            loading = false
        }
    }

    fun openDirections(lat: Double, lng: Double, label: String) {
        val geo = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
        val nav = Uri.parse("google.navigation:q=$lat,$lng")
        val intent = Intent(Intent.ACTION_VIEW, geo).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(Intent(Intent.ACTION_VIEW, nav)))
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Open in Maps"))
        } catch (e: Exception) {
            onNotice(e.message ?: "No maps app available.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Terminals", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Key cabinets at your permitted locations. Tap a pin for directions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            loading -> CircularProgressIndicator()
            loadError != null && !hasData -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
            terminals.isEmpty() -> Text(
                "No terminals found for your locations.",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {
                val withCoords = terminals.filter { it.latitude != null && it.longitude != null }
                if (withCoords.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                        ScopedTerminalsMap(
                            terminals = withCoords,
                            onPinTap = { t ->
                                openDirections(t.latitude!!, t.longitude!!, t.name)
                            },
                        )
                    }
                } else {
                    Text(
                        "No mapped coordinates yet — list only. Add lat/lng on the portal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                terminals.forEach { terminal ->
                    MobileTerminalCard(
                        terminal = terminal,
                        siteName = sitesById[terminal.siteId]?.name ?: terminal.siteId,
                        onDirections = {
                            val lat = terminal.latitude
                            val lng = terminal.longitude
                            if (lat == null || lng == null) {
                                onNotice("This cabinet has no map coordinates.")
                            } else {
                                openDirections(lat, lng, terminal.name)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileTerminalCard(
    terminal: TerminalDto,
    siteName: String,
    onDirections: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(terminal.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(siteName, style = MaterialTheme.typography.bodyMedium)
            Text("Status: ${terminal.connectionState.mobileLabel}", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (terminal.paired) "Paired" else "Not paired",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (terminal.latitude != null && terminal.longitude != null) {
                OutlinedButton(onClick = onDirections) {
                    Text("Directions")
                }
            }
        }
    }
}

@Composable
private fun ScopedTerminalsMap(
    terminals: List<TerminalDto>,
    onPinTap: (TerminalDto) -> Unit,
) {
    // Fresh MapView per tab visit — NOT a shared/reused instance (see CLAUDE_MOBILE.md's
    // Terminals-tab map bug entry: a process-lifetime singleton was tried and crashed with a
    // real, hardware-confirmed NullPointerException in osmdroid's Marker constructor, because
    // Android's View framework calls MapView.onDetachedFromWindow() automatically on every
    // tab-away regardless of whether we call onDetach() ourselves — osmdroid's own internal
    // state does not survive that detach/reattach cycle, so reusing an instance after even one
    // detach is unsafe). A fresh instance every visit is never reused, so it can never hit a
    // stale/detached internal state. Deliberately NOT calling mapView.onDetach() ourselves in
    // onDispose below — the framework's automatic onDetachedFromWindow() already tears the
    // instance down once it's discarded; an explicit second onDetach() call added nothing but
    // risk (this is also how the instance now-discarded per visit avoided being reused, versus
    // the very first pass, which called onDetach() explicitly to avoid a resource leak instead —
    // that leak concern doesn't apply here since a never-reused, unreferenced MapView instance
    // has nothing else holding it alive once discarded).
    val context = LocalContext.current
    var satelliteActive by remember { mutableStateOf(true) }
    val mapKey = terminals.joinToString { "${it.id}:${it.latitude}:${it.longitude}" }
    val mapView = remember {
        // Required by OSM's tile usage policy — a missing/generic user agent gets tile
        // requests blocked.
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setMultiTouchControls(true)
            setTileSource(TileSourceFactory.MAPNIK)
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    LaunchedEffect(satelliteActive) {
        mapView.setTileSource(if (satelliteActive) EsriSatelliteTileSource else TileSourceFactory.MAPNIK)
        mapView.invalidate()
    }

    LaunchedEffect(mapKey) {
        mapView.overlays.clear()
        val points = terminals.mapNotNull { t ->
            val lat = t.latitude
            val lng = t.longitude
            if (lat != null && lng != null) GeoPoint(lat, lng) else null
        }
        terminals.forEach { terminal ->
            val lat = terminal.latitude
            val lng = terminal.longitude
            if (lat == null || lng == null) return@forEach
            val marker = Marker(mapView)
            marker.position = GeoPoint(lat, lng)
            marker.title = terminal.name
            marker.setOnMarkerClickListener { _, _ ->
                onPinTap(terminal)
                true
            }
            mapView.overlays.add(marker)
        }
        mapView.invalidate()
        when {
            points.size == 1 -> {
                mapView.controller.setZoom(14.0)
                mapView.controller.setCenter(points[0])
            }
            points.size > 1 -> {
                mapView.post {
                    mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, 24)
                }
            }
            else -> {
                mapView.controller.setZoom(5.0)
                mapView.controller.setCenter(GeoPoint(4.2, 109.5))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView })
        BasemapToggle(
            satelliteActive = satelliteActive,
            onSelect = { satelliteActive = it },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
        )
    }
}

/** Native replacement for the old HTML/CSS pill toggle — same Satellite/Map two-state UI. */
@Composable
private fun BasemapToggle(
    satelliteActive: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White),
    ) {
        BasemapToggleButton("Satellite", active = satelliteActive, onClick = { onSelect(true) })
        BasemapToggleButton("Map", active = !satelliteActive, onClick = { onSelect(false) })
    }
}

@Composable
private fun BasemapToggleButton(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(if (active) Color(0xFF1A73E8) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (active) Color.White else Color(0xFF333333),
        style = MaterialTheme.typography.labelMedium,
    )
}

/**
 * osmdroid's [TileSourceFactory] has no built-in Esri World Imagery source, and its generic
 * [org.osmdroid.tileprovider.tilesource.XYTileSource] hardcodes a z/x/y URL ordering — this
 * server uses z/y/x (`.../tile/{z}/{y}/{x}`), so a direct subclass override is required. Same
 * tile server/path this screen used before the osmdroid rewrite (see CLAUDE_MOBILE.md).
 */
private object EsriSatelliteTileSource : OnlineTileSourceBase(
    "EsriWorldImagery",
    0,
    19,
    256,
    "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Tiles © Esri",
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${getBaseUrl()}$zoom/$y/$x"
    }
}

private val TerminalConnectionState.mobileLabel: String
    get() = when (this) {
        TerminalConnectionState.UNKNOWN -> "Unknown"
        TerminalConnectionState.ONLINE -> "Online"
        TerminalConnectionState.OFFLINE -> "Offline"
        TerminalConnectionState.SETUP_REQUIRED -> "Setup required"
    }
