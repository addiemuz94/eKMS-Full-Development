import { Fragment, useEffect, useMemo, useState } from 'react'
import { Check, X } from 'lucide-react'
import { Navigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { RoleCapabilitiesMatrixResponse, RoleCapabilityCatalogEntry } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { useCapabilities } from '../auth/CapabilitiesContext'
import { Button, LinearProgress, useConfirm } from '../components/ui'

const ROLE_COLUMNS = [
  { id: 'SUPER_ADMIN', label: 'Super Admin', editable: false },
  { id: 'REGIONAL_ADMIN', label: 'Regional Admin', editable: true },
  { id: 'TECHNICIAN', label: 'Technician', editable: true },
  { id: 'VENDOR', label: 'Vendor', editable: true },
] as const

type Draft = Record<string, Record<string, boolean>>

export function RolePermissionsPage() {
  const { session } = useAuth()
  const { confirmAction, dialog } = useConfirm()
  const { reload: reloadMyCapabilities } = useCapabilities()
  const [data, setData] = useState<RoleCapabilitiesMatrixResponse | null>(null)
  const [draft, setDraft] = useState<Draft>({})
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [savingRole, setSavingRole] = useState<string | null>(null)

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const matrix = await api.getRoleCapabilitiesMatrix()
      setData(matrix)
      setDraft(structuredClone(matrix.matrix))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load role permissions')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    if (session?.role !== 'SUPER_ADMIN') return
    void reload()
  }, [session?.role])

  const dirtyRoles = useMemo(() => {
    if (!data) return new Set<string>()
    const dirty = new Set<string>()
    for (const role of data.editableRoles) {
      const original = data.matrix[role] || {}
      const next = draft[role] || {}
      const keys = new Set([...Object.keys(original), ...Object.keys(next)])
      for (const key of keys) {
        if (Boolean(original[key]) !== Boolean(next[key])) {
          dirty.add(role)
          break
        }
      }
    }
    return dirty
  }, [data, draft])

  function toggle(role: string, key: string) {
    setDraft((prev) => ({
      ...prev,
      [role]: { ...(prev[role] || {}), [key]: !prev[role]?.[key] },
    }))
    setNotice(null)
  }

  async function saveRole(role: string) {
    if (!data) return
    const ok = await confirmAction({
      title: `Save ${ROLE_COLUMNS.find((c) => c.id === role)?.label ?? role} permissions?`,
      message:
        'Disabled capabilities take effect immediately for that role. Newly unlocked Admin tools stay off until you enable them here.',
      confirmLabel: 'Save',
    })
    if (!ok) return

    setSavingRole(role)
    setError(null)
    setNotice(null)
    try {
      const ceiling = data.ceilings[role] || []
      const capabilities: Record<string, boolean> = {}
      for (const key of ceiling) {
        capabilities[key] = Boolean(draft[role]?.[key])
      }
      const next = await api.updateRoleCapabilities({ role, capabilities })
      setData(next)
      setDraft(structuredClone(next.matrix))
      setNotice(`Saved permissions for ${role.replaceAll('_', ' ').toLowerCase()}.`)
      await reloadMyCapabilities()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to save role permissions')
      await reload()
    } finally {
      setSavingRole(null)
    }
  }

  function cellState(
    entry: RoleCapabilityCatalogEntry,
    roleId: string,
  ): 'on' | 'off' | 'locked-on' | 'locked-off' {
    if (roleId === 'SUPER_ADMIN') return 'locked-on'
    const ceiling = data?.ceilings[roleId] || []
    if (!ceiling.includes(entry.key)) return 'locked-off'
    return draft[roleId]?.[entry.key] ? 'on' : 'off'
  }

  const grouped = useMemo(() => {
    const catalog = data?.catalog ?? []
    const groups: { name: string; entries: RoleCapabilityCatalogEntry[] }[] = []
    for (const entry of catalog) {
      const last = groups[groups.length - 1]
      if (last && last.name === entry.group) last.entries.push(entry)
      else groups.push({ name: entry.group, entries: [entry] })
    }
    return groups
  }, [data])

  if (session?.role !== 'SUPER_ADMIN') {
    return <Navigate to="/" replace />
  }

  return (
    <section>
      {dialog}
      <div className="page-header">
        <div>
          <h1>Role permissions</h1>
          <p className="muted">
            Enable or disable what Regional Admin, Technician, and Vendor can do. Super Admin stays
            unrestricted. Admin tools (Registration, User Management, Deleted items, Erase data, and
            more) start off — tick a cell and Save to grant that role access.
          </p>
        </div>
      </div>

      {busy && !data ? <LinearProgress /> : null}
      {error ? <div className="notice error">{error}</div> : null}
      {notice ? <div className="notice">{notice}</div> : null}

      {data ? (
        <div className="data-panel" style={{ overflowX: 'auto' }}>
          <table className="data-table role-permissions-table">
            <thead>
              <tr>
                <th scope="col">Capability</th>
                {ROLE_COLUMNS.map((col) => (
                  <th key={col.id} scope="col" style={{ textAlign: 'center', minWidth: 110 }}>
                    {col.label}
                    {col.editable && dirtyRoles.has(col.id) ? (
                      <div style={{ marginTop: 8 }}>
                        <Button
                          type="button"
                          icon={Check}
                          loading={savingRole === col.id}
                          disabled={Boolean(savingRole)}
                          onClick={() => void saveRole(col.id)}
                        >
                          Save
                        </Button>
                      </div>
                    ) : null}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {grouped.map((group) => (
                <Fragment key={group.name}>
                  <tr className="role-permissions-group">
                    <td colSpan={ROLE_COLUMNS.length + 1}>
                      <strong>{group.name}</strong>
                    </td>
                  </tr>
                  {group.entries.map((entry) => (
                    <tr key={entry.key}>
                      <td>
                        <div>{entry.label}</div>
                        <div className="muted" style={{ fontSize: '0.85rem' }}>
                          {entry.description}
                        </div>
                      </td>
                      {ROLE_COLUMNS.map((col) => {
                        const state = cellState(entry, col.id)
                        const editable = state === 'on' || state === 'off'
                        return (
                          <td key={col.id} style={{ textAlign: 'center' }}>
                            {editable ? (
                              <label className="role-cap-toggle">
                                <input
                                  type="checkbox"
                                  checked={state === 'on'}
                                  disabled={Boolean(savingRole)}
                                  onChange={() => toggle(col.id, entry.key)}
                                  aria-label={`${entry.label} for ${col.label}`}
                                />
                              </label>
                            ) : state === 'locked-on' ? (
                              <span className="muted" title="Always enabled">
                                <Check size={16} aria-hidden />
                              </span>
                            ) : (
                              <span className="muted" title="Not available for this role">
                                <X size={16} aria-hidden />
                              </span>
                            )}
                          </td>
                        )
                      })}
                    </tr>
                  ))}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </section>
  )
}
