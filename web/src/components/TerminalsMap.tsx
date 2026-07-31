import { useEffect, useMemo, useRef, useState } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import 'leaflet.markercluster'
import 'leaflet.markercluster/dist/MarkerCluster.css'
import 'leaflet.markercluster/dist/MarkerCluster.Default.css'
import type { RegionDto, SiteDto, TerminalDto } from '../api/types'
import { SegmentedControl } from './ui'
import { MALAYSIA_BOUNDS, latLngForSite } from '../geo/malaysiaLocations'

export type MapBasemap = 'satellite' | 'street'

export type TerminalMapPoint = {
  id: string
  name: string
  siteId: string
  siteName: string
  lat: number
  lng: number
  known: boolean
  paired: boolean
}

type Props = {
  sites: SiteDto[]
  terminals: TerminalDto[]
  regions: RegionDto[]
  regionFilter: string
  siteFilter: string
  selectedTerminalId: string | null
  onRegionFilterChange: (regionId: string) => void
  onSiteFilterChange: (siteId: string) => void
  onSelectTerminal: (terminalId: string | null) => void
}

const STREET_TILE_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const STREET_TILE_ATTRIBUTION = '&copy; OpenStreetMap'
/** Esri World Imagery — free satellite tiles, no API key. */
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
    unpaired: L.divIcon({
      className: 'ekms-map-marker unpaired',
      html: '<span class="ekms-map-marker-dot"></span>',
      iconSize: [18, 18],
      iconAnchor: [9, 9],
    }),
  }
}

export function latLngForTerminal(
  terminal: TerminalDto,
  site: SiteDto | undefined,
): { lat: number; lng: number; known: boolean } {
  if (terminal.latitude != null && terminal.longitude != null) {
    return { lat: terminal.latitude, lng: terminal.longitude, known: true }
  }
  if (site) {
    const pos = latLngForSite(site)
    return { lat: pos.lat, lng: pos.lng, known: pos.known }
  }
  return { lat: 4.2105, lng: 101.9758, known: false }
}

export type RegionFilterMode = 'admin' | 'province'

function siteMatchesRegionFilter(
  site: SiteDto,
  regionFilter: string,
  mode: RegionFilterMode,
): boolean {
  if (regionFilter === 'all') return true
  if (mode === 'admin') return site.regionId === regionFilter
  return (site.province ?? '').trim() === regionFilter
}

export function buildTerminalPoints(
  sites: SiteDto[],
  terminals: TerminalDto[],
  regionFilter: string,
  siteFilter: string,
  regionMode: RegionFilterMode = 'province',
): TerminalMapPoint[] {
  const siteById = new Map(sites.map((s) => [s.id, s]))
  let filteredSites = sites
  if (regionFilter !== 'all') {
    filteredSites = filteredSites.filter((s) => siteMatchesRegionFilter(s, regionFilter, regionMode))
  }
  if (siteFilter !== 'all') {
    filteredSites = filteredSites.filter((s) => s.id === siteFilter)
  }
  const allowedSiteIds = new Set(filteredSites.map((s) => s.id))

  return terminals
    .filter((t) => allowedSiteIds.has(t.siteId))
    .map((terminal) => {
      const site = siteById.get(terminal.siteId)
      const pos = latLngForTerminal(terminal, site)
      return {
        id: terminal.id,
        name: terminal.name,
        siteId: terminal.siteId,
        siteName: site?.name ?? 'Unknown unit',
        lat: pos.lat,
        lng: pos.lng,
        known: pos.known,
        paired: Boolean(terminal.paired),
      }
    })
}

/** Prefer admin Regions when any unit is linked; otherwise Malaysian state/province on the unit. */
export function resolveRegionFilterMode(sites: SiteDto[], regions: RegionDto[]): RegionFilterMode {
  if (regions.length === 0) return 'province'
  const regionIds = new Set(regions.map((r) => r.id))
  if (sites.some((s) => s.regionId && regionIds.has(s.regionId))) return 'admin'
  return 'province'
}

