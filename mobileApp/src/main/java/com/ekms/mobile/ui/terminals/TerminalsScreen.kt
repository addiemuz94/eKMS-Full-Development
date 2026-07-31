package com.ekms.mobile.ui.terminals

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ekms.mobile.data.MobileApiClient
import com.ekms.shared.api.SiteDto
import com.ekms.shared.api.TerminalDto
import com.ekms.shared.domain.TerminalConnectionState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scoped terminal list + OSM map (Leaflet in WebView). Pin tap / Directions opens the native
 * Maps chooser — no in-app turn-by-turn.
 */
@Composable
fun TerminalsScreen(
    apiClient: MobileApiClient,
    onNotice: (String) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var terminals by remember { mutableStateOf<List<TerminalDto>>(emptyList()) }
    var sitesById by remember { mutableStateOf<Map<String, SiteDto>>(emptyMap()) }

    LaunchedEffect(Unit) {
        loading = true
        loadError = null
        try {
            val sites = apiClient.listSites()
            sitesById = sites.associateBy { it.id }
            terminals = apiClient.listTerminals()
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't load terminals."
            onNotice(loadError!!)
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
            loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ScopedTerminalsMap(
    terminals: List<TerminalDto>,
    onPinTap: (TerminalDto) -> Unit,
) {
    val terminalsRef = remember { java.util.concurrent.atomic.AtomicReference(terminals) }
    val tapRef = remember { java.util.concurrent.atomic.AtomicReference(onPinTap) }
    terminalsRef.set(terminals)
    tapRef.set(onPinTap)
    val mapKey = terminals.joinToString { "${it.id}:${it.latitude}:${it.longitude}" }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                webViewClient = WebViewClient()
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun openTerminal(id: String) {
                            val t = terminalsRef.get().firstOrNull { it.id == id } ?: return
                            tapRef.get().invoke(t)
                        }
                    },
                    "EkmsBridge",
                )
                tag = mapKey
                loadDataWithBaseURL(
                    "https://unpkg.com/",
                    buildLeafletHtml(terminalsRef.get()),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        },
        update = { webView ->
            if (webView.tag != mapKey) {
                webView.tag = mapKey
                webView.loadDataWithBaseURL(
                    "https://unpkg.com/",
                    buildLeafletHtml(terminalsRef.get()),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        },
    )
}

private fun buildLeafletHtml(terminals: List<TerminalDto>): String {
    val features = JSONArray()
    terminals.forEach { t ->
        features.put(
            JSONObject()
                .put("id", t.id)
                .put("name", t.name)
                .put("lat", t.latitude)
                .put("lng", t.longitude),
        )
    }
    val json = features.toString()
    return """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
html,body,#map{margin:0;height:100%;width:100%}
.basemap-toggle{
  position:absolute;top:10px;right:10px;z-index:1000;
  display:flex;border-radius:8px;overflow:hidden;
  box-shadow:0 1px 4px rgba(0,0,0,.35);background:#fff;font:600 12px/1.2 system-ui,sans-serif;
}
.basemap-toggle button{
  border:0;background:transparent;padding:8px 12px;cursor:pointer;color:#333;
}
.basemap-toggle button.active{background:#1a73e8;color:#fff}
</style>
</head><body>
<div id="map"></div>
<div class="basemap-toggle" role="group" aria-label="Map basemap">
  <button type="button" id="btn-sat" class="active">Satellite</button>
  <button type="button" id="btn-map">Map</button>
</div>
<script>
const pins = $json;
const map = L.map('map', { zoomControl: true });
const street = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19, attribution: '&copy; OpenStreetMap'
});
const satellite = L.tileLayer(
  'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
  {
    maxZoom: 19,
    attribution: 'Tiles &copy; Esri'
  }
);
let active = satellite;
active.addTo(map);
function setBasemap(kind) {
  map.removeLayer(active);
  active = kind === 'street' ? street : satellite;
  active.addTo(map);
  document.getElementById('btn-sat').classList.toggle('active', kind === 'satellite');
  document.getElementById('btn-map').classList.toggle('active', kind === 'street');
}
document.getElementById('btn-sat').onclick = () => setBasemap('satellite');
document.getElementById('btn-map').onclick = () => setBasemap('street');
const bounds = [];
pins.forEach(p => {
  const m = L.marker([p.lat, p.lng]).addTo(map).bindPopup(p.name);
  m.on('click', () => { if (window.EkmsBridge) EkmsBridge.openTerminal(p.id); });
  bounds.push([p.lat, p.lng]);
});
if (bounds.length === 1) map.setView(bounds[0], 14);
else if (bounds.length > 1) map.fitBounds(bounds, { padding: [24, 24] });
else map.setView([4.2, 109.5], 5);
</script>
</body></html>
""".trimIndent()
}

private val TerminalConnectionState.mobileLabel: String
    get() = when (this) {
        TerminalConnectionState.UNKNOWN -> "Unknown"
        TerminalConnectionState.ONLINE -> "Online"
        TerminalConnectionState.OFFLINE -> "Offline"
        TerminalConnectionState.SETUP_REQUIRED -> "Setup required"
    }
