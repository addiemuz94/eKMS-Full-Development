import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Check, Plus, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { TerminalDto, UserDto } from '../api/types'
import { Button, useConfirm } from './ui'

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: 'Super Admin',
  REGIONAL_ADMIN: 'Regional Admin',
  TECHNICIAN: 'Technician',
  VENDOR: 'Vendor',
  GOD_ADMIN: 'System account',
}

type Props = {
  terminal: TerminalDto
  unitName: string
  onChanged?: () => void
}

/**
 * Unit-scoped personnel assign/create/remove for a selected cabinet.
 */
export function CabinetUnitPeoplePanel({ terminal, unitName, onChanged }: Props) {
  const { confirmAction, dialog } = useConfirm()
  const [people, setPeople] = useState<UserDto[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [assignOpen, setAssignOpen] = useState(false)
  const [assignUserId, setAssignUserId] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [staffId, setStaffId] = useState('')
  const [role, setRole] = useState('TECHNICIAN')
  const [password, setPassword] = useState('')

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      setPeople(await api.listUsers())
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load personnel')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [terminal.siteId])

  const unitPeople = useMemo(
    () =>
      people.filter(
        (p) => p.role !== 'SUPER_ADMIN' && (p.assignedSiteIds ?? []).includes(terminal.siteId),
      ),
    [people, terminal.siteId],
  )

  const assignablePeople = useMemo(
    () =>
      people.filter(
        (p) => p.role !== 'SUPER_ADMIN' && !(p.assignedSiteIds ?? []).includes(terminal.siteId),
      ),
    [people, terminal.siteId],
  )

  async function assignExisting(e: FormEvent) {
    e.preventDefault()
    if (!assignUserId) return
    const person = people.find((p) => p.id === assignUserId)
    if (!person) return
    setBusy(true)
    setError(null)
    try {
      // REGIONAL_ADMIN can cover multiple locations (Tier 2b) — attaching them to this cabinet's
      // location must ADD to their existing assignedSiteIds, not replace the whole array (that
      // would silently wipe out every other location they cover). Technician/Vendor keep the
      // original replace behavior exactly as-is — one physical work site, moving them here is
      // meant to replace, not accumulate (see the dialog copy below, also made role-conditional).
      const nextSiteIds =
        person.role === 'REGIONAL_ADMIN'
          ? Array.from(new Set([...(person.assignedSiteIds ?? []), terminal.siteId]))
          : [terminal.siteId]
      await api.updateUser(person.id, {
        displayName: person.displayName,
        email: person.email,
        role: person.role,
        staffId: person.staffId ?? null,
        assignedSiteIds: nextSiteIds,
        expectedRevision: person.revision,
      })
      setAssignOpen(false)
      setAssignUserId('')
      await reload()
      onChanged?.()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError('This personnel record was changed by someone else. Reloading — please retry.')
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to assign personnel')
      }
    } finally {
      setBusy(false)
    }
  }

  async function removeFromUnit(person: UserDto) {
    // Independent, pre-existing bug found while fixing the above (not part of the multi-site
    // ask): this used to unconditionally send `assignedSiteIds: []`, which users.js's PATCH
    // schema always rejects for any non-SUPER_ADMIN role ("...require at least one assigned
    // site") — so "Remove from location" 400'd for every Technician/Vendor/Regional Admin, every
    // time, before this fix. Now removes only this cabinet's site from the person's existing
    // list (a no-op-turned-correct change for a multi-site Regional Admin; for a single-site
    // Technician/Vendor it still empties their list, so the same "must keep one location" rule
    // is checked client-side first, with an actionable message, instead of hitting that 400 blind.
    const nextSiteIds = (person.assignedSiteIds ?? []).filter((id) => id !== terminal.siteId)
    if (person.role !== 'SUPER_ADMIN' && nextSiteIds.length === 0) {
      setError(
        `${person.displayName} only has ${unitName} assigned. ${ROLE_LABELS[person.role] ?? person.role} accounts require at least one location — reassign them to a different location in User Management, or delete the account instead.`,
      )
      return
    }
    if (
      !(await confirmAction({
        message: `Remove ${person.displayName} from ${unitName}?`,
        danger: true,
      }))
    ) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.updateUser(person.id, {
        displayName: person.displayName,
        email: person.email,
        role: person.role,
        staffId: person.staffId ?? null,
        assignedSiteIds: nextSiteIds,
        expectedRevision: person.revision,
      })
      await reload()
      onChanged?.()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError('This personnel record was changed by someone else. Reloading — please retry.')
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to remove personnel')
      }
    } finally {
      setBusy(false)
    }
  }

  async function createForUnit(e: FormEvent) {
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
        assignedSiteIds: [terminal.siteId],
        password: password.length >= 8 ? password : undefined,
      })
      setCreateOpen(false)
      setDisplayName('')
      setEmail('')
      setStaffId('')
      setPassword('')
      setRole('TECHNICIAN')
      await reload()
      onChanged?.()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to create personnel')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="cabinet-people-section" style={{ borderTop: 'none', marginTop: 0, paddingTop: 0 }}>
      <div className="cabinet-people-section-header">
        <div>
          <h3 style={{ margin: 0 }}>Assign User</h3>
          <p className="muted" style={{ margin: '4px 0 0' }}>
            Assign personnel already registered under{' '}
            <Link to="/personnel">User Management</Link> to {unitName}. To create or edit accounts,
            use User Management.
          </p>
        </div>
        <div className="cabinets-detail-actions">
          <Button
            variant="outlined"
            icon={Plus}
            onClick={() => {
              setAssignUserId(assignablePeople[0]?.id ?? '')
              setAssignOpen(true)
            }}
            disabled={!assignablePeople.length}
          >
            Assign personnel
          </Button>
          <Button
            icon={Plus}
            onClick={() => {
              setDisplayName('')
              setEmail('')
              setStaffId('')
              setPassword('')
              setRole('TECHNICIAN')
              setCreateOpen(true)
            }}
          >
            Create and assign personnel
          </Button>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {unitPeople.length ? (
        <table className="data-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Role</th>
              <th>Email</th>
              <th className="col-actions">Actions</th>
            </tr>
          </thead>
          <tbody>
            {unitPeople.map((person) => (
              <tr key={person.id}>
                <td className="cell-title">{person.displayName}</td>
                <td>
                  <span className="badge">{ROLE_LABELS[person.role] ?? person.role}</span>
                </td>
                <td>{person.email}</td>
                <td className="col-actions">
                  <Button variant="link" onClick={() => void removeFromUnit(person)}>
                    Remove from location
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        !busy && <div className="empty-state">No personnel assigned to this location yet.</div>
      )}

      {assignOpen && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={assignExisting}>
            <h2>Assign existing personnel</h2>
            <p className="dialog-copy">
              Assign an account to <strong>{unitName}</strong>.{' '}
              {people.find((p) => p.id === assignUserId)?.role === 'REGIONAL_ADMIN'
                ? 'This adds it to their assigned locations, alongside any others they already cover.'
                : 'This replaces their current location assignment.'}
            </p>
            <div className="field">
              <label>Personnel</label>
              <select value={assignUserId} onChange={(e) => setAssignUserId(e.target.value)} required>
                {assignablePeople.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.displayName} ({u.role})
                  </option>
                ))}
              </select>
            </div>
            <div className="dialog-actions">
              <Button variant="outlined" icon={X} onClick={() => setAssignOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                Assign
              </Button>
            </div>
          </form>
        </div>
      )}

      {createOpen && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={createForUnit}>
            <h2>Create personnel for location</h2>
            <p className="dialog-copy">
              New account will be assigned to <strong>{unitName}</strong>.
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
                <option value="TECHNICIAN">Technician</option>
                <option value="VENDOR">Vendor</option>
                <option value="REGIONAL_ADMIN">Regional Admin</option>
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
              <Button variant="outlined" icon={X} onClick={() => setCreateOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                Create
              </Button>
            </div>
          </form>
        </div>
      )}

      {dialog}
    </div>
  )
}
