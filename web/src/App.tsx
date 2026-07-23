import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { ErrorBoundary } from './components/ErrorBoundary'
import { AppShell } from './layout/AppShell'
import { LoginPage } from './pages/LoginPage'

const DashboardPage = lazy(() =>
  import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })),
)
const UnitsPage = lazy(() => import('./pages/UnitsPage').then((m) => ({ default: m.UnitsPage })))
const TerminalsPage = lazy(() =>
  import('./pages/TerminalsPage').then((m) => ({ default: m.TerminalsPage })),
)
const PersonnelPage = lazy(() =>
  import('./pages/PersonnelPage').then((m) => ({ default: m.PersonnelPage })),
)
const KeysPage = lazy(() => import('./pages/KeysPage').then((m) => ({ default: m.KeysPage })))
const PermissionsPage = lazy(() =>
  import('./pages/PermissionsPage').then((m) => ({ default: m.PermissionsPage })),
)
const EventsPage = lazy(() =>
  import('./pages/SimpleResources').then((m) => ({ default: m.EventsPage })),
)
const SchedulesPage = lazy(() =>
  import('./pages/SimpleResources').then((m) => ({ default: m.SchedulesPage })),
)
const MultiAuthPage = lazy(() =>
  import('./pages/MultiAuthPage').then((m) => ({ default: m.MultiAuthPage })),
)
const UserGroupsPage = lazy(() =>
  import('./pages/SimpleResources').then((m) => ({ default: m.UserGroupsPage })),
)
const KeyGroupsPage = lazy(() =>
  import('./pages/SimpleResources').then((m) => ({ default: m.KeyGroupsPage })),
)
const DataSyncPage = lazy(() =>
  import('./pages/DataSyncPage').then((m) => ({ default: m.DataSyncPage })),
)
const KeyRecordsPage = lazy(() =>
  import('./pages/LogsPages').then((m) => ({ default: m.KeyRecordsPage })),
)
const OperationLogsPage = lazy(() =>
  import('./pages/LogsPages').then((m) => ({ default: m.OperationLogsPage })),
)
const AppointmentsPage = lazy(() =>
  import('./pages/AppointmentsPage').then((m) => ({ default: m.AppointmentsPage })),
)
const AppointmentReasonsPage = lazy(() =>
  import('./pages/SimpleResources').then((m) => ({ default: m.AppointmentReasonsPage })),
)
const AppointmentPermissionsPage = lazy(() =>
  import('./pages/AppointmentPermissionsPage').then((m) => ({
    default: m.AppointmentPermissionsPage,
  })),
)
const SystemLogsPage = lazy(() =>
  import('./pages/LogsPages').then((m) => ({ default: m.SystemLogsPage })),
)
const EquipmentLogsPage = lazy(() =>
  import('./pages/LogsPages').then((m) => ({ default: m.EquipmentLogsPage })),
)
const RecycleBinPage = lazy(() =>
  import('./pages/RecycleBinPage').then((m) => ({ default: m.RecycleBinPage })),
)

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { session } = useAuth()
  if (!session) return <Navigate to="/login" replace />
  return children
}

function PageFallback() {
  return <div className="empty-state">Loading…</div>
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
        <Route
          index
          element={
            <ErrorBoundary fallbackTitle="Dashboard failed to load">
              <Suspense fallback={<PageFallback />}>
                <DashboardPage />
              </Suspense>
            </ErrorBoundary>
          }
        />
        <Route
          path="units"
          element={
            <Suspense fallback={<PageFallback />}>
              <UnitsPage />
            </Suspense>
          }
        />
        <Route
          path="terminals"
          element={
            <Suspense fallback={<PageFallback />}>
              <TerminalsPage />
            </Suspense>
          }
        />
        <Route
          path="personnel"
          element={
            <Suspense fallback={<PageFallback />}>
              <PersonnelPage />
            </Suspense>
          }
        />
        <Route
          path="keys"
          element={
            <Suspense fallback={<PageFallback />}>
              <KeysPage />
            </Suspense>
          }
        />
        <Route
          path="permissions"
          element={
            <Suspense fallback={<PageFallback />}>
              <PermissionsPage />
            </Suspense>
          }
        />
        <Route
          path="events"
          element={
            <Suspense fallback={<PageFallback />}>
              <EventsPage />
            </Suspense>
          }
        />
        <Route
          path="schedules"
          element={
            <Suspense fallback={<PageFallback />}>
              <SchedulesPage />
            </Suspense>
          }
        />
        <Route
          path="multi-auth"
          element={
            <Suspense fallback={<PageFallback />}>
              <MultiAuthPage />
            </Suspense>
          }
        />
        <Route
          path="user-groups"
          element={
            <Suspense fallback={<PageFallback />}>
              <UserGroupsPage />
            </Suspense>
          }
        />
        <Route
          path="key-groups"
          element={
            <Suspense fallback={<PageFallback />}>
              <KeyGroupsPage />
            </Suspense>
          }
        />
        <Route
          path="data-sync"
          element={
            <Suspense fallback={<PageFallback />}>
              <DataSyncPage />
            </Suspense>
          }
        />
        <Route
          path="key-records"
          element={
            <Suspense fallback={<PageFallback />}>
              <KeyRecordsPage />
            </Suspense>
          }
        />
        <Route
          path="operation-logs"
          element={
            <Suspense fallback={<PageFallback />}>
              <OperationLogsPage />
            </Suspense>
          }
        />
        <Route
          path="appointments"
          element={
            <Suspense fallback={<PageFallback />}>
              <AppointmentsPage />
            </Suspense>
          }
        />
        <Route
          path="appointment-reasons"
          element={
            <Suspense fallback={<PageFallback />}>
              <AppointmentReasonsPage />
            </Suspense>
          }
        />
        <Route
          path="appointment-permissions"
          element={
            <Suspense fallback={<PageFallback />}>
              <AppointmentPermissionsPage />
            </Suspense>
          }
        />
        <Route
          path="system-logs"
          element={
            <Suspense fallback={<PageFallback />}>
              <SystemLogsPage />
            </Suspense>
          }
        />
        <Route
          path="equipment-logs"
          element={
            <Suspense fallback={<PageFallback />}>
              <EquipmentLogsPage />
            </Suspense>
          }
        />
        <Route
          path="recycle-bin"
          element={
            <Suspense fallback={<PageFallback />}>
              <RecycleBinPage />
            </Suspense>
          }
        />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
