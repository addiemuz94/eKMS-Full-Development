import { api } from '../api/client'
import { ResourcePage, siteName } from '../components/ResourcePage'
import type { SiteDto } from '../api/types'

export function EventsPage() {
  return (
    <ResourcePage
      title="Event Setup"
      description="Define operational event types used in schedules and reports."
      addLabel="Add event"
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
        `Unit: ${siteName(sites, item.siteId)}`,
        `Code: ${item.eventNumber}`,
        `Requirement: ${item.requirement || '—'}`,
      ]}
    />
  )
}

export function SchedulesPage() {
  return (
    <ResourcePage
      title="Schedule Settings"
      description="Daily, weekly and monthly access windows."
      addLabel="Add schedule"
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
        `Unit: ${siteName(sites, item.siteId)}`,
        `Frequency: ${item.frequency}`,
        `Window: ${item.timeWindowLabel}`,
      ]}
    />
  )
}

export function UserGroupsPage() {
  return (
    <ResourcePage
      title="User Groups"
      description="Personnel groups used for multi-authentication and bulk assignment."
      addLabel="Add user group"
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
      renderLines={(item, sites) => [`Unit: ${siteName(sites, item.siteId)}`, `Code: ${item.code}`]}
    />
  )
}

export function KeyGroupsPage() {
  return (
    <ResourcePage
      title="Key Groups"
      description="Named sets of keys for multi-authentication rules."
      addLabel="Add key group"
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
      renderLines={(item, sites: SiteDto[]) => [`Unit: ${siteName(sites, item.siteId)}`, `Code: ${item.code}`]}
    />
  )
}

export function AppointmentReasonsPage() {
  return (
    <ResourcePage
      title="Appointment Reason Settings"
      description="Reasons operators can pick when requesting temporary key access."
      addLabel="Add reason"
      fields={[
        { name: 'siteId', label: 'Unit', type: 'site', required: true },
        { name: 'name', label: 'Reason name', type: 'text', required: true },
      ]}
      list={api.listAppointmentReasons}
      create={(payload: Record<string, unknown>) => api.createAppointmentReason({ ...payload, active: true })}
      update={(id: string, payload: Record<string, unknown>) =>
        api.updateAppointmentReason(id, { ...payload, active: true })
      }
      remove={api.deleteAppointmentReason}
      titleOf={(item) => String(item.name)}
      renderLines={(item, sites: SiteDto[]) => [
        `Unit: ${siteName(sites, item.siteId)}`,
        `Active: ${item.active === false ? 'No' : 'Yes'}`,
      ]}
    />
  )
}
