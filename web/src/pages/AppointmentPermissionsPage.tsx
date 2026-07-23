import { AppointmentsPage } from './AppointmentsPage'

export function AppointmentPermissionsPage() {
  return (
    <section className="stack">
      <div className="notice">
        Appointment Permission Settings uses the same appointment records. Approve requests on Appointment
        Authorization, or create appointments with exact keys there.
      </div>
      <AppointmentsPage />
    </section>
  )
}
