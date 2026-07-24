import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { api, ApiError } from '../api/client'
import type { SiteDto } from '../api/types'
import { Button, LinearProgress } from './ui'

export type FieldDef =
  | { name: string; label: string; type: 'text' | 'number'; required?: boolean }
  | { name: string; label: string; type: 'select'; required?: boolean; options: { value: string; label: string }[] }
  | { name: string; label: string; type: 'site'; required?: boolean }

type Props = {
  title: string
  description: string
  addLabel: string
  fields: FieldDef[]
  list: () => Promise<Record<string, unknown>[]>
  create: (payload: Record<string, unknown>) => Promise<unknown>
  update?: (id: string, payload: Record<string, unknown>) => Promise<unknown>
  remove?: (id: string) => Promise<unknown>
  renderLines: (item: Record<string, unknown>, sites: SiteDto[]) => string[]
  titleOf: (item: Record<string, unknown>) => string
  extraActions?: (item: Record<string, unknown>, reload: () => Promise<void>) => ReactNode
  buildPayload?: (values: Record<string, string>, sites: SiteDto[]) => Record<string, unknown>
}

export function ResourcePage({
  title,
  description,
  addLabel,
  fields,
  list,
  create,
  update,
  remove,
  renderLines,
  titleOf,
  extraActions,
  buildPayload,
}: Props) {
  const [items, setItems] = useState<Record<string, unknown>[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [query, setQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [values, setValues] = useState<Record<string, string>>({})

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [rows, siteRows] = await Promise.all([list(), api.listSites()])
      setItems(rows)
      setSites(siteRows)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return items
    return items.filter((item) => JSON.stringify(item).toLowerCase().includes(q))
  }, [items, query])

  function openDialog() {
    const initial: Record<string, string> = {}
    for (const field of fields) {
      if (field.type === 'site') initial[field.name] = sites[0]?.id ?? ''
      else if (field.type === 'select') initial[field.name] = field.options[0]?.value ?? ''
      else initial[field.name] = ''
    }
    setValues(initial)
    setEditingId(null)
    setOpen(true)
  }

  function openEditDialog(item: Record<string, unknown>) {
    const initial: Record<string, string> = {}
    for (const field of fields) {
      const raw = item[field.name]
      initial[field.name] = raw == null ? '' : String(raw)
    }
    setValues(initial)
    setEditingId(String(item.id ?? ''))
    setError(null)
    setOpen(true)
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const payload = buildPayload
        ? buildPayload(values, sites)
        : Object.fromEntries(
            fields.map((field) => {
              const raw = values[field.name] ?? ''
              if (field.type === 'number') return [field.name, Number(raw)]
              if (!raw && !field.required) return [field.name, null]
              return [field.name, raw]
            }),
          )
      if (editingId && update) {
        const current = items.find((item) => String(item.id ?? '') === editingId)
        await update(editingId, { ...payload, expectedRevision: Number(current?.revision ?? 0) })
        setNotice('Saved.')
      } else {
        await create(payload)
        setNotice('Saved.')
      }
      setOpen(false)
      setEditingId(null)
      await reload()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError(
          'This record was changed by someone else since you opened it. Reloading the latest version — please reapply your edit.',
        )
        setOpen(false)
        setEditingId(null)
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Save failed')
      }
    } finally {
      setBusy(false)
    }
  }

  async function onRemove(id: string) {
    if (!remove) return
    if (!confirm('Move this record to the Recycle Bin?')) return
    setBusy(true)
    try {
      await remove(id)
      setNotice('Moved to Recycle Bin.')
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Delete failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>{title}</h1>
          <p className="muted">{description}</p>
        </div>
        <Button onClick={openDialog}>{addLabel}</Button>
      </div>

      {notice && <div className="notice">{notice}</div>}
      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading" />}

      <input className="search" placeholder="Search…" value={query} onChange={(e) => setQuery(e.target.value)} />

      {filtered.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Details</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((item) => {
                const id = String(item.id ?? '')
                return (
                  <tr key={id || titleOf(item)}>
                    <td className="cell-title">{titleOf(item)}</td>
                    <td>
                      <div className="cell-stack">
                        {renderLines(item, sites).map((line) => (
                          <span key={line}>{line}</span>
                        ))}
                      </div>
                    </td>
                    <td className="col-actions">
                      <div className="row-actions">
                        {update && (
                          <Button variant="link" onClick={() => openEditDialog(item)}>
                            Edit
                          </Button>
                        )}
                        {remove && (
                          <Button variant="link" onClick={() => void onRemove(id)}>
                            Recycle
                          </Button>
                        )}
                        {extraActions?.(item, reload)}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No records yet.</div>
      )}

      {open && (
        <div className="dialog-backdrop" onClick={() => setOpen(false)}>
          <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={onSave}>
            <h2>{editingId ? `Edit ${title.toLowerCase()}` : addLabel}</h2>
            <p className="dialog-copy">{description}</p>
            {fields.map((field) => (
              <div className="field" key={field.name}>
                <label>{field.label}</label>
                {field.type === 'site' || field.type === 'select' ? (
                  <select
                    value={values[field.name] ?? ''}
                    required={field.required}
                    onChange={(e) => setValues((current) => ({ ...current, [field.name]: e.target.value }))}
                  >
                    {(field.type === 'site'
                      ? sites.map((site) => ({ value: site.id, label: site.name }))
                      : field.options
                    ).map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                ) : (
                  <input
                    type={field.type === 'number' ? 'number' : 'text'}
                    value={values[field.name] ?? ''}
                    required={field.required}
                    onChange={(e) => setValues((current) => ({ ...current, [field.name]: e.target.value }))}
                  />
                )}
              </div>
            ))}
            <div className="dialog-actions">
              <Button
                variant="outlined"
                onClick={() => {
                  setOpen(false)
                  setEditingId(null)
                }}
              >
                Cancel
              </Button>
              <Button type="submit" loading={busy}>
                {editingId ? 'Save changes' : 'Save'}
              </Button>
            </div>
          </form>
        </div>
      )}
    </section>
  )
}

export function siteName(sites: SiteDto[], id: unknown) {
  return sites.find((site) => site.id === id)?.name ?? String(id ?? '—')
}
