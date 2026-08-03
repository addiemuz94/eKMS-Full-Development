import { useAuth } from '../auth/AuthContext'
import { Button, SegmentedControl } from '../components/ui'
import { useTheme, type ThemeMode } from '../theme/ThemeContext'

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: 'Super Admin',
  REGIONAL_ADMIN: 'Regional Admin',
  TECHNICIAN: 'Technician',
  VENDOR: 'Vendor',
  GOD_ADMIN: 'System account',
}

export function WebsiteSettingsPage() {
  const { session, logout } = useAuth()
  const { mode, setMode } = useTheme()

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Website settings</h1>
          <p className="muted">Appearance and account settings for this browser session. Preferences are stored on this device.</p>
        </div>
      </div>

      <div className="data-panel settings-panel">
        <h2 className="flush-section-title">Appearance</h2>
        <p className="muted" style={{ marginTop: 0, marginBottom: 12 }}>
          Choose light, dark, or match the operating system.
        </p>
        <SegmentedControl<ThemeMode>
          ariaLabel="Theme"
          value={mode}
          onChange={setMode}
          options={[
            { value: 'system', label: 'System' },
            { value: 'light', label: 'Light' },
            { value: 'dark', label: 'Dark' },
          ]}
        />
      </div>

      <div className="data-panel settings-panel" style={{ marginTop: 16 }}>
        <h2 className="flush-section-title">Account</h2>
        <dl className="settings-account">
          <div>
            <dt>Name</dt>
            <dd>{session?.displayName ?? '—'}</dd>
          </div>
          <div>
            <dt>Email</dt>
            <dd>{session?.email ?? '—'}</dd>
          </div>
          <div>
            <dt>Role</dt>
            <dd>{ROLE_LABELS[session?.role ?? ''] ?? session?.role ?? '—'}</dd>
          </div>
        </dl>
        <div style={{ marginTop: 16 }}>
          <Button variant="outlined" onClick={logout}>
            Sign out
          </Button>
        </div>
      </div>
    </section>
  )
}
