import { api } from '../api/client'
import { ResourcePage, siteName } from '../components/ResourcePage'
import type { SiteDto } from '../api/types'

type EmbedProps = {
  lockedSiteId?: string
  embedded?: boolean
}

export function EventsPage({ lockedSiteId, embedded }: EmbedProps = {}) {
  return (
    <ResourcePage
      title="Event Setup"
      description={
        embedded
          ? 'Event types for this cabinet’s unit — used in schedules and reports.'
          : 'Define operational event types used in schedules and reports.'
      }
      addLabel="Add event"
      lockedSiteId={lockedSiteId}
      embedded={embedded}
      fields={[
        { name: 'siteId', label: 'Unit', type: 'site', required: true },
        { name: 'name', label: 'Event name', type: 'text', required: true },
        { name: 'eventNumber', label: 'Event number / code', type: 'text', required: true },
        { name: 'requirement', label: 'Requirement', type: 'text' },
      ]}
      list={api.listEvents}
      create={api.createEvent}
      update={api.updateEvent}
      remove={api.deleteEvent}
      titleOf={(item) => String(item.name)}
      renderLines={(item, sites) => [
        ...(lockedSiteId ? [] : [`Unit: ${siteName(sites, item.siteId)}`]),
        `Code: ${item.eventNumber}`,
        `Requirement: ${item.requirement || '—'}`,
      ]}
    />
  )
}

export function SchedulesPage({ lockedSiteId, embedded }: EmbedProps = {}) {
  return (
    <ResourcePage
      title="Schedule Settings"
      description={
        embedded
          ? 'Access windows for this cabinet’s unit.'
          : 'Daily, weekly and monthly access windows.'
      }
      addLabel="Add schedule"
      lockedSiteId={lockedSiteId}
      embedded={embedded}
      fields={[
        { name: 'siteId', label: 'Unit', type: 'site', required: true },
        { name: 'name', label: 'Schedule name', type: 'text', required: true },
        {
          name: 'frequency',
          label: 'Frequency',
          type: 'select',
          required: true,
          options: [
            { value: 'DAILY', label: 'Daily' },
            { value: 'WEEKLY', label: 'Weekly' },
            { value: 'MONTHLY', label: 'Monthly' },
          ],
        },
        { name: 'timeWindowLabel', label: 'Time window label', type: 'text', required: true },
      ]}
      list={api.listSchedules}
      create={api.createSchedule}
      update={api.updateSchedule}
      remove={api.deleteSchedule}
      titleOf={(item) => String(item.name)}
      renderLines={(item, sites) => [
        ...(lockedSiteId ? [] : [`Unit: ${siteName(sites, item.siteId)}`]),
        `Frequency: ${item.frequency}`,
        `Window: ${item.timeWindowLabel}`,
      ]}
    />
  )
}

export function UserGroupsPage({ lockedSiteId, embedded }: EmbedProps = {}) {
  return (
    <ResourcePage
      title="User Groups"
      description={
        embedded
          ? 'Personnel groups for this unit (multi-authentication and bulk assignment).'
          : 'Personnel groups used for multi-authentication and bulk assignment.'
      }
      addLabel="Add user group"
      lockedSiteId={lockedSiteId}
      embedded={embedded}
      fields={[
        { name: 'siteId', label: 'Unit', type: 'site', required: true },
        { name: 'name', label: 'Group name', type: 'text', required: true },
        { name: 'code', label: 'Group code', type: 'text', required: true },
      ]}
      list={api.listPersonnelGroups}
      create={api.createPersonnelGroup}
      update={api.updatePersonnelGroup}
      remove={api.deletePersonnelGroup}
      titleOf={(item) => String(item.name)}
      renderLines={(item, sites) => [
        ...(lockedSiteId ? [] : [`Unit: ${siteName(sites, item.siteId)}`]),
        `Code: ${item.code}`,
      ]}
    />
  )
}

export function KeyGroupsPage({ lockedSiteId, embedded }: EmbedProps = {}) {
  return (
    <ResourcePage
      title="Key Groups"
      description={
        embedded
          ? 'Named key sets for this unit’s multi-authentication rules.'
          : 'Named sets of keys for multi-authentication rules.'
      }
      addLabel="Add key group"
      lockedSiteId={lockedSiteId}
      embedded={embedded}
      fields={[
        { name: 'siteId', label: 'Unit', type: 'site', required: true },
        { name: 'name', label: 'Group name', type: 'text', required: true },
        { name: 'code', label: 'Group code', type: 'text', required: true },
      ]}
      list={api.listKeyGroups}
      create={api.createKeyGroup}
      update={api.updateKeyGroup}
      remove={api.deleteKeyGroup}
      titleOf={(item) => String(item.name)}
      renderLines={(item, sites: SiteDto[]) => [
        ...(lockedSiteId ? [] : [`Unit: ${siteName(sites, item.siteId)}`]),
        `Code: ${item.code}`,
      ]}
    />
  )
}
