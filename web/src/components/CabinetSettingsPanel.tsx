import { useEffect, useMemo, useState } from 'react'
import type { SiteDto, TerminalDto } from '../api/types'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { useCapabilities } from '../auth/CapabilitiesContext'
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

const ALL_TABS: { value: SettingsTab; label: string; roles?: string[]; capability?: string }[] = [
  { value: 'cabinet', label: 'Cabinet', capability: 'cabinet.identity' },
  { value: 'unit', label: 'Location', capability: 'cabinet.identity' },
  { value: 'behavior', label: 'Timers & video', capability: 'cabinet.timers' },
  { value: 'personnel', label: 'Assign User', capability: 'cabinet.assign_user' },
  { value: 'keys', label: 'Keys', capability: 'cabinet.keys' },
  { value: 'permissions', label: 'Key Permission', capability: 'cabinet.key_permission' },
  { value: 'key-access', label: 'Key Access', capability: 'cabinet.key_access' },
]

/**
 * Per-cabinet settings hub shown only after Location → Cabinet selection.
 */
export function CabinetSettingsPanel({ terminal, unitName, onUnitSaved, onCabinetSaved }: Props) {
  const { session } = useAuth()
  const { hasCapability } = useCapabilities()
  const role = session?.role
  const visibleTabs = useMemo(
    () =>
      ALL_TABS.filter((tab) => {
        if (tab.roles && !(role != null && tab.roles.includes(role))) return false
        if (tab.capability && role !== 'SUPER_ADMIN' && !hasCapability(tab.capability)) return false
        return true
      }),
    [role, hasCapability],
  )
  const [tab, setTab] = useState<SettingsTab>(visibleTabs[0]?.value ?? 'behavior')
  const [liveUnitName, setLiveUnitName] = useState(unitName)
  const [preferredUserIds, setPreferredUserIds] = useState<string[]>([])
  const [peopleTick, setPeopleTick] = useState(0)
  const siteId = terminal.siteId

  useEffect(() => {
    setLiveUnitName(unitName)
    setTab(visibleTabs[0]?.value ?? 'behavior')
  }, [unitName, terminal.id]) // eslint-disable-line react-hooks/exhaustive-deps -- reset when cabinet changes

  useEffect(() => {
    if (!visibleTabs.some((t) => t.value === tab)) {
      setTab(visibleTabs[0]?.value ?? 'behavior')
    }
  }, [visibleTabs, tab])

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
        {visibleTabs.map((opt) => (
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
