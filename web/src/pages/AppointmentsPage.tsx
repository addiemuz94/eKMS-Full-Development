import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError } from '../api/client'
import type { KeyDto, SiteDto, TerminalDto, UserDto } from '../api/types'

export function AppointmentsPage() {
  const [items, setItems] = useState<Record<string, unknown>[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [users, setUsers] = useState<UserDto[]>([])
  const [keys, setKeys] = useState<KeyDto[]>([])
  const [reasons, setReasons] = useState<Record<string, unknown>[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState({
    siteId: '',
    terminalId: '',
    userId: '',
    reasonId: '',
    keyId: '',
    pickupWindowLabel: '',
  })

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [appointmentRows, siteRows, terminalRows, userRows, keyRows, reasonRows] = await Promise.all([
        api.listAppointments(),
        api.listSites(),
        api.listTerminals(),
        api.listUsers(),
        api.listKeys(),
        api.listAppointmentReasons(),
      ])
      setItems(appointmentRows)
      setSites(siteRows)
      setTerminals(terminalRows)
      setUsers(userRows)
      setKeys(keyRows)
      setReasons(reasonRows)
      setForm((current) => ({
        ...current,
        siteId: current.siteId || siteRows[0]?.id || '',
        terminalId: current.terminalId || terminalRows[0]?.id || '',
        userId: current.userId || userRows[0]?.id || '',
        reasonId: current.reasonId || String(reasonRows[0]?.id ?? ''),
        keyId: current.keyId || keyRows[0]?.id || '',
      }))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load appointments')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  async function onCreate(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.createAppointment({
        siteId: form.siteId,
        terminalId: form.terminalId,
        userId: form.userId,
        reasonId: form.reasonId || null,
        keyIds: form.keyId ? [form.keyId] : [],
        pickupWindowLabel: form.pickupWindowLabel.trim() || 'Time window TBD',
      })
      setOpen(false)
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to create appointment')
    } finally {
      setBusy(false)
    }
  }

  async function review(item: Record<string, unknown>, status: 'APPROVED' | 'REJECTED') {
    setBusy(true)
    try {
      await api.reviewAppointment(String(item.id), {
        status,
        expectedRevision: Number(item.revision ?? 1),
      })
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Review failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Appointment Authorization</h1>
          <p className="muted">Time-bounded temporary key access requests awaiting Super Admin review.</p>
        </div>
        <button className="btn" type="button" onClick={() => setOpen(true)}>
          Add appointment
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {items.map((item) => (
        <article className="card" key={String(item.id)}>
          <h3>{users.find((user) => user.id === item.userId)?.displayName ?? String(item.userId)}</h3>
          <div className="meta">
            <div>Unit: {sites.find((site) => site.id === item.siteId)?.name ?? '—'}</div>
            <div>Terminal: {terminals.find((terminal) => terminal.id === item.terminalId)?.name ?? '—'}</div>
            <div>Window: {String(item.pickupWindowLabel ?? '—')}</div>
            <div>Status: {String(item.status ?? 'PENDING')}</div>
            <div>
              Keys:{' '}
              {Array.isArray(item.keyIds)
                ? (item.keyIds as string[])
                    .map((id) => keys.find((key) => key.id === id)?.displayName ?? id)
                    .join(', ') || '—'
                : '—'}
            </div>
          </div>
          {item.status === 'PENDING' && (
            <div className="card-actions">
              <button className="btn" type="button" onClick={() => void review(item, 'APPROVED')}>
                Approve
              </button>
              <button className="btn secondary" type="button" onClick={() => void review(item, 'REJECTED')}>
                Reject
              </button>
            </div>
          )}
        </article>
      ))}

      {!items.length && !busy && <div className="empty-state">No appointments yet.</div>}

      {open && (
        <div className="dialog-backdrop" onClick={() => setOpen(false)}>
          <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={onCreate}>
            <h2>Add appointment</h2>
            <p className="dialog-copy">Create a temporary key access request for later Super Admin review.</p>
            {(
              [
                ['siteId', 'Unit', sites.map((site) => [site.id, site.name] as const)],
                ['terminalId', 'Terminal', terminals.map((terminal) => [terminal.id, terminal.name] as const)],
                ['userId', 'Personnel', users.map((user) => [user.id, user.displayName] as const)],
                ['reasonId', 'Reason', reasons.map((reason) => [String(reason.id), String(reason.name)] as const)],
                ['keyId', 'Exact key', keys.map((key) => [key.id, key.displayName] as const)],
              ] as const
            ).map(([name, label, options]) => (
              <div className="field" key={name}>
                <label>{label}</label>
                <select
                  value={form[name]}
                  onChange={(e) => setForm((current) => ({ ...current, [name]: e.target.value }))}
                  required={name !== 'reasonId'}
                >
                  {name === 'reasonId' && <option value="">— Optional —</option>}
                  {options.map(([value, text]) => (
                    <option key={value} value={value}>
                      {text}
                    </option>
                  ))}
                </select>
              </div>
            ))}
            <div className="field">
              <label>Pickup window</label>
              <input
                value={form.pickupWindowLabel}
                onChange={(e) => setForm((current) => ({ ...current, pickupWindowLabel: e.target.value }))}
                placeholder="e.g. Tomorrow 09:00–12:00"
                required
              />
            </div>
            <div className="dialog-actions">
              <button className="btn secondary" type="button" onClick={() => setOpen(false)}>
                Cancel
              </button>
              <button className="btn" type="submit" disabled={busy}>
                Save
              </button>
            </div>
          </form>
        </div>
      )}
    </section>
  )
}
