import { useEffect, useMemo, useRef, useState } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import 'leaflet.markercluster'
import 'leaflet.markercluster/dist/MarkerCluster.css'
import 'leaflet.markercluster/dist/MarkerCluster.Default.css'
import type { SiteDto } from '../api/types'
import { SegmentedControl } from './ui'
import { MALAYSIA_BOUNDS, latLngForSite } from '../geo/malaysiaLocations'

type Props = {
  sites: SiteDto[]
}

type MapBasemap = 'satellite' | 'street'

const STREET_TILE_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const STREET_TILE_ATTRIBUTION = '&copy; OpenStreetMap'
const SATELLITE_TILE_URL =
  'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'
const SATELLITE_TILE_ATTRIBUTION =
  'Tiles &copy; Esri — Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community'

function tileForBasemap(basemap: MapBasemap) {
  if (basemap === 'satellite') {
    return { url: SATELLITE_TILE_URL, attribution: SATELLITE_TILE_ATTRIBUTION }
  }
  return { url: STREET_TILE_URL, attribution: STREET_TILE_ATTRIBUTION }
}

function makeIcons() {
  return {
    marker: L.divIcon({
      className: 'ekms-map-marker',
      html: '<span class="ekms-map-marker-dot"></span>',
      iconSize: [18, 18],
      iconAnchor: [9, 9],
    }),
    selected: L.divIcon({
      className: 'ekms-map-marker selected',
      html: '<span class="ekms-map-marker-dot"></span>',
      iconSize: [22, 22],
      iconAnchor: [11, 11],
    }),
  }
}

export function MalaysiaUnitsMap({ sites }: Props) {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [mapError, setMapError] = useState<string | null>(null)
  const [basemap, setBasemap] = useState<MapBasemap>('satellite')
  const mapEl = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<L.Map | null>(null)
  const markersRef = useRef<L.MarkerClusterGroup | null>(null)
  const tileLayerRef = useRef<L.TileLayer | null>(null)
  const icons = useMemo(() => makeIcons(), [])

  const points = useMemo(
    () =>
      sites.map((site) => {
        const pos = latLngForSite(site)
        return {
          id: site.id,
          name: site.name,
          province: site.province?.trim() || '—',
          city: site.city?.trim() || '—',
          lat: pos.lat,
          lng: pos.lng,
          known: pos.known,
        }
      }),
    [sites],
  )

  const selected = points.find((point) => point.id === selectedId) ?? null

  useEffect(() => {
    if (!mapEl.current || mapRef.current) return

    try {
      const map = L.map(mapEl.current, {
        zoomControl: true,
        attributionControl: true,
        minZoom: 5,
        maxZoom: 18,
      })

      const initial = tileForBasemap('satellite')
      tileLayerRef.current = L.tileLayer(initial.url, {
        attribution: initial.attribution,
        maxZoom: 19,
      }).addTo(map)

      map.fitBounds(MALAYSIA_BOUNDS, { padding: [24, 24] })
      markersRef.current = L.markerClusterGroup({
        showCoverageOnHover: false,
        maxClusterRadius: 50,
      }).addTo(map)
      mapRef.current = map

      // Leaflet needs a invalidate after layout settles (common white/grey map cause).
      requestAnimationFrame(() => map.invalidateSize())
    } catch (err) {
      setMapError(err instanceof Error ? err.message : 'Map failed to start')
    }

    return () => {
      mapRef.current?.remove()
      mapRef.current = null
      markersRef.current = null
      tileLayerRef.current = null
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    const next = tileForBasemap(basemap)
    tileLayerRef.current?.remove()
    tileLayerRef.current = L.tileLayer(next.url, {
      attribution: next.attribution,
      maxZoom: 19,
    }).addTo(map)
  }, [basemap])

  useEffect(() => {
    const map = mapRef.current
    const group = markersRef.current
    if (!map || !group) return

    group.clearLayers()
    for (const point of points) {
      const marker = L.marker([point.lat, point.lng], {
        icon: selectedId === point.id ? icons.selected : icons.marker,
        title: point.name,
      })
      marker.bindPopup(
        `<strong>${escapeHtml(point.name)}</strong><br/>${escapeHtml(point.city)}, ${escapeHtml(point.province)}`,
      )
      marker.on('click', () => setSelectedId(point.id))
      group.addLayer(marker)
    }

    if (points.length === 1) {
      map.setView([points[0].lat, points[0].lng], 8)
    } else if (points.length > 1) {
      const bounds = L.latLngBounds(points.map((p) => [p.lat, p.lng] as [number, number]))
      map.fitBounds(bounds.pad(0.35), { maxZoom: 8 })
    } else {
      map.fitBounds(MALAYSIA_BOUNDS, { padding: [24, 24] })
    }
    map.invalidateSize()
  }, [points, selectedId, icons])

  useEffect(() => {
    if (!selected || !mapRef.current) return
    const map = mapRef.current
    const targetZoom = Math.min(map.getMaxZoom(), Math.max(map.getZoom(), 11))
    map.flyTo([selected.lat, selected.lng], targetZoom, { animate: true })
  }, [selected])

  return (
    <div className="map-card">
      <div className="map-card-header">
        <div>
          <h2>Units in Malaysia</h2>
          <p className="muted">Map of Malaysia with unit markers by state and city.</p>
        </div>
        <SegmentedControl
          ariaLabel="Map basemap"
          value={basemap}
          onChange={setBasemap}
          options={[
            { value: 'satellite', label: 'Satellite' },
            { value: 'street', label: 'Map' },
          ]}
        />
      </div>

      <div className="map-layout">
        <div className="map-canvas-wrap">
          {mapError ? (
            <div className="empty-state" style={{ margin: 16 }}>
              Map unavailable: {mapError}. Unit list still works.
            </div>
          ) : (
            <div className="map-canvas" ref={mapEl} />
          )}
          {selected && (
            <div className="layout-map-detail">
              <strong>{selected.name}</strong>
              <span>State / province: {selected.province}</span>
              <span>City: {selected.city}</span>
              {!selected.known && (
                <span className="muted">Location is approximate — choose a Malaysian state when editing.</span>
              )}
            </div>
          )}
        </div>

        <div className="map-side-list">
          <div className="map-side-title">Unit list</div>
          {points.length ? (
            <ul className="map-unit-list">
              {points.map((point) => (
                <li key={point.id}>
                  <button
                    type="button"
                    className={`map-unit-button${selectedId === point.id ? ' selected' : ''}`}
                    onClick={() => setSelectedId(point.id)}
                  >
                    <strong>{point.name}</strong>
                    <span>
                      {point.city !== '—' || point.province !== '—'
                        ? `${point.city}${point.city !== '—' && point.province !== '—' ? ', ' : ''}${
                            point.province !== '—' ? point.province : ''
                          }`
                        : 'No location set'}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <div className="empty-state" style={{ margin: 0, padding: 16 }}>
              No units available.
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function escapeHtml(value: string) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}
