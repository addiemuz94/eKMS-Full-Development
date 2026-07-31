import { useEffect, useState } from 'react'
import type { SiteDto, TerminalDto } from '../api/types'
import { SegmentedControl } from './ui'
import { CabinetSettingsForm } from './CabinetSettingsForm'
import { UnitSettingsForm } from './UnitSettingsForm'
import {
  EventsPage,
  KeyGroupsPage,
  SchedulesPage,
  UserGroupsPage,
} from '../pages/SimpleResources'
import { MultiAuthPage } from '../pages/MultiAuthPage'

type SettingsTab =
  | 'unit'
  | 'behavior'
  | 'events'
  | 'schedules'
  | 'multi-auth'
  | 'user-groups'
  | 'key-groups'

type Props = {
  terminal: TerminalDto
  unitName?: string
  onUnitSaved?: (site: SiteDto) => void
}

/**
 * Per-cabinet settings hub: unit details, behavioral timers, plus unit-scoped
 * Event / Schedule / Multi-auth / User Groups / Key Groups (formerly top-level nav).
 */
export function CabinetSettingsPanel({ terminal, unitName, onUnitSaved }: Props) {
  const [tab, setTab] = useState<SettingsTab>('unit')
  const [liveUnitName, setLiveUnitName] = useState(unitName)
  const siteId = terminal.siteId

  useEffect(() => {
    setLiveUnitName(unitName)
  }, [unitName, terminal.id])

  return (
    <div className="cabinet-settings-panel">
      <p className="muted" style={{ marginTop: 0 }}>
        Configure <strong>{terminal.name}</strong>
        {liveUnitName ? (
          <>
            {' '}
            for unit <strong>{liveUnitName}</strong>
          </>
        ) : null}
        . Unit details and events/schedules/groups apply to the unit; timers sync to this cabinet.
      </p>

      <div className="cabinet-settings-tabs">
        <SegmentedControl<SettingsTab>
          ariaLabel="Cabinet settings section"
          value={tab}
          onChange={setTab}
          options={[
            { value: 'unit', label: 'Unit' },
            { value: 'behavior', label: 'Timers & video' },
            { value: 'events', label: 'Events' },
            { value: 'schedules', label: 'Schedules' },
            { value: 'user-groups', label: 'User groups' },
            { value: 'key-groups', label: 'Key groups' },
            { value: 'multi-auth', label: 'Multi-auth' },
          ]}
        />
      </div>

      <div className="cabinet-settings-body">
        {tab === 'unit' && (
          <UnitSettingsForm
            siteId={siteId}
            embedded
            onSaved={(site) => {
              setLiveUnitName(site.name)
              onUnitSaved?.(site)
            }}
          />
        )}
        {tab === 'behavior' && <CabinetSettingsForm terminal={terminal} />}
        {tab === 'events' && <EventsPage lockedSiteId={siteId} embedded />}
        {tab === 'schedules' && <SchedulesPage lockedSiteId={siteId} embedded />}
        {tab === 'user-groups' && <UserGroupsPage lockedSiteId={siteId} embedded />}
        {tab === 'key-groups' && <KeyGroupsPage lockedSiteId={siteId} embedded />}
        {tab === 'multi-auth' && <MultiAuthPage lockedSiteId={siteId} embedded />}
      </div>
    </div>
  )
}
