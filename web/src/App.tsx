import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { ErrorBoundary } from './components/ErrorBoundary'
import { AppShell } from './layout/AppShell'
import { LoginPage } from './pages/LoginPage'
import { BootstrapPage } from './pages/BootstrapPage'

const DashboardPage = lazy(() =>
  import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })),
)
const RegistrationPage = lazy(() =>
  import('./pages/RegistrationPage').then((m) => ({ default: m.RegistrationPage })),
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
const KeyAccessPage = lazy(() =>
  import('./pages/KeyAccessPage').then((m) => ({ default: m.KeyAccessPage })),
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
  if (session.role === 'GOD_ADMIN') return <Navigate to="/bootstrap" replace />
  return children
}

function RequireGodAdmin({ children }: { children: React.ReactNode }) {
  const { session } = useAuth()
  if (!session) return <Navigate to="/login" replace />
  if (session.role !== 'GOD_ADMIN') return <Navigate to="/" replace />
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
        path="/bootstrap"
        element={
          <RequireGodAdmin>
            <BootstrapPage />
          </RequireGodAdmin>
        }
      />
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
          path="registration"
          element={
            <Suspense fallback={<PageFallback />}>
              <RegistrationPage />
            </Suspense>
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
          path="key-access"
          element={
            <Suspense fallback={<PageFallback />}>
              <KeyAccessPage />
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
