import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { AppShell } from './layout/AppShell'
import { AppointmentPermissionsPage } from './pages/AppointmentPermissionsPage'
import { AppointmentsPage } from './pages/AppointmentsPage'
import { DashboardPage } from './pages/DashboardPage'
import { DataSyncPage } from './pages/DataSyncPage'
import { KeysPage } from './pages/KeysPage'
import { LoginPage } from './pages/LoginPage'
import {
  EquipmentLogsPage,
  KeyRecordsPage,
  OperationLogsPage,
  SystemLogsPage,
} from './pages/LogsPages'
import { MultiAuthPage } from './pages/MultiAuthPage'
import { PermissionsPage } from './pages/PermissionsPage'
import { PersonnelPage } from './pages/PersonnelPage'
import { RecycleBinPage } from './pages/RecycleBinPage'
import {
  AppointmentReasonsPage,
  EventsPage,
  KeyGroupsPage,
  SchedulesPage,
  UserGroupsPage,
} from './pages/SimpleResources'
import { TerminalsPage } from './pages/TerminalsPage'
import { UnitsPage } from './pages/UnitsPage'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { session } = useAuth()
  if (!session) return <Navigate to="/login" replace />
  return children
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="units" element={<UnitsPage />} />
        <Route path="terminals" element={<TerminalsPage />} />
        <Route path="personnel" element={<PersonnelPage />} />
        <Route path="keys" element={<KeysPage />} />
        <Route path="permissions" element={<PermissionsPage />} />
        <Route path="events" element={<EventsPage />} />
        <Route path="schedules" element={<SchedulesPage />} />
        <Route path="multi-auth" element={<MultiAuthPage />} />
        <Route path="user-groups" element={<UserGroupsPage />} />
        <Route path="key-groups" element={<KeyGroupsPage />} />
        <Route path="data-sync" element={<DataSyncPage />} />
        <Route path="key-records" element={<KeyRecordsPage />} />
        <Route path="operation-logs" element={<OperationLogsPage />} />
        <Route path="appointments" element={<AppointmentsPage />} />
        <Route path="appointment-reasons" element={<AppointmentReasonsPage />} />
        <Route path="appointment-permissions" element={<AppointmentPermissionsPage />} />
        <Route path="system-logs" element={<SystemLogsPage />} />
        <Route path="equipment-logs" element={<EquipmentLogsPage />} />
        <Route path="recycle-bin" element={<RecycleBinPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
