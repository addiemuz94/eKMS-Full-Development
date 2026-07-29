import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check, Plus, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { SiteDto, UserDto } from '../api/types'
import { Button, LinearProgress, useConfirm } from '../components/ui'

type SortKey = 'name' | 'role' | 'site'
type SortDir = 'asc' | 'desc'

export function PersonnelPage() {
  const { confirmAction, dialog } = useConfirm()
  const [people, setPeople] = useState<UserDto[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [query, setQuery] = useState('')
  const [roleFilter, setRoleFilter] = useState('all')
  const [siteFilter, setSiteFilter] = useState('all')
  const [sort, setSort] = useState<SortKey>('name')
  const [dir, setDir] = useState<SortDir>('asc')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [editingPerson, setEditingPerson] = useState<UserDto | null>(null)
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [staffId, setStaffId] = useState('')
  const [role, setRole] = useState('TECHNICIAN')
  const [siteId, setSiteId] = useState('')
  const [password, setPassword] = useState('')

  const reload = useCallback(async () => {
    setBusy(true)
    setError(null)
    try {
      const [userRows, siteRows] = await Promise.all([api.listUsers(), api.listSites()])
      setPeople(userRows)
      setSites(siteRows)
      setSiteId((current) => current || siteRows[0]?.id || '')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load personnel')
    } finally {
      setBusy(false)
    }
  }, [])

  useEffect(() => {
    void reload()
  }, [reload])

  function toggleSort(k: SortKey) {
    if (sort === k) setDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    else { setSort(k); setDir('asc') }
  }
  const arrow = (k: SortKey) => sort === k ? (dir === 'asc' ? ' ↑' : ' ↓') : ''

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return people
      .filter((p) => {
        const sn = (p.assignedSiteIds ?? []).map((id) => sites.find((s) => s.id === id)?.name ?? '').join(' ')
        const matchQ = !q || p.displayName.toLowerCase().includes(q) || p.email.toLowerCase().includes(q) || (p.staffId ?? '').toLowerCase().includes(q) || sn.toLowerCase().includes(q)
        const matchRole = roleFilter === 'all' || p.role === roleFilter
        const matchSite = siteFilter === 'all' || (p.assignedSiteIds ?? []).includes(siteFilter)
        return matchQ && matchRole && matchSite
      })
      .sort((a, b) => {
        let av = '', bv = ''
        if (sort === 'name') { av = a.displayName; bv = b.displayName }
        else if (sort === 'role') { av = a.role; bv = b.role }
        else {
          av = (a.assignedSiteIds ?? []).map((id) => sites.find((s) => s.id === id)?.name ?? '').join()
          bv = (b.assignedSiteIds ?? []).map((id) => sites.find((s) => s.id === id)?.name ?? '').join()
        }
        return dir === 'asc' ? av.localeCompare(bv) : bv.localeCompare(av)
      })
  }, [people, sites, query, roleFilter, siteFilter, sort, dir])

  function siteLabel(ids?: string[]) {
    if (!ids?.length) return '—'
    return ids.map((id) => sites.find((s) => s.id === id)?.name ?? id).join(', ')
  }

  function resetForm() {
    setDisplayName('')
    setEmail('')
    setStaffId('')
    setPassword('')
    setRole('TECHNICIAN')
  }

  function openEdit(person: UserDto) {
    setEditingPerson(person)
    setDisplayName(person.displayName)
    setEmail(person.email)
    setStaffId(person.staffId ?? '')
    setRole(person.role)
    setSiteId(person.assignedSiteIds?.[0] ?? '')
    setPassword('')
    setError(null)
    setOpen(true)
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
    if (!email.trim() || !email.includes('@')) {
      setError('Enter a valid account email.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const payload = {
        displayName: displayName.trim(),
        email: email.trim(),
        role,
        staffId: staffId.trim() || null,
        assignedSiteIds: role === 'SUPER_ADMIN' ? [] : siteId ? [siteId] : [],
      }
      if (editingPerson) {
        await api.updateUser(editingPerson.id, {
          ...payload,
          expectedRevision: editingPerson.revision,
        })
      } else {
        await api.createUser({
          ...payload,
          password: password.length >= 8 ? password : undefined,
        })
      }
      setOpen(false)
      setEditingPerson(null)
      resetForm()
      await reload()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError('This person was changed by someone else since you opened it. Reloading — please reapply.')
        setOpen(false)
        setEditingPerson(null)
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to save personnel')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Personnel Management</h1>
          <p className="muted">
            Create technician and vendor accounts for each unit. NFC, fingerprint and face enrollment
            stay on the physical terminal only — they are not stored in the portal database.
          </p>
        </div>
        <Button
          icon={Plus}
          onClick={() => {
            resetForm()
            setEditingPerson(null)
            setOpen(true)
          }}
        >
          Add personnel
        </Button>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading personnel" />}

      <div className="toolbar-row">
        <input
          className="search"
          placeholder="Search name, email, staff ID or unit…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ flex: 1 }}
        />
        <select value={roleFilter} onChange={(e) => setRoleFilter(e.target.value)} title="Filter by role">
          <option value="all">All roles</option>
          <option value="TECHNICIAN">Technician</option>
          <option value="VENDOR">Vendor</option>
          <option value="REGIONAL_ADMIN">Regional Admin</option>
          <option value="SUPER_ADMIN">Super Admin</option>
        </select>
        <select value={siteFilter} onChange={(e) => setSiteFilter(e.target.value)} title="Filter by unit">
          <option value="all">All units</option>
          {sites.map((s) => (
            <option key={s.id} value={s.id}>{s.name}</option>
          ))}
        </select>
      </div>

      {filtered.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th style={{ cursor: 'pointer' }} onClick={() => toggleSort('name')}>Name{arrow('name')}</th>
                <th>Staff ID</th>
                <th>Email</th>
                <th style={{ cursor: 'pointer' }} onClick={() => toggleSort('role')}>Role{arrow('role')}</th>
                <th>Status</th>
                <th style={{ cursor: 'pointer' }} onClick={() => toggleSort('site')}>Units{arrow('site')}</th>
                <th>Credentials</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((person) => (
                <tr key={person.id}>
                  <td className="cell-title">{person.displayName}</td>
                  <td className="mono">{person.staffId?.trim() || '—'}</td>
                  <td>{person.email}</td>
                  <td>
                    <span className="badge">{person.role}</span>
                  </td>
                  <td>
                    <span className="badge">{person.accountStatus || 'ACTIVE'}</span>
                  </td>
                  <td>{siteLabel(person.assignedSiteIds)}</td>
                  <td>
                    <span className="badge">Terminal-local only</span>
                  </td>
                  <td className="col-actions">
                    {person.role !== 'SUPER_ADMIN' && (
                      <div className="row-actions">
                        <Button variant="link" onClick={() => openEdit(person)}>Edit</Button>
                        <Button
                          variant="link"
                          onClick={() =>
                            void (async () => {
                              if (!(await confirmAction({ message: 'Move personnel to Recycle Bin?', danger: true })))
                                return
                              await api.deleteUser(person.id)
                              await reload()
                            })()
                          }
                        >
                          Recycle
                        </Button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No personnel match current filters.</div>
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>{editingPerson ? 'Edit personnel' : 'Add personnel'}</h2>
            <p className="dialog-copy">
              Same account can sign in on the web portal and (when server-linked) on the terminal.
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
              <input
                value={staffId}
                onChange={(e) => setStaffId(e.target.value)}
                placeholder="Employee / external ID"
              />
            </div>
            <div className="split">
              <div className="field">
                <label>Role</label>
                <select value={role} onChange={(e) => setRole(e.target.value)}>
                  <option value="TECHNICIAN">TECHNICIAN</option>
                  <option value="VENDOR">VENDOR</option>
                  <option value="REGIONAL_ADMIN">REGIONAL_ADMIN</option>
                  <option value="SUPER_ADMIN">SUPER_ADMIN</option>
                </select>
              </div>
              {role !== 'SUPER_ADMIN' && (
                <div className="field">
                  <label>Assigned unit</label>
                  <select value={siteId} onChange={(e) => setSiteId(e.target.value)} required>
                    {sites.map((s) => (
                      <option key={s.id} value={s.id}>{s.name}</option>
                    ))}
                  </select>
                </div>
              )}
            </div>
            {!editingPerson && (
              <div className="field">
                <label>Initial password (min 8, optional)</label>
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  minLength={8}
                />
              </div>
            )}
            <div className="dialog-actions">
              <Button
                variant="outlined"
                icon={X}
                onClick={() => {
                  setOpen(false)
                  setEditingPerson(null)
                }}
              >
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                {editingPerson ? 'Save changes' : 'Save'}
              </Button>
            </div>
          </form>
        </div>
      )}

      {dialog}
    </section>
  )
}
