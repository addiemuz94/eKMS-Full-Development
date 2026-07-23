import { useEffect, useMemo, useRef, useState } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import type { SiteDto } from '../api/types'
import { MALAYSIA_BOUNDS, latLngForSite } from '../geo/malaysiaLocations'

type Props = {
  sites: SiteDto[]
}

const markerIcon = L.divIcon({
  className: 'ekms-map-marker',
  html: '<span class="ekms-map-marker-dot"></span>',
  iconSize: [18, 18],
  iconAnchor: [9, 9],
})

const selectedIcon = L.divIcon({
  className: 'ekms-map-marker selected',
  html: '<span class="ekms-map-marker-dot"></span>',
  iconSize: [22, 22],
  iconAnchor: [11, 11],
})

export function MalaysiaUnitsMap({ sites }: Props) {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const mapEl = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<L.Map | null>(null)
  const markersRef = useRef<L.LayerGroup | null>(null)

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

    const map = L.map(mapEl.current, {
      zoomControl: true,
      attributionControl: true,
      minZoom: 5,
      maxZoom: 12,
    })

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap',
      maxZoom: 19,
    }).addTo(map)

    map.fitBounds(MALAYSIA_BOUNDS, { padding: [24, 24] })
    markersRef.current = L.layerGroup().addTo(map)
    mapRef.current = map

    return () => {
      map.remove()
      mapRef.current = null
      markersRef.current = null
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    const group = markersRef.current
    if (!map || !group) return

    group.clearLayers()
    for (const point of points) {
      const marker = L.marker([point.lat, point.lng], {
        icon: selectedId === point.id ? selectedIcon : markerIcon,
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
  }, [points, selectedId])

  useEffect(() => {
    if (!selected || !mapRef.current) return
    mapRef.current.panTo([selected.lat, selected.lng], { animate: true })
  }, [selected])

  return (
    <div className="map-card">
      <div className="map-card-header">
        <div>
          <h2>Units in Malaysia</h2>
          <p className="muted">Map of Malaysia with unit markers by state and city.</p>
        </div>
      </div>

      <div className="map-layout">
        <div className="map-canvas-wrap">
          <div className="map-canvas" ref={mapEl} />
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
