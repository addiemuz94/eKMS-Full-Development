/**
 * Continuous new-unit registration wizard.
 *
 * Flow (one unit carried through every step — no re-selecting):
 *   1. Register Unit
 *   2. Key Cabinet(s) for that unit (no pairing code yet)
 *   3. Cabinet Settings (timers / certification / video toggles)
 *   4. Personnel who will use the terminal
 *   5. Keys for that unit
 *   6. Permissions for that unit
 *   7. Generate pairing code(s) — last step, give to on-site technician
 *
 * Dialogs never close on backdrop click; Cancel / Save only.
 */
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check, Plus, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import { assignKeyToNextAvailableNode, countAvailableNodes } from '../api/keySlotAssignment'
import type { KeyDto, KeySlotDto, SiteDto, TerminalDto, UserDto } from '../api/types'
import { CabinetSettingsForm } from '../components/CabinetSettingsForm'
import { Button, LinearProgress, useConfirm } from '../components/ui'
import { MALAYSIA_STATES, citiesForState } from '../geo/malaysiaLocations'

type PairingBanner = {
  code: string
  expiresAtEpochMillis: number
  terminalName: string
  terminalId: string
}

const STEPS = [
  { label: 'Unit', title: 'Register Unit' },
  { label: 'Key Cabinet', title: 'Register Key Cabinet' },
  { label: 'Cabinet Settings', title: 'Cabinet Settings' },
  { label: 'Personnel', title: 'Assign Personnel' },
  { label: 'Keys', title: 'Register Keys' },
  { label: 'Permissions', title: 'Set Permissions' },
  { label: 'Pairing Code', title: 'Generate Pairing Code' },
] as const

function SectionHeader({
  step,
  title,
  desc,
  unitName,
}: {
  step: number
  title: string
  desc: string
  unitName?: string | null
}) {
  return (
    <div style={{ marginBottom: 20 }}>
      <p className="muted" style={{ margin: '0 0 4px', fontSize: '0.82rem', fontWeight: 600 }}>
        STEP {step} OF {STEPS.length}
        {unitName ? (
          <>
            {' · '}
            <span style={{ color: 'var(--md-sys-color-primary, #0055a5)' }}>Unit: {unitName}</span>
          </>
        ) : null}
      </p>
      <h2 style={{ margin: '0 0 6px' }}>{title}</h2>
      <p className="muted" style={{ margin: 0 }}>
        {desc}
      </p>
    </div>
  )
}

