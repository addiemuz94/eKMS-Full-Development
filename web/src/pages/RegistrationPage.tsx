/**
 * Continuous new-location registration wizard (sites API; UI label = Location).
 *
 * Flow (one location/site carried through every step — no re-selecting):
 *   1. Register Location
 *   2. Key Cabinet(s) for that location (no setup code yet)
 *   3. Keys for that location (same Layout/List UX as Key Settings)
 *   4. Key Permission — allow existing personnel to take keys
 *   5. Generate setup code(s) — last step, give to on-site technician
 *
 * Cabinet behavioral settings (timers / video) live under Cabinet Management after
 * the cabinet exists — not a wizard step. Dialogs never close on backdrop click.
 */
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check, Plus, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import { layoutSummary, parseCabinetLayout } from '../api/cabinetLayout'
import { CabinetLayoutFields } from '../components/CabinetLayoutFields'
import type { KeyDto, SiteDto, TerminalDto, UserDto } from '../api/types'
import { Button, LinearProgress, useConfirm } from '../components/ui'
import { MALAYSIA_STATES, citiesForState } from '../geo/malaysiaLocations'
import { KeysPage } from './KeysPage'

type PairingBanner = {
  code: string
  expiresAtEpochMillis: number
  terminalName: string
  terminalId: string
}

const STEPS = [
  { label: 'Location', title: 'Register Location' },
  { label: 'Key Cabinet', title: 'Register Key Cabinet' },
  { label: 'Keys', title: 'Register Keys' },
  { label: 'Key Permission', title: 'Key Permission' },
  { label: 'Setup Code', title: 'Setup Code' },
] as const

function SectionHeader({
  step,
  title,
  unitName,
}: {
  step: number
  title: string
  unitName?: string | null
}) {
  return (
    <div style={{ marginBottom: 20 }}>
      <p className="muted" style={{ margin: '0 0 4px', fontSize: '0.82rem', fontWeight: 600 }}>
        STEP {step} OF {STEPS.length}
        {unitName ? (
          <>
            {' · '}
            <span style={{ color: 'var(--md-sys-color-primary, #0055a5)' }}>Location: {unitName}</span>
          </>
        ) : null}
      </p>
      <h2 style={{ margin: 0 }}>{title}</h2>
    </div>
  )
}

// ─── Step 1: Location (site) ─────────────────────────────────────────────────

