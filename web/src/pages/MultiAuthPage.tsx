import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError } from '../api/client'
import type { SiteDto } from '../api/types'

export function MultiAuthPage() {
  const [rules, setRules] = useState<Record<string, unknown>[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [personnelGroups, setPersonnelGroups] = useState<Record<string, unknown>[]>([])
  const [keyGroups, setKeyGroups] = useState<Record<string, unknown>[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [editingRule, setEditingRule] = useState<Record<string, unknown> | null>(null)
  const [siteId, setSiteId] = useState('')
  const [primary, setPrimary] = useState('')
  const [keyGroupId, setKeyGroupId] = useState('')
  const [assistant1, setAssistant1] = useState('')
  const [assistant2, setAssistant2] = useState('')

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
      if (!siteId && siteRows[0]) setSiteId(siteRows[0].id)
      if (!primary && personnelGroupRows[0]) setPrimary(String(personnelGroupRows[0].id))
      if (!keyGroupId && keyGroupRows[0]) setKeyGroupId(String(keyGroupRows[0].id))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load rules')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  function label(list: Record<string, unknown>[], id: unknown) {
    return String(list.find((entry) => entry.id === id)?.name ?? id ?? '—')
  }

  function openEdit(rule: Record<string, unknown>) {
    setEditingRule(rule)
    setSiteId(String(rule.siteId ?? ''))
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
        siteId,
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
    <section>
      <div className="page-header">
        <div>
          <h1>Multi-authentication Rules</h1>
          <p className="muted">Primary and assistant personnel groups required for a key group.</p>
        </div>
        <button
          className="btn"
          type="button"
          onClick={() => {
            setEditingRule(null)
            setOpen(true)
          }}
        >
          Add rule
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {rules.map((rule) => (
        <article className="card" key={String(rule.id)}>
          <h3>{sites.find((site) => site.id === rule.siteId)?.name ?? 'Rule'}</h3>
          <div className="meta">
            <div>Primary group: {label(personnelGroups, rule.primaryPersonnelGroupId)}</div>
            <div>Assistant 1: {label(personnelGroups, rule.assistantGroupOneId)}</div>
            <div>Assistant 2: {label(personnelGroups, rule.assistantGroupTwoId)}</div>
            <div>Key group: {label(keyGroups, rule.keyGroupId)}</div>
          </div>
          <div className="card-actions">
            <button className="btn linkish" type="button" onClick={() => openEdit(rule)}>
              Edit
            </button>
            <button
              className="btn linkish"
              type="button"
              onClick={() =>
                void (async () => {
                  if (!confirm('Move rule to Recycle Bin?')) return
                  await api.deleteMultiAuthRule(String(rule.id))
                  await reload()
                })()
              }
            >
              Move to Recycle Bin
            </button>
          </div>
        </article>
      ))}

      {!rules.length && !busy && <div className="empty-state">No multi-auth rules yet. Create user and key groups first.</div>}

      {open && (
        <div className="dialog-backdrop" onClick={() => setOpen(false)}>
          <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={onSave}>
            <h2>{editingRule ? 'Edit multi-auth rule' : 'Add multi-auth rule'}</h2>
            <p className="dialog-copy">Require one primary group plus optional assistant groups for a key group.</p>
            <div className="field">
              <label>Unit</label>
              <select value={siteId} onChange={(e) => setSiteId(e.target.value)} required>
                {sites.map((site) => (
                  <option key={site.id} value={site.id}>
                    {site.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>Primary personnel group</label>
              <select value={primary} onChange={(e) => setPrimary(e.target.value)} required>
                {personnelGroups.map((group) => (
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
                  {personnelGroups.map((group) => (
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
                  {personnelGroups.map((group) => (
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
                {keyGroups.map((group) => (
                  <option key={String(group.id)} value={String(group.id)}>
                    {String(group.name)}
                  </option>
                ))}
              </select>
            </div>
            <div className="dialog-actions">
              <button
                className="btn secondary"
                type="button"
                onClick={() => {
                  setOpen(false)
                  setEditingRule(null)
                }}
              >
                Cancel
              </button>
              <button className="btn" type="submit" disabled={busy}>
                {editingRule ? 'Save changes' : 'Save'}
              </button>
            </div>
          </form>
        </div>
      )}
    </section>
  )
}