// ─── Step 1: Unit ────────────────────────────────────────────────────────────

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
  const [parentSiteId, setParentSiteId] = useState('')
  const cityOptions = useMemo(() => citiesForState(province), [province])

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      setSites(await api.listSites())
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load units')
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
      setError('Unit name is required.')
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
        parentSiteId: parentSiteId || null,
      })
      setOpen(false)
      setName('')
      setProvince('')
      setCity('')
      setParentSiteId('')
      await reload()
      onUnitReady(created)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to register unit')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <SectionHeader
        step={1}
        title="Register Unit"
        desc="Create the new site/unit first. Every later step in this wizard stays locked to this unit — you will not choose it again."
      />
      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading" />}

      {unit ? (
        <div className="notice" style={{ marginBottom: 16 }}>
          <strong>Registering for:</strong> {unit.name}
          {unit.province || unit.city
            ? ` · ${[unit.province, unit.city].filter(Boolean).join(', ')}`
            : ''}
          <p className="muted" style={{ margin: '8px 0 0' }}>
            Continue to the next step, or register a different unit below (that becomes the active
            registration).
          </p>
        </div>
      ) : (
        <div className="notice" style={{ marginBottom: 16 }}>
          No unit selected yet. Register a new unit to start this continuous registration.
        </div>
      )}

      <div className="toolbar-row" style={{ marginBottom: 12 }}>
        <Button icon={Plus} onClick={() => { setError(null); setOpen(true) }}>Register new unit</Button>
        {sites.length > 0 && (
          <select
            value={unit?.id ?? ''}
            onChange={(e) => {
              const found = sites.find((s) => s.id === e.target.value)
              if (found) onUnitReady(found)
            }}
            title="Or continue with an existing unit"
          >
            <option value="">Or continue with existing unit…</option>
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
            <h2>Register new unit</h2>
            <p className="dialog-copy">
              This unit is carried into Key Cabinet, Personnel, Keys, Permissions, then Pairing Code.
            </p>
            <div className="field">
              <label>Unit name</label>
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
            <div className="field">
              <label>Superior unit (optional)</label>
              <select value={parentSiteId} onChange={(e) => setParentSiteId(e.target.value)}>
                <option value="">— None —</option>
                {sites.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="dialog-actions">
              <Button variant="outlined" icon={X} onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                Save unit & continue
              </Button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}

// ─── Step 2: Key Cabinet (unit locked, no pairing yet) ───────────────────────

function StepCabinet({ unit }: { unit: SiteDto }) {
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [boxAddress, setBoxAddress] = useState('1')
  const [serialNumber, setSerialNumber] = useState('')
  const [nodeCount, setNodeCount] = useState('24')
  const [vendorDeviceId, setVendorDeviceId] = useState('')
  const [nodeRows, setNodeRows] = useState('')
  const [nodesPerRow, setNodesPerRow] = useState('')
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
    setSerialNumber('')
    setNodeCount('24')
    setVendorDeviceId('')
    setNodeRows('')
    setNodesPerRow('')
    setLatitude('')
    setLongitude('')
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
    const slots = Number(nodeCount)
    if (!Number.isFinite(slots) || slots < 1 || slots > 127) {
      setError('Configured node count must be between 1 and 127.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      // Pairing code is issued by the API on create, but we deliberately do not show it here —
      // Step 6 (Generate Pairing Code) is the only place the technician code is presented.
      await api.createTerminal({
        siteId: unit.id,
        name: name.trim() || `${unit.name} Cabinet`,
        boxAddress: Math.max(1, Number(boxAddress) || 1),
        serialNumber: serialNumber.trim() || null,
        configuredSlotCount: Math.min(127, Math.max(1, slots)),
        vendorDeviceId: vendorDeviceId.trim() || null,
        nodeRows: nodeRows.trim() ? Number(nodeRows) : null,
        nodesPerRow: nodesPerRow.trim() ? Number(nodesPerRow) : null,
        latitude: latitude.trim() ? Number(latitude) : null,
        longitude: longitude.trim() ? Number(longitude) : null,
      })
      setOpen(false)
      resetForm()
      setNotice(
        `Cabinet registered under ${unit.name}. Pairing code is generated in the last step.`,
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
      <SectionHeader
        step={2}
        title="Register Key Cabinet"
        desc={`Cabinets are registered under “${unit.name}” only. Unit is already set — no need to choose again. Pairing code comes in the last step.`}
        unitName={unit.name}
      />
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
          Register cabinet for this unit
        </Button>
      </div>

      {terminals.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Cabinet</th>
                <th>Layout</th>
                <th>Paired</th>
                <th>Cabinet ID</th>
              </tr>
            </thead>
            <tbody>
              {terminals.map((t) => (
                <tr key={t.id}>
                  <td className="cell-title">{t.name}</td>
                  <td>
                    Box {t.boxAddress} · {t.configuredSlotCount} nodes
                  </td>
                  <td>
                    <span className={`badge${t.paired ? ' badge-success' : ''}`}>
                      {t.paired ? 'Paired' : 'Not paired'}
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
            No cabinets for this unit yet. Register one before continuing.
          </div>
        )
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>Register key cabinet</h2>
            <p className="dialog-copy">
              Unit is fixed to <strong>{unit.name}</strong>. Pairing code will be generated in the
              final wizard step — not shown here.
            </p>
            <div className="field">
              <label>Unit</label>
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
            <div className="split">
              <div className="field">
                <label>Box address</label>
                <input value={boxAddress} onChange={(e) => setBoxAddress(e.target.value)} required />
              </div>
              <div className="field">
                <label>Node count (1–127)</label>
                <input value={nodeCount} onChange={(e) => setNodeCount(e.target.value)} required />
              </div>
            </div>
            <div className="split">
              <div className="field">
                <label>Serial number (optional)</label>
                <input value={serialNumber} onChange={(e) => setSerialNumber(e.target.value)} />
              </div>
              <div className="field">
                <label>Vendor device ID (optional)</label>
                <input value={vendorDeviceId} onChange={(e) => setVendorDeviceId(e.target.value)} />
              </div>
            </div>
            <div className="split">
              <div className="field">
                <label>Node rows (optional)</label>
                <input value={nodeRows} onChange={(e) => setNodeRows(e.target.value)} />
              </div>
              <div className="field">
                <label>Nodes per row (optional)</label>
                <input value={nodesPerRow} onChange={(e) => setNodesPerRow(e.target.value)} />
              </div>
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

// ─── Step 3: Cabinet Settings (per terminal of this unit) ────────────────────────────

function StepCabinetSettings({ unit }: { unit: SiteDto }) {
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const all = await api.listTerminals()
      const mine = all.filter((t) => t.siteId === unit.id)
      setTerminals(mine)
      setSelectedId((prev) => {
        if (prev && mine.some((t) => t.id === prev)) return prev
        return mine[0]?.id ?? ''
      })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load cabinets')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [unit.id])

  const selected = terminals.find((t) => t.id === selectedId) ?? null

  return (
    <div>
      <SectionHeader
        step={3}
        title="Cabinet Settings"
        desc={`Configure Take/Return warning times and video/certification toggles for cabinets under "${unit.name}". These sync to the device after pairing.`}
        unitName={unit.name}
      />
      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading" />}

      {!busy && terminals.length === 0 ? (
        <div className="empty-state">
          No cabinets for this unit yet. Go back to Key Cabinet and register one first.
        </div>
      ) : (
        <>
          {terminals.length > 1 && (
            <div className="field" style={{ marginBottom: 16, maxWidth: 420 }}>
              <label>Cabinet</label>
              <select value={selectedId} onChange={(e) => setSelectedId(e.target.value)}>
                {terminals.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
              </select>
            </div>
          )}
          {selected && (
            <div className="data-panel" style={{ padding: 16 }}>
              <CabinetSettingsForm
                terminal={selected}
                title={terminals.length === 1 ? selected.name : undefined}
              />
            </div>
          )}
        </>
      )}
    </div>
  )
}

// ─── Step 4: Personnel ───────────────────────────────────────────────────────
// Lists EVERY active user from the database. Check who should use this unit,
// then click "Save assignments". Create-new is still available.

function StepPersonnel({ unit }: { unit: SiteDto }) {
  const [allPeople, setAllPeople] = useState<UserDto[]>([])
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [saving, setSaving] = useState(false)
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [staffId, setStaffId] = useState('')
  const [role, setRole] = useState('TECHNICIAN')
  const [password, setPassword] = useState('')

  function isAssigned(p: UserDto) {
    return (p.assignedSiteIds ?? []).includes(unit.id)
  }

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const people = await api.listUsers()
      setAllPeople(people)
      setSelectedIds(new Set(people.filter(isAssigned).map((p) => p.id)))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load personnel')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [unit.id])

  function toggle(id: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  async function onSaveAssignments() {
    setSaving(true)
    setError(null)
    setNotice(null)
    try {
      // Re-fetch so expectedRevision is fresh
      const fresh = await api.listUsers()
      const byId = new Map(fresh.map((u) => [u.id, u]))
      let changed = 0

      for (const p of fresh) {
        const want = selectedIds.has(p.id)
        const has = (p.assignedSiteIds ?? []).includes(unit.id)
        if (want === has) continue

        let newSites: string[]
        if (want) {
          newSites = [...new Set([...(p.assignedSiteIds ?? []), unit.id])]
        } else {
          newSites = (p.assignedSiteIds ?? []).filter((id) => id !== unit.id)
          // TECHNICIAN / VENDOR must keep at least one site — block clearing the last one
          if (p.role !== 'SUPER_ADMIN' && newSites.length === 0) {
            throw new ApiError(
              400,
              `${p.displayName} (${p.role}) must stay assigned to at least one unit. Assign them to another unit first, or leave them checked here.`,
            )
          }
        }

        await api.updateUser(p.id, {
          displayName: p.displayName,
          email: p.email,
          role: p.role,
          staffId: p.staffId ?? null,
          assignedSiteIds: newSites,
          expectedRevision: p.revision,
        })
        changed += 1
        // keep local map revision in sync if we touch the same user twice (we don't)
        void byId
      }

      setNotice(
        changed === 0
          ? 'No changes — selection already matches this unit.'
          : `Saved ${changed} assignment change${changed === 1 ? '' : 's'} for ${unit.name}.`,
      )
      await reload()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError('Someone else changed a user while you were editing. Reloaded — please try again.')
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to save assignments')
      }
    } finally {
      setSaving(false)
    }
  }

  async function onCreateNew(e: FormEvent) {
    e.preventDefault()
    if (!email.trim() || !email.includes('@')) {
      setError('Enter a valid account email.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.createUser({
        displayName: displayName.trim(),
        email: email.trim(),
        role,
        staffId: staffId.trim() || null,
        assignedSiteIds: [unit.id],
        password: password.length >= 8 ? password : undefined,
      })
      setOpen(false)
      setDisplayName('')
      setEmail('')
      setStaffId('')
      setPassword('')
      setRole('TECHNICIAN')
      setNotice('New user created and assigned.')
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to create personnel')
    } finally {
      setBusy(false)
    }
  }

  const filtered = allPeople.filter((p) => {
    const q = query.trim().toLowerCase()
    if (!q) return true
    return (
      p.displayName.toLowerCase().includes(q) ||
      p.email.toLowerCase().includes(q) ||
      (p.staffId ?? '').toLowerCase().includes(q) ||
      p.role.toLowerCase().includes(q)
    )
  })

  const selectedCount = selectedIds.size
  const dirty = allPeople.some((p) => selectedIds.has(p.id) !== isAssigned(p))

  return (
    <div>
      <SectionHeader
        step={4}
        title="Assign Personnel"
        desc={`Choose who will use the terminal at "${unit.name}". Check users from the database list, then save. You can also create a new account.`}
        unitName={unit.name}
      />
      {notice && <div className="notice">{notice}</div>}
      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading" />}

      <div className="toolbar-row" style={{ marginBottom: 12 }}>
        <input
          type="search"
          placeholder="Search name, email, staff ID…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ flex: 1 }}
        />
        <Button
          variant="outlined"
          icon={Plus}
          onClick={() => {
            setError(null)
            setNotice(null)
            setOpen(true)
          }}
        >
          Create new user
        </Button>
        <Button icon={Check} loading={saving} disabled={!dirty || saving} onClick={() => void onSaveAssignments()}>
          Save assignments ({selectedCount})
        </Button>
      </div>

      {filtered.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th style={{ width: 44 }}>Use</th>
                <th>Name</th>
                <th>Staff ID</th>
                <th>Email</th>
                <th>Role</th>
                <th>Currently on this unit</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((p) => {
                const checked = selectedIds.has(p.id)
                const onUnit = isAssigned(p)
                return (
                  <tr
                    key={p.id}
                    style={{ cursor: 'pointer', background: checked ? 'var(--md-sys-color-primary-container, #d3e4ff)' : undefined }}
                    onClick={() => toggle(p.id)}
                  >
                    <td onClick={(e) => e.stopPropagation()}>
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => toggle(p.id)}
                        aria-label={`Assign ${p.displayName}`}
                      />
                    </td>
                    <td className="cell-title">{p.displayName}</td>
                    <td className="mono">{p.staffId?.trim() || '—'}</td>
                    <td>{p.email}</td>
                    <td>
                      <span className="badge">{p.role}</span>
                    </td>
                    <td>{onUnit ? 'Yes' : '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && (
          <div className="empty-state">
            {allPeople.length === 0
              ? 'No users in the database yet. Create one above.'
              : 'No users match your search.'}
          </div>
        )
      )}

      {dirty && (
        <p className="muted" style={{ marginTop: 12 }}>
          You have unsaved checkbox changes. Click <strong>Save assignments</strong> to apply them to{' '}
          {unit.name}.
        </p>
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onCreateNew}>
            <h2>Create new user</h2>
            <p className="dialog-copy">
              Will be assigned to <strong>{unit.name}</strong> immediately.
            </p>
            <div className="field">
              <label>Display name</label>
              <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
            </div>
            <div className="field">
              <label>Account email</label>
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </div>
            <div className="field">
              <label>Staff ID (optional)</label>
              <input value={staffId} onChange={(e) => setStaffId(e.target.value)} />
            </div>
            <div className="field">
              <label>Role</label>
              <select value={role} onChange={(e) => setRole(e.target.value)}>
                <option value="TECHNICIAN">TECHNICIAN</option>
                <option value="VENDOR">VENDOR</option>
                <option value="REGIONAL_ADMIN">REGIONAL_ADMIN</option>
                <option value="SUPER_ADMIN">SUPER_ADMIN</option>
              </select>
            </div>
            <div className="field">
              <label>Initial password (min 8, optional)</label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                minLength={8}
              />
            </div>
            <div className="dialog-actions">
              <Button variant="outlined" icon={X} onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                Create & assign
              </Button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}

// ─── Step 5: Keys (unit locked) ──────────────────────────────────────────────

function StepKeys({ unit }: { unit: SiteDto }) {
  const [keys, setKeys] = useState<KeyDto[]>([])
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [slotsByTerminal, setSlotsByTerminal] = useState<Record<string, KeySlotDto[]>>({})
  const [availableNodes, setAvailableNodes] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [displayName, setDisplayName] = useState('')
  const [selectedTerminalId, setSelectedTerminalId] = useState('')

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [allKeys, allTerminals] = await Promise.all([api.listKeys(), api.listTerminals()])
      setKeys(allKeys.filter((k) => k.siteId === unit.id))
      const unitTerminals = allTerminals.filter((t) => t.siteId === unit.id)
      setTerminals(unitTerminals)

      const slotLists = await Promise.all(unitTerminals.map((t) => api.listKeySlots(t.id)))
      const byTerminal: Record<string, KeySlotDto[]> = {}
      unitTerminals.forEach((t, i) => {
        byTerminal[t.id] = slotLists[i]
      })
      setSlotsByTerminal(byTerminal)

      if (unitTerminals.length === 1) {
        setAvailableNodes(await countAvailableNodes(unitTerminals[0]))
      } else {
        setAvailableNodes(null)
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load keys')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [unit.id])

  function nodeLabelFor(keyId: string): string {
    for (const terminal of terminals) {
      const slot = (slotsByTerminal[terminal.id] ?? []).find((s) => s.managedKeyId === keyId)
      if (slot) return `Node ${slot.nodeAddress}` + (terminals.length > 1 ? ` (${terminal.name})` : '')
    }
    return 'Not assigned'
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const created = await api.createKey({ siteId: unit.id, displayName: displayName.trim() })
      const targetTerminal =
        terminals.length === 1 ? terminals[0] : terminals.find((t) => t.id === selectedTerminalId)
      if (targetTerminal) {
        const assignment = await assignKeyToNextAvailableNode(targetTerminal, created.id)
        if (!assignment.ok) {
          setError(
            assignment.reason === 'CAPACITY_FULL'
              ? `“${targetTerminal.name}” has no free key nodes left (${targetTerminal.configuredSlotCount} configured). The key was created but is not assigned to a cabinet slot.`
              : `Key was created, but assigning a cabinet node failed: ${assignment.message}`,
          )
        }
      } else if (terminals.length === 0) {
        setError(
          `“${unit.name}” has no cabinet registered yet — the key was created without a slot assignment. Register a Key Cabinet first.`,
        )
      }
      setOpen(false)
      setDisplayName('')
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to save key')
    } finally {
      setBusy(false)
    }
  }

  const capacityFull = terminals.length === 1 && availableNodes === 0

  return (
    <div>
      <SectionHeader
        step={5}
        title="Register Keys"
        desc={`Add managed keys for “${unit.name}”. Raw NFC UIDs never appear here.`}
        unitName={unit.name}
      />
      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading" />}

      <div className="toolbar-row" style={{ marginBottom: 12 }}>
        <Button
          icon={Plus}
          disabled={capacityFull}
          onClick={() => {
            setDisplayName('')
            setSelectedTerminalId(terminals[0]?.id ?? '')
            setError(null)
            setOpen(true)
          }}
        >
          Add key for this unit
        </Button>
        {terminals.length === 1 && availableNodes != null && (
          <span className="muted" style={{ marginLeft: 12 }}>
            {capacityFull
              ? `Cabinet full — 0 of ${terminals[0].configuredSlotCount} nodes free`
              : `${availableNodes} of ${terminals[0].configuredSlotCount} nodes free`}
          </span>
        )}
      </div>

      {keys.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Key</th>
                <th>Cabinet node</th>
                <th>Enrollment</th>
              </tr>
            </thead>
            <tbody>
              {keys.map((k) => (
                <tr key={k.id}>
                  <td className="cell-title">{k.displayName}</td>
                  <td>{nodeLabelFor(k.id)}</td>
                  <td>
                    {k.fobEnrollmentReference ? (
                      <span className="badge badge-success">Enrolled</span>
                    ) : (
                      <span className="muted">Not enrolled</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No keys for this unit yet.</div>
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>Add key</h2>
            <p className="dialog-copy">
              Unit is fixed to <strong>{unit.name}</strong>.
            </p>
            <div className="field">
              <label>Unit</label>
              <input value={unit.name} disabled readOnly />
            </div>
            <div className="field">
              <label>Key name</label>
              <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
            </div>
            {terminals.length > 1 && (
              <div className="field">
                <label>Cabinet</label>
                <select
                  value={selectedTerminalId}
                  onChange={(e) => setSelectedTerminalId(e.target.value)}
                  required
                >
                  <option value="" disabled>
                    Select the cabinet this key's node will be assigned in
                  </option>
                  {terminals.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name} (Box {t.boxAddress})
                    </option>
                  ))}
                </select>
              </div>
            )}
            {terminals.length === 0 && (
              <p className="dialog-copy muted">
                No cabinet is registered for this unit yet — the key will be created without a
                node assignment.
              </p>
            )}
            <div className="dialog-actions">
              <Button variant="outlined" icon={X} onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                Save
              </Button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}

// ─── Step 6: Permissions (unit locked) ───────────────────────────────────────

function StepPermissions({ unit }: { unit: SiteDto }) {
  const [grants, setGrants] = useState<Record<string, unknown>[]>([])
  const [users, setUsers] = useState<UserDto[]>([])
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
      const unitUsers = userRows.filter((u) => (u.assignedSiteIds ?? []).includes(unit.id))
      const unitKeys = keyRows.filter((k) => k.siteId === unit.id)
      setUsers(unitUsers)
      setKeys(unitKeys)
      setGrants(grantRows.filter((g) => g.siteId === unit.id))
      if (!userId && unitUsers[0]) setUserId(unitUsers[0].id)
      if (!keyId && unitKeys[0]) setKeyId(unitKeys[0].id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load permissions')
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
      setError(err instanceof ApiError ? err.message : 'Failed to save grant')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <SectionHeader
        step={6}
        title="Set Permissions"
        desc={`Grant exact keys to personnel under “${unit.name}”. Only people and keys for this unit are listed.`}
        unitName={unit.name}
      />
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
          Add access grant
        </Button>
      </div>

      {grants.length ? (
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
                    {users.find((u) => u.id === g.userId)?.displayName ?? String(g.userId)}
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
        !busy && <div className="empty-state">No grants for this unit yet.</div>
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>Add access grant</h2>
            <p className="dialog-copy">
              Unit is fixed to <strong>{unit.name}</strong>.
            </p>
            <div className="field">
              <label>Unit</label>
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
                Save
              </Button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}

// ─── Step 7: Pairing code (LAST) ─────────────────────────────────────────────

function StepPairing({ unit }: { unit: SiteDto }) {
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

  async function onGenerate(terminal: TerminalDto) {
    if (
      !(await confirmAction({
        message:
          'Generate a 6-digit pairing code for this cabinet? If a previous code or device session exists, it will be revoked. Copy the new code for the on-site technician.',
        danger: true,
      }))
    ) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      const result = await api.regenerateTerminalPairingCode(terminal.id)
      setPairing({
        code: result.code,
        expiresAtEpochMillis: result.expiresAtEpochMillis,
        terminalName: terminal.name,
        terminalId: terminal.id,
      })
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to generate pairing code')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <SectionHeader
        step={7}
        title="Generate Pairing Code"
        desc={`Last step. Issue the one-time 6-digit code for a cabinet under “${unit.name}”. The on-site technician enters it on the terminal; settings then download from the database.`}
        unitName={unit.name}
      />
      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading" />}

      {pairing && (
        <div className="notice pairing-code-banner" role="status">
          <h3>Pairing code — copy now</h3>
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
                <th>Paired</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {terminals.map((t) => (
                <tr key={t.id}>
                  <td className="cell-title">{t.name}</td>
                  <td>
                    <span className={`badge${t.paired ? ' badge-success' : ''}`}>
                      {t.paired ? 'Paired' : 'Not paired'}
                    </span>
                  </td>
                  <td className="col-actions">
                    <Button variant="link" disabled={busy} onClick={() => void onGenerate(t)}>
                      Generate 6-digit code
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && (
          <div className="empty-state">
            No cabinets for this unit. Go back to step 2 and register a key cabinet first.
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

  function goNext() {
    if (step === 0 && !unit) return
    setStep((s) => Math.min(s + 1, STEPS.length - 1))
  }

  function goPrev() {
    setStep((s) => Math.max(s - 1, 0))
  }

  function onUnitReady(site: SiteDto) {
    setUnit(site)
    setStep(1)
  }

  return (
    <section className="stack">
      <div className="page-header">
        <div>
          <h1>Registration</h1>
          <p className="muted">
            Continuous new-unit onboarding: register the unit once, then add its cabinets, personnel,
            keys and permissions without re-selecting the unit. The 6-digit pairing code is generated
            only at the last step for the on-site technician.
          </p>
        </div>
      </div>

      <div className="wizard-stepper">
        {STEPS.map((s, i) => (
          <button
            key={s.label}
            type="button"
            className={`wizard-step${i === step ? ' wizard-step-active' : ''}${i < step ? ' wizard-step-done' : ''}`}
            onClick={() => {
              if (i > 0 && !unit) return
              setStep(i)
            }}
            disabled={i > 0 && !unit}
            title={i > 0 && !unit ? 'Register a unit in step 1 first' : s.title}
          >
            <span className="wizard-step-num">{i + 1}</span>
            <span className="wizard-step-label">{s.label}</span>
          </button>
        ))}
      </div>

      <div className="card wizard-body">
        {step === 0 && <StepUnit unit={unit} onUnitReady={onUnitReady} />}
        {step === 1 && unit && <StepCabinet unit={unit} />}
        {step === 2 && unit && <StepCabinetSettings unit={unit} />}
        {step === 3 && unit && <StepPersonnel unit={unit} />}
        {step === 4 && unit && <StepKeys unit={unit} />}
        {step === 5 && unit && <StepPermissions unit={unit} />}
        {step === 6 && unit && <StepPairing unit={unit} />}
        {step > 0 && !unit && (
          <div className="empty-state">Register a unit in step 1 to continue.</div>
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
          <Button onClick={goNext} disabled={step === 0 && !unit}>
            Next →
          </Button>
        ) : (
          <Button
            icon={Plus}
            onClick={() => {
              setStep(0)
              setUnit(null)
            }}
          >
            Start another unit
          </Button>
        )}
      </div>
    </section>
  )
}