export function TerminalsMap({
  sites,
  terminals,
  regions,
  regionFilter,
  siteFilter,
  selectedTerminalId,
  onRegionFilterChange,
  onSiteFilterChange,
  onSelectTerminal,
}: Props) {
  const [mapError, setMapError] = useState<string | null>(null)
  const [basemap, setBasemap] = useState<MapBasemap>('satellite')
  const mapEl = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<L.Map | null>(null)
  const markersRef = useRef<L.MarkerClusterGroup | null>(null)
  const tileLayerRef = useRef<L.TileLayer | null>(null)
  const icons = useMemo(() => makeIcons(), [])
  const regionMode = useMemo(() => resolveRegionFilterMode(sites, regions), [sites, regions])

  const regionOptions = useMemo(() => {
    if (regionMode === 'admin') {
      return regions
        .slice()
        .sort((a, b) => a.displayOrder - b.displayOrder || a.name.localeCompare(b.name))
        .map((r) => ({ value: r.id, label: r.name }))
    }
    const names = new Set<string>()
    for (const site of sites) {
      const province = site.province?.trim()
      if (province) names.add(province)
    }
    return [...names].sort((a, b) => a.localeCompare(b)).map((name) => ({ value: name, label: name }))
  }, [regionMode, regions, sites])

  const points = useMemo(
    () => buildTerminalPoints(sites, terminals, regionFilter, siteFilter, regionMode),
    [sites, terminals, regionFilter, siteFilter, regionMode],
  )

  const selected = points.find((point) => point.id === selectedTerminalId) ?? null

  const sitesInRegion = useMemo(() => {
    if (regionFilter === 'all') return sites
    return sites.filter((s) => siteMatchesRegionFilter(s, regionFilter, regionMode))
  }, [sites, regionFilter, regionMode])

  useEffect(() => {
    if (regionFilter === 'all') return
    if (!regionOptions.some((option) => option.value === regionFilter)) {
      onRegionFilterChange('all')
    }
  }, [regionFilter, regionOptions, onRegionFilterChange])

  useEffect(() => {
    if (!mapEl.current || mapRef.current) return

    try {
      const map = L.map(mapEl.current, {
        zoomControl: true,
        attributionControl: true,
        minZoom: 5,
        maxZoom: 20,
      })

      const initial = tileForBasemap('satellite')
      tileLayerRef.current = L.tileLayer(initial.url, {
        attribution: initial.attribution,
        maxZoom: 20,
        maxNativeZoom: 19,
      }).addTo(map)

      map.fitBounds(MALAYSIA_BOUNDS, { padding: [24, 24] })
      markersRef.current = L.markerClusterGroup({
        showCoverageOnHover: false,
        maxClusterRadius: 50,
        disableClusteringAtZoom: 16,
      }).addTo(map)
      mapRef.current = map

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
      maxZoom: 20,
      maxNativeZoom: 19,
    }).addTo(map)
  }, [basemap])

  useEffect(() => {
    const map = mapRef.current
    const group = markersRef.current
    if (!map || !group) return

    group.clearLayers()
    for (const point of points) {
      const icon =
        selectedTerminalId === point.id
          ? icons.selected
          : point.paired
            ? icons.marker
            : icons.unpaired
      const marker = L.marker([point.lat, point.lng], { icon, title: point.name })
      marker.bindPopup(
        `<strong>${escapeHtml(point.name)}</strong><br/>${escapeHtml(point.siteName)}`,
      )
      marker.on('click', () => onSelectTerminal(point.id))
      group.addLayer(marker)
    }

    if (points.length === 1) {
      map.setView([points[0].lat, points[0].lng], 17)
    } else if (points.length > 1) {
      const bounds = L.latLngBounds(points.map((p) => [p.lat, p.lng] as [number, number]))
      map.fitBounds(bounds.pad(0.2), { maxZoom: 17 })
    } else {
      map.fitBounds(MALAYSIA_BOUNDS, { padding: [24, 24] })
    }
    map.invalidateSize()
  }, [points, selectedTerminalId, icons, onSelectTerminal])

  useEffect(() => {
    if (!selected || !mapRef.current) return
    const map = mapRef.current
    const targetZoom = Math.min(map.getMaxZoom(), Math.max(map.getZoom(), 17))
    map.flyTo([selected.lat, selected.lng], targetZoom, { animate: true })
  }, [selected])

  return (
    <div className="map-card">
      <div className="map-card-header">
        <div>
          <h2>Terminals in Malaysia</h2>
          <p className="muted">
            Key cabinet locations by GPS coordinates or unit geography. Filter by region and unit.
          </p>
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
              Map unavailable: {mapError}. Terminal list still works.
            </div>
          ) : (
            <div className="map-canvas" ref={mapEl} />
          )}
          {selected && (
            <div className="layout-map-detail">
              <strong>{selected.name}</strong>
              <span>Unit: {selected.siteName}</span>
              <span className="mono">ID: {selected.id}</span>
              <span>
                Status:{' '}
                <span className={`badge${selected.paired ? ' badge-success' : ''}`}>
                  {selected.paired ? 'Paired' : 'Not paired'}
                </span>
              </span>
              {!selected.known && (
                <span className="muted">Location is approximate — set lat/lng on the terminal.</span>
              )}
            </div>
          )}
        </div>

        <div className="map-side-list">
          <div className="map-side-title">Filters & terminals</div>

          <div className="map-filter-stack">
            <label className="map-filter-label">
              {regionMode === 'admin' ? 'Region' : 'State / region'}
              <select
                value={regionFilter}
                onChange={(e) => {
                  onRegionFilterChange(e.target.value)
                  onSiteFilterChange('all')
                  onSelectTerminal(null)
                }}
              >
                <option value="all">
                  {regionMode === 'admin' ? 'All regions' : 'All states'}
                </option>
                {regionOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>
            {regionMode === 'province' && regionOptions.length === 0 && (
              <p className="muted map-filter-hint">
                Set each unit&apos;s Malaysian state on Unit Settings so this filter has options.
              </p>
            )}
            {regionMode === 'admin' && regionOptions.length === 0 && (
              <p className="muted map-filter-hint">
                No admin regions yet — assign a region on each unit to enable this filter.
              </p>
            )}
            <label className="map-filter-label">
              Unit
              <select
                value={siteFilter}
                onChange={(e) => {
                  onSiteFilterChange(e.target.value)
                  onSelectTerminal(null)
                }}
              >
                <option value="all">All units</option>
                {sitesInRegion.map((site) => (
                  <option key={site.id} value={site.id}>
                    {site.name}
                  </option>
                ))}
              </select>
            </label>
          </div>

          {points.length ? (
            <ul className="map-unit-list">
              {points.map((point) => (
                <li key={point.id}>
                  <button
                    type="button"
                    className={`map-unit-button${selectedTerminalId === point.id ? ' selected' : ''}`}
                    onClick={() => onSelectTerminal(point.id)}
                  >
                    <strong>{point.name}</strong>
                    <span>{point.siteName}</span>
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <div className="empty-state" style={{ margin: 0, padding: 16 }}>
              No terminals match the current filters.
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
