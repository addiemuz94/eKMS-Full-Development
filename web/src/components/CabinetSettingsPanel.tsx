import { useEffect, useMemo, useState } from 'react'
import type { SiteDto, TerminalDto } from '../api/types'
import { api } from '../api/client'
import { CabinetIdentityForm } from './CabinetIdentityForm'
import { CabinetSettingsForm } from './CabinetSettingsForm'
import { UnitSettingsForm } from './UnitSettingsForm'
import { CabinetUnitPeoplePanel } from './CabinetUnitPeoplePanel'
import { AccessGrantsPanel } from './AccessGrantsPanel'
import { KeysPage } from '../pages/KeysPage'
import { KeyAccessPage } from '../pages/KeyAccessPage'

type SettingsTab =
  | 'cabinet'
  | 'unit'
  | 'behavior'
  | 'personnel'
  | 'keys'
  | 'permissions'
  | 'key-access'

type Props = {
  terminal: TerminalDto
  unitName?: string
  onUnitSaved?: (site: SiteDto) => void
  onCabinetSaved?: (terminal: TerminalDto) => void
}

/**
 * Per-cabinet settings hub shown only after Location → Cabinet selection.
 */
export function CabinetSettingsPanel({ terminal, unitName, onUnitSaved, onCabinetSaved }: Props) {
  const [tab, setTab] = useState<SettingsTab>('cabinet')
  const [liveUnitName, setLiveUnitName] = useState(unitName)
  const [preferredUserIds, setPreferredUserIds] = useState<string[]>([])
  const [peopleTick, setPeopleTick] = useState(0)
  const siteId = terminal.siteId

  useEffect(() => {
    setLiveUnitName(unitName)
    setTab('cabinet')
  }, [unitName, terminal.id])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const users = await api.listUsers()
        if (cancelled) return
        setPreferredUserIds(
          users
            .filter((p) => p.role !== 'SUPER_ADMIN' && (p.assignedSiteIds ?? []).includes(siteId))
            .map((p) => p.id),
        )
      } catch {
        if (!cancelled) setPreferredUserIds([])
      }
    })()
    return () => {
      cancelled = true
    }
  }, [siteId, peopleTick])

  const displayUnit = useMemo(() => liveUnitName ?? 'this location', [liveUnitName])

  return (
    <div className="cabinet-settings-panel">
      <p className="muted" style={{ marginTop: 0 }}>
        Settings for <strong>{terminal.name}</strong>
        {liveUnitName ? (
          <>
            {' '}
            at <strong>{liveUnitName}</strong>
          </>
        ) : null}
        . Cabinet identity, location details, and assigned personnel apply here; keys, timers, and
        access apply to this cabinet’s context.
      </p>

      <div className="cabinet-settings-tabs" role="tablist" aria-label="Cabinet settings section">
        {(
          [
            { value: 'cabinet', label: 'Cabinet' },
            { value: 'unit', label: 'Location' },
            { value: 'behavior', label: 'Timers & video' },
            { value: 'personnel', label: 'Assign User' },
            { value: 'keys', label: 'Keys' },
            { value: 'permissions', label: 'Key Permission' },
            { value: 'key-access', label: 'Key Access' },
          ] as const
        ).map((opt) => (
          <button
            key={opt.value}
            type="button"
            role="tab"
            aria-selected={tab === opt.value}
            className={`cabinet-settings-tab${tab === opt.value ? ' is-active' : ''}`}
            onClick={() => setTab(opt.value)}
          >
            {opt.label}
          </button>
        ))}
      </div>

      <div className="cabinet-settings-body">
        {tab === 'cabinet' && (
          <CabinetIdentityForm
            terminal={terminal}
            embedded
            onSaved={(updated) => onCabinetSaved?.(updated)}
          />
        )}
        {tab === 'unit' && (
          <UnitSettingsForm
            siteId={siteId}
            terminal={terminal}
            embedded
            onSaved={(site) => {
              setLiveUnitName(site.name)
              onUnitSaved?.(site)
            }}
            onCabinetSaved={(updated) => onCabinetSaved?.(updated)}
          />
        )}
        {tab === 'behavior' && <CabinetSettingsForm terminal={terminal} />}
        {tab === 'personnel' && (
          <CabinetUnitPeoplePanel
            terminal={terminal}
            unitName={displayUnit}
            onChanged={() => setPeopleTick((n) => n + 1)}
          />
        )}
        {tab === 'keys' && (
          <KeysPage
            lockedSiteId={siteId}
            lockedTerminalId={terminal.id}
            embedded
          />
        )}
        {tab === 'permissions' && (
          <AccessGrantsPanel
            lockedSiteId={siteId}
            embedded
            preferredUserIds={preferredUserIds}
          />
        )}
        {tab === 'key-access' && (
          <KeyAccessPage lockedSiteId={siteId} embedded cabinetName={terminal.name} />
        )}
      </div>
    </div>
  )
}
