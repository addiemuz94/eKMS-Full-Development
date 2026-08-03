import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check, Plus, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { SiteDto } from '../api/types'
import { Button, LinearProgress, useConfirm } from '../components/ui'

type Props = {
  lockedSiteId?: string
  embedded?: boolean
}

export function MultiAuthPage({ lockedSiteId, embedded = false }: Props = {}) {
  const { confirmAction, dialog } = useConfirm()
  const [rules, setRules] = useState<Record<string, unknown>[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [personnelGroups, setPersonnelGroups] = useState<Record<string, unknown>[]>([])
  const [keyGroups, setKeyGroups] = useState<Record<string, unknown>[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [editingRule, setEditingRule] = useState<Record<string, unknown> | null>(null)
  const [siteId, setSiteId] = useState(lockedSiteId ?? '')
  const [primary, setPrimary] = useState('')
  const [keyGroupId, setKeyGroupId] = useState('')
  const [assistant1, setAssistant1] = useState('')
  const [assistant2, setAssistant2] = useState('')

  const effectiveSiteId = lockedSiteId ?? siteId

  const visibleRules = useMemo(
    () => (lockedSiteId ? rules.filter((rule) => rule.siteId === lockedSiteId) : rules),
    [rules, lockedSiteId],
  )

  const sitePersonnelGroups = useMemo(
    () =>
      effectiveSiteId
        ? personnelGroups.filter((group) => group.siteId === effectiveSiteId)
        : personnelGroups,
    [personnelGroups, effectiveSiteId],
  )

  const siteKeyGroups = useMemo(
    () =>
      effectiveSiteId ? keyGroups.filter((group) => group.siteId === effectiveSiteId) : keyGroups,
    [keyGroups, effectiveSiteId],
  )

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [ruleRows, siteRows, personnelGroupRows, keyGroupRows] = await Promise.all([
        api.listMultiAuthRules(),
        api.listSites(),
        api.listPersonnelGroups(),
        api.listKeyGroups(),
      ])
      setRules(ruleRows)
      setSites(siteRows)
      setPersonnelGroups(personnelGroupRows)
      setKeyGroups(keyGroupRows)
      const nextSite = lockedSiteId ?? (siteId || siteRows[0]?.id || '')
      setSiteId(nextSite)
      const groupsForSite = nextSite
        ? personnelGroupRows.filter((g) => g.siteId === nextSite)
        : personnelGroupRows
      const keysForSite = nextSite
        ? keyGroupRows.filter((g) => g.siteId === nextSite)
        : keyGroupRows
      if (!primary && groupsForSite[0]) setPrimary(String(groupsForSite[0].id))
      if (!keyGroupId && keysForSite[0]) setKeyGroupId(String(keysForSite[0].id))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load rules')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [lockedSiteId])

  function label(list: Record<string, unknown>[], id: unknown) {
    return String(list.find((entry) => entry.id === id)?.name ?? id ?? '—')
  }

  function openCreate() {
    setEditingRule(null)
    if (lockedSiteId) setSiteId(lockedSiteId)
    setAssistant1('')
    setAssistant2('')
    const groups = lockedSiteId
      ? personnelGroups.filter((g) => g.siteId === lockedSiteId)
      : personnelGroups
    const keys = lockedSiteId ? keyGroups.filter((g) => g.siteId === lockedSiteId) : keyGroups
    setPrimary(groups[0] ? String(groups[0].id) : '')
    setKeyGroupId(keys[0] ? String(keys[0].id) : '')
    setError(null)
    setOpen(true)
  }

  function openEdit(rule: Record<string, unknown>) {
    setEditingRule(rule)
    setSiteId(String(rule.siteId ?? lockedSiteId ?? ''))
    setPrimary(String(rule.primaryPersonnelGroupId ?? ''))
    setKeyGroupId(String(rule.keyGroupId ?? ''))
    setAssistant1(rule.assistantGroupOneId ? String(rule.assistantGroupOneId) : '')
    setAssistant2(rule.assistantGroupTwoId ? String(rule.assistantGroupTwoId) : '')
    setError(null)
    setOpen(true)
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const payload = {
        siteId: lockedSiteId ?? siteId,
        primaryPersonnelGroupId: primary,
        assistantGroupOneId: assistant1 || null,
        assistantGroupTwoId: assistant2 || null,
        keyGroupId,
      }
      if (editingRule) {
        await api.updateMultiAuthRule(String(editingRule.id), {
          ...payload,
          expectedRevision: Number(editingRule.revision ?? 0),
        })
      } else {
        await api.createMultiAuthRule(payload)
      }
      setOpen(false)
      setEditingRule(null)
      await reload()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError(
          'This rule was changed by someone else since you opened it. Reloading the latest version — please reapply your edit.',
        )
        setOpen(false)
        setEditingRule(null)
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to save rule')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className={embedded ? 'resource-embedded' : undefined}>
      <div className={embedded ? 'embedded-header' : 'page-header'}>
        <div>
          {embedded ? (
            <h3 style={{ margin: 0 }}>Two-person approval</h3>
          ) : (
            <h1>Two-person approval</h1>
          )}
          <p className="muted">
            {embedded
              ? 'Primary and assistant personnel groups required for a key group in this location.'
              : 'Primary and assistant personnel groups required for a key group.'}
          </p>
        </div>
        <Button icon={Plus} onClick={openCreate}>
          Add rule
        </Button>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading rules" />}

      {visibleRules.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                {!lockedSiteId && <th>Location</th>}
                <th>Primary group</th>
                <th>Assistant groups</th>
                <th>Key group</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {visibleRules.map((rule) => (
                <tr key={String(rule.id)}>
                  {!lockedSiteId && (
                    <td className="cell-title">
                      {sites.find((site) => site.id === rule.siteId)?.name ?? 'Rule'}
                    </td>
                  )}
                  <td>{label(personnelGroups, rule.primaryPersonnelGroupId)}</td>
                  <td>
                    <div className="cell-stack">
                      <span>Assistant 1: {label(personnelGroups, rule.assistantGroupOneId)}</span>
                      <span>Assistant 2: {label(personnelGroups, rule.assistantGroupTwoId)}</span>
                    </div>
                  </td>
                  <td>{label(keyGroups, rule.keyGroupId)}</td>
                  <td className="col-actions">
                    <div className="row-actions">
                      <Button variant="link" onClick={() => openEdit(rule)}>
                        Edit
                      </Button>
                      <Button
                        variant="link"
                        onClick={() =>
                          void (async () => {
                            if (
                              !(await confirmAction({
                                message:
                                  'Delete this rule? It will move to Deleted items and can be restored for 60 days.',
                                danger: true,
                              }))
                            )
                              return
                            await api.deleteMultiAuthRule(String(rule.id))
                            await reload()
                          })()
                        }
                      >
                        Delete
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && (
          <div className="empty-state">No two-person approval rules yet. Create user and key groups first.</div>
        )
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>{editingRule ? 'Edit two-person approval rule' : 'Add two-person approval rule'}</h2>
            <p className="dialog-copy">
              Require one primary group plus optional assistant groups for a key group.
            </p>
            {!lockedSiteId && (
              <div className="field">
                <label>Location</label>
                <select value={siteId} onChange={(e) => setSiteId(e.target.value)} required>
                  {sites.map((site) => (
                    <option key={site.id} value={site.id}>
                      {site.name}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="field">
              <label>Primary personnel group</label>
              <select value={primary} onChange={(e) => setPrimary(e.target.value)} required>
                {sitePersonnelGroups.map((group) => (
                  <option key={String(group.id)} value={String(group.id)}>
                    {String(group.name)}
                  </option>
                ))}
              </select>
            </div>
            <div className="split">
              <div className="field">
                <label>Assistant group 1</label>
                <select value={assistant1} onChange={(e) => setAssistant1(e.target.value)}>
                  <option value="">— None —</option>
                  {sitePersonnelGroups.map((group) => (
                    <option key={String(group.id)} value={String(group.id)}>
                      {String(group.name)}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label>Assistant group 2</label>
                <select value={assistant2} onChange={(e) => setAssistant2(e.target.value)}>
                  <option value="">— None —</option>
                  {sitePersonnelGroups.map((group) => (
                    <option key={String(group.id)} value={String(group.id)}>
                      {String(group.name)}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div className="field">
              <label>Key group</label>
              <select value={keyGroupId} onChange={(e) => setKeyGroupId(e.target.value)} required>
                {siteKeyGroups.map((group) => (
                  <option key={String(group.id)} value={String(group.id)}>
                    {String(group.name)}
                  </option>
                ))}
              </select>
            </div>
            <div className="dialog-actions">
              <Button
                variant="outlined"
                icon={X}
                onClick={() => {
                  setOpen(false)
                  setEditingRule(null)
                }}
              >
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                {editingRule ? 'Save changes' : 'Save'}
              </Button>
            </div>
          </form>
        </div>
      )}

      {dialog}
    </section>
  )
}
