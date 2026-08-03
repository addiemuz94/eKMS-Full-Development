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
const TerminalsPage = lazy(() =>
  import('./pages/TerminalsPage').then((m) => ({ default: m.TerminalsPage })),
)
const PersonnelPage = lazy(() =>
  import('./pages/PersonnelPage').then((m) => ({ default: m.PersonnelPage })),
)
const DataSyncPage = lazy(() =>
  import('./pages/DataSyncPage').then((m) => ({ default: m.DataSyncPage })),
)
const ActivityReportPage = lazy(() =>
  import('./pages/LogsPages').then((m) => ({ default: m.ActivityReportPage })),
)
const ActivityArchivePage = lazy(() =>
  import('./pages/LogsPages').then((m) => ({ default: m.ActivityArchivePage })),
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
const FlushDataPage = lazy(() =>
  import('./pages/FlushDataPage').then((m) => ({ default: m.FlushDataPage })),
)
const WebsiteSettingsPage = lazy(() =>
  import('./pages/WebsiteSettingsPage').then((m) => ({ default: m.WebsiteSettingsPage })),
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
        <Route path="units" element={<Navigate to="/terminals" replace />} />
        <Route
          path="terminals"
          element={
            <Suspense fallback={<PageFallback />}>
              <TerminalsPage />
            </Suspense>
          }
        />
        <Route path="previous-cabinets" element={<Navigate to="/activity-archive" replace />} />
        <Route path="personnel/cabinets" element={<Navigate to="/terminals" replace />} />
        <Route
          path="personnel"
          element={
            <Suspense fallback={<PageFallback />}>
              <PersonnelPage />
            </Suspense>
          }
        />
        <Route path="keys" element={<Navigate to="/terminals" replace />} />
        <Route path="permissions" element={<Navigate to="/terminals" replace />} />
        <Route path="key-access" element={<Navigate to="/terminals" replace />} />
        <Route path="events" element={<Navigate to="/terminals" replace />} />
        <Route path="schedules" element={<Navigate to="/terminals" replace />} />
        <Route path="multi-auth" element={<Navigate to="/terminals" replace />} />
        <Route path="user-groups" element={<Navigate to="/terminals" replace />} />
        <Route path="key-groups" element={<Navigate to="/terminals" replace />} />
        <Route
          path="data-sync"
          element={
            <Suspense fallback={<PageFallback />}>
              <DataSyncPage />
            </Suspense>
          }
        />
        <Route
          path="activity-report"
          element={
            <Suspense fallback={<PageFallback />}>
              <ActivityReportPage />
            </Suspense>
          }
        />
        <Route
          path="activity-archive"
          element={
            <Suspense fallback={<PageFallback />}>
              <ActivityArchivePage />
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
        <Route
          path="flush-data"
          element={
            <Suspense fallback={<PageFallback />}>
              <FlushDataPage />
            </Suspense>
          }
        />
        <Route
          path="settings"
          element={
            <Suspense fallback={<PageFallback />}>
              <WebsiteSettingsPage />
            </Suspense>
          }
        />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
