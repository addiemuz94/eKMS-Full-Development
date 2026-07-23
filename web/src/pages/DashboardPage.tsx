import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { SiteDto } from '../api/types'
import { MalaysiaUnitsMap } from '../components/MalaysiaUnitsMap'

export function DashboardPage() {
  const [sites, setSites] = useState<SiteDto[]>([])
  const [counts, setCounts] = useState({ sites: 0, terminals: 0, users: 0, keys: 0 })
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void (async () => {
      try {
        const [siteRows, terminals, users, keys] = await Promise.all([
          api.listSites(),
          api.listTerminals(),
          api.listUsers(),
          api.listKeys(),
        ])
        setSites(siteRows)
        setCounts({
          sites: siteRows.length,
          terminals: terminals.length,
          users: users.length,
          keys: keys.length,
        })
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Failed to load dashboard')
      }
    })()
  }, [])

  return (
    <section className="stack">
      <div className="page-header">
        <div>
          <h1>Dashboard</h1>
          <p className="muted">
            Overview of configured units, terminals, personnel and keys. Physical key actions stay on the
            Android Terminal.
          </p>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="metrics">
        <div className="metric">
          <div className="metric-label">Units</div>
          <strong>{counts.sites}</strong>
        </div>
        <div className="metric">
          <div className="metric-label">Terminals</div>
          <strong>{counts.terminals}</strong>
        </div>
        <div className="metric">
          <div className="metric-label">Personnel</div>
          <strong>{counts.users}</strong>
        </div>
        <div className="metric">
          <div className="metric-label">Keys</div>
          <strong>{counts.keys}</strong>
        </div>
      </div>

      <MalaysiaUnitsMap sites={sites} />
    </section>
  )
}
