import { useEffect, useState, type FormEvent } from 'react'
import { Check } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { TerminalCabinetSettingsDto, TerminalDto } from '../api/types'
import { Button, LinearProgress } from './ui'

type Props = {
  terminal: TerminalDto
  /** Optional heading override */
  title?: string
  onSaved?: (settings: TerminalCabinetSettingsDto) => void
}

export function CabinetSettingsForm({ terminal, title, onSaved }: Props) {
  const [settings, setSettings] = useState<TerminalCabinetSettingsDto | null>(null)
  const [takeWarning, setTakeWarning] = useState('15')
  const [doorClose, setDoorClose] = useState('15')
  const [certification, setCertification] = useState(false)
  const [returnVideo, setReturnVideo] = useState(false)
  const [retrievalVideo, setRetrievalVideo] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const s = await api.getCabinetSettings(terminal.id)
      setSettings(s)
      setTakeWarning(String(s.takeWarningTimeSeconds))
      setDoorClose(String(s.doorCloseWarningTimeSeconds))
      setCertification(s.keyReturnCertificationEnabled)
      setReturnVideo(s.returnKeyVideoEnabled)
      setRetrievalVideo(s.keyRetrievalVideoEnabled)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load cabinet settings')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [terminal.id])

  async function onSave(e: FormEvent) {
    e.preventDefault()
    if (!settings) return
    const take = Number(takeWarning)
    const door = Number(doorClose)
    if (!Number.isInteger(take) || take < 1 || take > 300) {
      setError('Take Warning Time must be an integer from 1 to 300 seconds.')
      return
    }
    if (!Number.isInteger(door) || door < 1 || door > 300) {
      setError('Door-Close Warning Time must be an integer from 1 to 300 seconds.')
      return
    }
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const updated = await api.updateCabinetSettings(terminal.id, {
        takeWarningTimeSeconds: take,
        doorCloseWarningTimeSeconds: door,
        keyReturnCertificationEnabled: certification,
        returnKeyVideoEnabled: returnVideo,
        keyRetrievalVideoEnabled: retrievalVideo,
        expectedRevision: settings.revision,
      })
      setSettings(updated)
      setNotice(`Settings saved for ${terminal.name}.`)
      onSaved?.(updated)
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError('Settings were changed by someone else. Reloading — please reapply.')
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to save cabinet settings')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={onSave}>
      {title ? <h3 style={{ marginTop: 0 }}>{title}</h3> : null}
      <p className="muted" style={{ marginTop: 0 }}>
        These values sync to the terminal on pairing / bootstrap (server wins).
      </p>
      {notice && <div className="notice">{notice}</div>}
      {error && <div className="error-banner">{error}</div>}
      {busy && !settings && <LinearProgress className="table-busy" label="Loading" />}

      <div className="split">
        <div className="field">
          <label>Take Warning Time (seconds, 1–300)</label>
          <input
            type="number"
            min={1}
            max={300}
            value={takeWarning}
            onChange={(e) => setTakeWarning(e.target.value)}
            required
          />
        </div>
        <div className="field">
          <label>Door-Close Warning Time (seconds, 1–300)</label>
          <input
            type="number"
            min={1}
            max={300}
            value={doorClose}
            onChange={(e) => setDoorClose(e.target.value)}
            required
          />
        </div>
      </div>

      <div className="toggle-list" role="group" aria-label="Cabinet toggles">
        <label className="toggle-row">
          <input
            type="checkbox"
            checked={certification}
            onChange={(e) => setCertification(e.target.checked)}
          />
          <span>
            <strong>Key Return Certification</strong>
            <span className="muted"> Require login before completing a key return</span>
          </span>
        </label>
        <label className="toggle-row">
          <input
            type="checkbox"
            checked={returnVideo}
            onChange={(e) => setReturnVideo(e.target.checked)}
          />
          <span>
            <strong>Return key video</strong>
            <span className="muted"> Record video during key return</span>
          </span>
        </label>
        <label className="toggle-row">
          <input
            type="checkbox"
            checked={retrievalVideo}
            onChange={(e) => setRetrievalVideo(e.target.checked)}
          />
          <span>
            <strong>Key retrieval video</strong>
            <span className="muted"> Record video during key take</span>
          </span>
        </label>
      </div>

      <div className="dialog-actions" style={{ marginTop: 12 }}>
        <Button type="submit" icon={Check} loading={busy} disabled={!settings}>
          Save settings
        </Button>
      </div>
    </form>
  )
}
