import { useState } from 'react'
import type { TerminalDto } from '../api/types'
import { SegmentedControl } from './ui'
import { CabinetSettingsForm } from './CabinetSettingsForm'
import {
  EventsPage,
  KeyGroupsPage,
  SchedulesPage,
  UserGroupsPage,
} from '../pages/SimpleResources'
import { MultiAuthPage } from '../pages/MultiAuthPage'

type SettingsTab =
  | 'behavior'
  | 'events'
  | 'schedules'
  | 'multi-auth'
  | 'user-groups'
  | 'key-groups'

type Props = {
  terminal: TerminalDto
  unitName?: string
}

/**
 * Per-cabinet settings hub: behavioral timers plus unit-scoped Event / Schedule /
 * Multi-auth / User Groups / Key Groups (formerly top-level nav pages).
 */
export function CabinetSettingsPanel({ terminal, unitName }: Props) {
  const [tab, setTab] = useState<SettingsTab>('behavior')
  const siteId = terminal.siteId

  return (
    <div className="cabinet-settings-panel">
      <p className="muted" style={{ marginTop: 0 }}>
        Configure <strong>{terminal.name}</strong>
        {unitName ? (
          <>
            {' '}
            for unit <strong>{unitName}</strong>
          </>
        ) : null}
        . Timers sync to this cabinet; events, schedules, and groups apply to the unit.
      </p>

      <div className="cabinet-settings-tabs">
        <SegmentedControl<SettingsTab>
          ariaLabel="Cabinet settings section"
          value={tab}
          onChange={setTab}
          options={[
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