function StepUnit({
  unit,
  onUnitReady,
}: {
  unit: SiteDto | null
  onUnitReady: (site: SiteDto) => void
}) {
  const [sites, setSites] = useState<SiteDto[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [province, setProvince] = useState('')
  const [city, setCity] = useState('')
  const cityOptions = useMemo(() => citiesForState(province), [province])

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      setSites(await api.listSites())
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load locations')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  async function onSave(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) {
      setError('Location name is required.')
      return
    }
    if (!province) {
      setError('Select a Malaysian state / province.')
      return
    }
    if (!city) {
      setError('Select a city.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const created = await api.createSite({
        name: name.trim(),
        province,
        city,
      })
      setOpen(false)
      setName('')
      setProvince('')
      setCity('')
      await reload()
      onUnitReady(created)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to register location')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <SectionHeader step={1} title="Register Location" />
      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading" />}

      {unit ? (
        <div className="notice" style={{ marginBottom: 16 }}>
          <strong>Registering for:</strong> {unit.name}
          {unit.province || unit.city
            ? ` · ${[unit.province, unit.city].filter(Boolean).join(', ')}`
            : ''}
          <p className="muted" style={{ margin: '8px 0 0' }}>
            Continue to the next step, or register a different location below (that becomes the active
            registration).
          </p>
        </div>
      ) : (
        <div className="notice" style={{ marginBottom: 16 }}>
          No location selected yet. Register a new location to start this continuous registration.
        </div>
      )}

      <div className="toolbar-row" style={{ marginBottom: 12 }}>
        <Button icon={Plus} onClick={() => { setError(null); setOpen(true) }}>Register new location</Button>
        {sites.length > 0 && (
          <select
            value={unit?.id ?? ''}
            onChange={(e) => {
              const found = sites.find((s) => s.id === e.target.value)
              if (found) onUnitReady(found)
            }}
            title="Or continue with an existing location"
          >
            <option value="">Or continue with existing location…</option>
            {sites.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
                {s.province ? ` (${s.province})` : ''}
              </option>
            ))}
          </select>
        )}
      </div>

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>Register new location</h2>
            <p className="dialog-copy">
              This location is carried into Key Cabinet, Keys, Key Permission, then Setup Code.
            </p>
            <div className="field">
              <label>Location name</label>
              <input value={name} onChange={(e) => setName(e.target.value)} required placeholder="Johor HQ" />
            </div>
            <div className="field">
              <label>State / province</label>
              <select
                value={province}
                required
                onChange={(e) => {
                  setProvince(e.target.value)
                  setCity('')
                }}
              >
                <option value="">Select state / province</option>
                {MALAYSIA_STATES.map((st) => (
                  <option key={st.id} value={st.name}>
                    {st.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>City</label>
              <select value={city} required disabled={!province} onChange={(e) => setCity(e.target.value)}>
                <option value="">{province ? 'Select city' : 'Select a state first'}</option>
                {cityOptions.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
            <div className="dialog-actions">
              <Button variant="outlined" icon={X} onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                Save location & continue
              </Button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}

// ─── Step 2: Key Cabinet (location locked, no setup code yet) ────────────────

function StepCabinet({
  unit,
  onCabinetRegistered,
}: {
  unit: SiteDto
  onCabinetRegistered: (pairing: PairingBanner) => void
}) {
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [boxAddress, setBoxAddress] = useState('1')
  const [nodeRows, setNodeRows] = useState('3')
  const [nodesPerRow, setNodesPerRow] = useState('8')
  const [vendorDeviceId, setVendorDeviceId] = useState('')
  const [latitude, setLatitude] = useState('')
  const [longitude, setLongitude] = useState('')

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const all = await api.listTerminals()
      setTerminals(all.filter((t) => t.siteId === unit.id))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load cabinets')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [unit.id])

  function resetForm() {
    setName('')
    setBoxAddress('1')
    setNodeRows('3')
    setNodesPerRow('8')
    setVendorDeviceId('')
    setLatitude('')
    setLongitude('')
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
    const layout = parseCabinetLayout(nodeRows, nodesPerRow)
    if (!layout.ok) {
      setError(layout.message)
      return
    }
    setBusy(true)
    setError(null)
    try {
      const result = await api.createTerminal({
        siteId: unit.id,
        name: name.trim() || `${unit.name} Cabinet`,
        boxAddress: Math.max(1, Number(boxAddress) || 1),
        configuredSlotCount: layout.value.totalSlots,
        vendorDeviceId: vendorDeviceId.trim() || null,
        nodeRows: layout.value.rows,
        nodesPerRow: layout.value.columns,
        serialNumber: null,
        latitude: latitude.trim() ? Number(latitude) : null,
        longitude: longitude.trim() ? Number(longitude) : null,
      })
      onCabinetRegistered({
        code: result.pairingCode,
        expiresAtEpochMillis: result.pairingCodeExpiresAtEpochMillis,
        terminalName: result.terminal.name,
        terminalId: result.terminal.id,
      })
      setOpen(false)
      resetForm()
      setNotice(
        `Cabinet registered under ${unit.name}. The setup code is available in the final step.`,
      )
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to register cabinet')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <SectionHeader step={2} title="Register Key Cabinet" unitName={unit.name} />
      {notice && <div className="notice">{notice}</div>}
      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading" />}

      <div className="toolbar-row" style={{ marginBottom: 12 }}>
        <Button
          icon={Plus}
          onClick={() => {
            resetForm()
            setError(null)
            setOpen(true)
          }}
        >
          Register cabinet for this location
        </Button>
      </div>

      {terminals.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Cabinet</th>
                <th>Layout</th>
                <th>Setup</th>
                <th>Cabinet ID</th>
              </tr>
            </thead>
            <tbody>
              {terminals.map((t) => (
                <tr key={t.id}>
                  <td className="cell-title">{t.name}</td>
                  <td>
                    Cabinet number {t.boxAddress} · {layoutSummary(t)}
                  </td>
                  <td>
                    <span className={`badge${t.paired ? ' badge-success' : ''}`}>
                      {t.paired ? 'Set up' : 'Not set up'}
                    </span>
                  </td>
                  <td className="mono" style={{ fontSize: '0.78rem' }}>
                    {t.id}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && (
          <div className="empty-state">
            No cabinets for this location. Register a cabinet before continuing.
          </div>
        )
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>Register key cabinet</h2>
            <p className="dialog-copy">
              Location is fixed to <strong>{unit.name}</strong>. The setup code is issued on save and
              shown in the final step.
            </p>
            <div className="field">
              <label>Location</label>
              <input value={unit.name} disabled readOnly />
            </div>
            <div className="field">
              <label>Cabinet name</label>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
                placeholder={`${unit.name} Cabinet`}
              />
            </div>
            <div className="field">
              <label>Cabinet number</label>
              <input value={boxAddress} onChange={(e) => setBoxAddress(e.target.value)} required />
            </div>
            <CabinetLayoutFields
              rows={nodeRows}
              columns={nodesPerRow}
              onRowsChange={setNodeRows}
              onColumnsChange={setNodesPerRow}
            />
            <div className="field">
              <label>Vendor device ID (optional)</label>
              <input value={vendorDeviceId} onChange={(e) => setVendorDeviceId(e.target.value)} />
            </div>
            <div className="split">
              <div className="field">
                <label>Latitude (optional)</label>
                <input value={latitude} onChange={(e) => setLatitude(e.target.value)} />
              </div>
              <div className="field">
                <label>Longitude (optional)</label>
                <input value={longitude} onChange={(e) => setLongitude(e.target.value)} />
              </div>
            </div>
            <div className="dialog-actions">
              <Button variant="outlined" icon={X} onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                Save cabinet
              </Button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}

// ─── Step 3: Keys (same UX as Key Settings, location locked) ─────────────────

function StepKeys({ unit }: { unit: SiteDto }) {
  return (
    <div>
      <SectionHeader step={3} title="Register Keys" unitName={unit.name} />
      <KeysPage lockedSiteId={unit.id} embedded />
    </div>
  )
}

// ─── Step 4: Key Permission (location locked) ───────────────────────────────

function StepPermissions({ unit }: { unit: SiteDto }) {
  const [grants, setGrants] = useState<Record<string, unknown>[]>([])
  const [users, setUsers] = useState<UserDto[]>([])
  const [allUserNames, setAllUserNames] = useState<UserDto[]>([])
  const [keys, setKeys] = useState<KeyDto[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [userId, setUserId] = useState('')
  const [keyId, setKeyId] = useState('')

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [grantRows, userRows, keyRows] = await Promise.all([
        api.listAccessGrants(),
        api.listUsers(),
        api.listKeys(),
      ])
      const unitKeys = keyRows.filter((k) => k.siteId === unit.id)
      const assigned = userRows.filter(
        (u) => u.role !== 'SUPER_ADMIN' && (u.assignedSiteIds ?? []).includes(unit.id),
      )
      setAllUserNames(userRows)
      setUsers(assigned)
      setKeys(unitKeys)
      setGrants(grantRows.filter((g) => g.siteId === unit.id))
      setUserId((prev) =>
        prev && assigned.some((u) => u.id === prev) ? prev : (assigned[0]?.id ?? ''),
      )
      setKeyId((prev) =>
        prev && unitKeys.some((k) => k.id === prev) ? prev : (unitKeys[0]?.id ?? ''),
      )
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load key permissions')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [unit.id])

  async function onSave(e: FormEvent) {
    e.preventDefault()
    if (!userId || !keyId) {
      setError('Select personnel and a key.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.createAccessGrant({ userId, siteId: unit.id, keyIds: [keyId] })
      setOpen(false)
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to save key permission')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <SectionHeader step={4} title="Key Permission" unitName={unit.name} />
      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading" />}

      <div className="toolbar-row" style={{ marginBottom: 12 }}>
        <Button
          icon={Plus}
          onClick={() => {
            setError(null)
            setOpen(true)
          }}
          disabled={!users.length || !keys.length}
        >
          Add key permission
        </Button>
      </div>

      {!keys.length && !busy ? (
        <div className="empty-state">
          Register at least one key for this location before granting key permission.
        </div>
      ) : !users.length && !busy ? (
        <div className="empty-state">
          Assign personnel to this location in User Management before granting key permission.
        </div>
      ) : grants.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Personnel</th>
                <th>Keys</th>
              </tr>
            </thead>
            <tbody>
              {grants.map((g) => (
                <tr key={String(g.id)}>
                  <td className="cell-title">
                    {allUserNames.find((u) => u.id === g.userId)?.displayName ?? String(g.userId)}
                  </td>
                  <td>
                    {Array.isArray(g.keyIds)
                      ? (g.keyIds as string[])
                          .map((id) => keys.find((k) => k.id === id)?.displayName ?? id)
                          .join(', ')
                      : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No key permissions for this location yet.</div>
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>Grant key permission</h2>
            <p className="dialog-copy">
              Location is fixed to <strong>{unit.name}</strong>. Personnel listed are those assigned
              to this location.
            </p>
            <div className="field">
              <label>Location</label>
              <input value={unit.name} disabled readOnly />
            </div>
            <div className="field">
              <label>Personnel</label>
              <select value={userId} onChange={(e) => setUserId(e.target.value)} required>
                {users.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.displayName}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>Key</label>
              <select value={keyId} onChange={(e) => setKeyId(e.target.value)} required>
                {keys.map((k) => (
                  <option key={k.id} value={k.id}>
                    {k.displayName}
                  </option>
                ))}
              </select>
            </div>
            <div className="dialog-actions">
              <Button variant="outlined" icon={X} onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                Grant key permission
              </Button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}

// ─── Step 5: Setup code (LAST) ───────────────────────────────────────────────

function StepPairing({
  unit,
  issuedPairings,
  onPairingIssued,
}: {
  unit: SiteDto
  issuedPairings: Record<string, PairingBanner>
  onPairingIssued: (pairing: PairingBanner) => void
}) {
  const { confirmAction, dialog } = useConfirm()
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [pairing, setPairing] = useState<PairingBanner | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const all = await api.listTerminals()
      setTerminals(all.filter((t) => t.siteId === unit.id))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load cabinets')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [unit.id])

  function showStoredCode(terminal: TerminalDto) {
    const stored = issuedPairings[terminal.id]
    if (stored) {
      setPairing(stored)
      return true
    }
    return false
  }

  async function onGenerate(terminal: TerminalDto) {
    // Prefer the create-time code when still held in wizard state.
    if (!terminal.paired && showStoredCode(terminal)) {
      return
    }

    const hasStored = Boolean(issuedPairings[terminal.id])
    const needsRevokeWarning = terminal.paired || hasStored
    const message = needsRevokeWarning
      ? 'Generate a new setup code for this cabinet? Any previous code and existing device session will be revoked. Copy the new code before leaving this page.'
      : 'Issue a 6-digit setup code for this cabinet? Copy the code before leaving this page for the on-site technician.'

    if (
      !(await confirmAction({
        message,
        danger: needsRevokeWarning,
      }))
    ) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      const result = await api.regenerateTerminalPairingCode(terminal.id)
      const banner: PairingBanner = {
        code: result.code,
        expiresAtEpochMillis: result.expiresAtEpochMillis,
        terminalName: terminal.name,
        terminalId: terminal.id,
      }
      onPairingIssued(banner)
      setPairing(banner)
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to generate setup code')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <SectionHeader step={5} title="Setup Code" unitName={unit.name} />
      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading" />}

      {pairing && (
        <div className="notice pairing-code-banner" role="status">
          <h3>Setup code (copy before leaving this page)</h3>
          <p className="muted">
            For <strong>{pairing.terminalName}</strong>. Shown once — the server only stores a hash.
            Expires {new Date(pairing.expiresAtEpochMillis).toLocaleString()}.
          </p>
          <p className="pairing-code-digits mono">{pairing.code}</p>
          <p className="muted mono" style={{ fontSize: '0.85rem' }}>
            Key Cabinet ID: {pairing.terminalId}
          </p>
          <Button variant="outlined" icon={X} onClick={() => setPairing(null)}>
            Dismiss
          </Button>
        </div>
      )}

      {terminals.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Cabinet</th>
                <th>Setup</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {terminals.map((t) => {
                const hasCode = Boolean(issuedPairings[t.id])
                return (
                  <tr key={t.id}>
                    <td className="cell-title">{t.name}</td>
                    <td>
                      <span className={`badge${t.paired ? ' badge-success' : ''}`}>
                        {t.paired ? 'Set up' : 'Not set up'}
                      </span>
                    </td>
                    <td className="col-actions">
                      <Button variant="link" disabled={busy} onClick={() => void onGenerate(t)}>
                        {hasCode && !t.paired
                          ? 'Show setup code'
                          : t.paired || hasCode
                            ? 'Regenerate setup code'
                            : 'Generate setup code'}
                      </Button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && (
          <div className="empty-state">
            No cabinets for this location. Register a key cabinet in step 2 first.
          </div>
        )
      )}

      {dialog}
    </div>
  )
}

// ─── Wizard shell ────────────────────────────────────────────────────────────

export function RegistrationPage() {
  const [step, setStep] = useState(0)
  const [unit, setUnit] = useState<SiteDto | null>(null)
  const [issuedPairings, setIssuedPairings] = useState<Record<string, PairingBanner>>({})
  const [cabinetCount, setCabinetCount] = useState(0)
  const [keyCount, setKeyCount] = useState(0)
  const [gateMessage, setGateMessage] = useState<string | null>(null)

  function rememberPairing(pairing: PairingBanner) {
    setIssuedPairings((prev) => ({ ...prev, [pairing.terminalId]: pairing }))
  }

  async function refreshCounts(siteId: string): Promise<{ cabinets: number; keys: number }> {
    try {
      const [terms, keys] = await Promise.all([api.listTerminals(), api.listKeys()])
      const cabinets = terms.filter((t) => t.siteId === siteId).length
      const keyTotal = keys.filter((k) => k.siteId === siteId).length
      setCabinetCount(cabinets)
      setKeyCount(keyTotal)
      return { cabinets, keys: keyTotal }
    } catch {
      return { cabinets: cabinetCount, keys: keyCount }
    }
  }

  useEffect(() => {
    if (!unit) {
      setCabinetCount(0)
      setKeyCount(0)
      return
    }
    void refreshCounts(unit.id)
  }, [unit, step])

  function stepBlockedReason(
    target: number,
    cabinets = cabinetCount,
    keys = keyCount,
  ): string | null {
    if (target <= 0) return null
    if (!unit) return 'Register a location in step 1 first.'
    if (target >= 2 && cabinets < 1) {
      return 'Register at least one key cabinet before continuing to Keys or Setup Code.'
    }
    if (target >= 3 && keys < 1) {
      return 'Register at least one key before continuing to Key Permission.'
    }
    return null
  }

  async function tryGoToStep(target: number) {
    let cabinets = cabinetCount
    let keys = keyCount
    if (unit && target > 0) {
      const counts = await refreshCounts(unit.id)
      cabinets = counts.cabinets
      keys = counts.keys
    }
    const reason = stepBlockedReason(target, cabinets, keys)
    if (reason) {
      setGateMessage(reason)
      return
    }
    setGateMessage(null)
    setStep(target)
  }

  function goNext() {
    void tryGoToStep(Math.min(step + 1, STEPS.length - 1))
  }

  function goPrev() {
    setGateMessage(null)
    setStep((s) => Math.max(s - 1, 0))
  }

  function onUnitReady(site: SiteDto) {
    setUnit(site)
    setIssuedPairings({})
    setGateMessage(null)
    setStep(1)
  }

  function resetWizard() {
    setStep(0)
    setUnit(null)
    setIssuedPairings({})
    setCabinetCount(0)
    setKeyCount(0)
    setGateMessage(null)
  }

  const nextBlocked = stepBlockedReason(step + 1)

  return (
    <section className="stack">
      <div className="page-header">
        <div>
          <h1>Registration</h1>
          <p className="muted">
            Register a location, then configure its cabinet, keys, permissions, and setup code.
          </p>
        </div>
      </div>

      <div className="wizard-stepper">
        {STEPS.map((s, i) => {
          const blocked = stepBlockedReason(i)
          return (
            <button
              key={s.label}
              type="button"
              className={`wizard-step${i === step ? ' wizard-step-active' : ''}${i < step ? ' wizard-step-done' : ''}`}
              onClick={() => void tryGoToStep(i)}
              disabled={Boolean(blocked) && i !== step}
              title={blocked ?? s.title}
            >
              <span className="wizard-step-num">{i + 1}</span>
              <span className="wizard-step-label">{s.label}</span>
            </button>
          )
        })}
      </div>

      {gateMessage && <div className="error-banner">{gateMessage}</div>}

      <div className="card wizard-body">
        {step === 0 && <StepUnit unit={unit} onUnitReady={onUnitReady} />}
        {step === 1 && unit && (
          <StepCabinet
            unit={unit}
            onCabinetRegistered={(pairing) => {
              rememberPairing(pairing)
              void refreshCounts(unit.id)
            }}
          />
        )}
        {step === 2 && unit && <StepKeys unit={unit} />}
        {step === 3 && unit && <StepPermissions unit={unit} />}
        {step === 4 && unit && (
          <StepPairing
            unit={unit}
            issuedPairings={issuedPairings}
            onPairingIssued={rememberPairing}
          />
        )}
        {step > 0 && !unit && (
          <div className="empty-state">Register a location in step 1 to continue.</div>
        )}
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Button variant="outlined" disabled={step === 0} onClick={goPrev}>
          ← Previous
        </Button>
        <span className="muted" style={{ fontSize: '0.88rem' }}>
          Step {step + 1} of {STEPS.length}
          {unit ? ` · ${unit.name}` : ''}
        </span>
        {step < STEPS.length - 1 ? (
          <Button onClick={goNext} disabled={Boolean(nextBlocked)} title={nextBlocked ?? undefined}>
            Next →
          </Button>
        ) : (
          <Button icon={Plus} onClick={resetWizard}>
            Start another location
          </Button>
        )}
      </div>
    </section>
  )
}
