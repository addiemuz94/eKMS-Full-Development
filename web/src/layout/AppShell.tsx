import { useEffect, useMemo, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { NAV_ICONS, type NavIconName } from './NavIcons'

type NavItem = { to: string; label: string; icon: NavIconName; end?: boolean }
type NavGroup = { title: string; icon: NavIconName; items: NavItem[] }

const GROUPS: NavGroup[] = [
  {
    title: 'Home',
    icon: 'groupHome',
    items: [{ to: '/', label: 'Dashboard', icon: 'home', end: true }],
  },
  {
    title: 'Basic Settings',
    icon: 'groupSettings',
    items: [
      { to: '/units', label: 'Unit Settings', icon: 'units' },
      { to: '/terminals', label: 'Terminal Settings', icon: 'terminals' },
      { to: '/personnel', label: 'Personnel Management', icon: 'personnel' },
      { to: '/keys', label: 'Key Settings', icon: 'keys' },
      { to: '/permissions', label: 'Permission Settings', icon: 'permissions' },
      { to: '/events', label: 'Event Setup', icon: 'events' },
      { to: '/schedules', label: 'Schedule Settings', icon: 'schedules' },
      { to: '/multi-auth', label: 'Multi-authentication Rules', icon: 'multiAuth' },
      { to: '/user-groups', label: 'User Groups', icon: 'userGroups' },
      { to: '/key-groups', label: 'Key Groups', icon: 'keyGroups' },
    ],
  },
  {
    title: 'Data Synchronization',
    icon: 'groupSync',
    items: [{ to: '/data-sync', label: 'Data Synchronization', icon: 'sync' }],
  },
  {
    title: 'Report Data',
    icon: 'groupReports',
    items: [
      { to: '/key-records', label: 'Pickup & Return Records', icon: 'keyRecords' },
      { to: '/operation-logs', label: 'Operation Log', icon: 'operationLogs' },
    ],
  },
  {
    title: 'Appointment Authorization',
    icon: 'groupAppointments',
    items: [
      { to: '/appointments', label: 'Appointment Authorization', icon: 'appointments' },
      { to: '/appointment-reasons', label: 'Appointment Reason Settings', icon: 'appointmentReasons' },
      {
        to: '/appointment-permissions',
        label: 'Appointment Permission Settings',
        icon: 'appointmentPermissions',
      },
    ],
  },
  {
    title: 'Logs',
    icon: 'groupLogs',
    items: [
      { to: '/system-logs', label: 'System Operation Log', icon: 'systemLogs' },
      { to: '/equipment-logs', label: 'Equipment Operation Log', icon: 'equipmentLogs' },
    ],
  },
  {
    title: 'Super Admin',
    icon: 'groupAdmin',
    items: [{ to: '/recycle-bin', label: 'Recycle Bin', icon: 'recycleBin' }],
  },
]

function pathInGroup(pathname: string, group: NavGroup) {
  return group.items.some((item) =>
    item.end ? pathname === item.to : pathname === item.to || pathname.startsWith(`${item.to}/`),
  )
}

export function AppShell() {
  const { session, logout } = useAuth()
  const location = useLocation()
  const [navOpen, setNavOpen] = useState(false)
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>(() => {
    const initial: Record<string, boolean> = {}
    for (const group of GROUPS) {
      initial[group.title] = pathInGroup(location.pathname, group) || group.title === 'Home'
    }
    return initial
  })

  const activeGroupTitle = useMemo(
    () => GROUPS.find((group) => pathInGroup(location.pathname, group))?.title ?? 'Home',
    [location.pathname],
  )

  useEffect(() => {
    setNavOpen(false)
  }, [location.pathname])

  useEffect(() => {
    setOpenGroups((current) => ({
      ...current,
      [activeGroupTitle]: true,
    }))
  }, [activeGroupTitle])

  useEffect(() => {
    document.body.style.overflow = navOpen ? 'hidden' : ''
    return () => {
      document.body.style.overflow = ''
    }
  }, [navOpen])

  function toggleGroup(title: string) {
    setOpenGroups((current) => ({
      ...current,
      [title]: !current[title],
    }))
  }

  return (
    <div className={`app-shell${navOpen ? ' nav-open' : ''}`}>
      {navOpen && (
        <button
          className="nav-backdrop"
          type="button"
          aria-label="Close navigation"
          onClick={() => setNavOpen(false)}
        />
      )}

      <aside className="sidebar">
        <div className="brand-block">
          <div className="brand-mark">EK</div>
          <div>
            <h1 className="brand">eKMS</h1>
            <p className="brand-copy">Admin portal</p>
          </div>
        </div>

        {GROUPS.map((group) => {
          const isOpen = Boolean(openGroups[group.title])
          const GroupIcon = NAV_ICONS[group.icon]
          return (
            <section className={`sidebar-group${isOpen ? ' open' : ''}`} key={group.title}>
              <button
                className="sidebar-group-title"
                type="button"
                aria-expanded={isOpen}
                onClick={() => toggleGroup(group.title)}
              >
                <span className="sidebar-group-label">
                  <GroupIcon className="nav-icon" />
                  <span>{group.title}</span>
                </span>
                <span className="sidebar-chevron" aria-hidden="true">
                  {isOpen ? '▾' : '▸'}
                </span>
              </button>
              {isOpen && (
                <div className="nav-list">
                  {group.items.map((item) => {
                    const ItemIcon = NAV_ICONS[item.icon]
                    return (
                      <NavLink
                        key={item.to}
                        to={item.to}
                        end={item.end}
                        className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}
                      >
                        <ItemIcon className="nav-icon" />
                        <span>{item.label}</span>
                      </NavLink>
                    )
                  })}
                </div>
              )}
            </section>
          )
        })}
      </aside>

      <main className="main">
        <div className="topbar">
          <div className="topbar-left">
            <button
              className="btn secondary nav-toggle"
              type="button"
              aria-expanded={navOpen}
              aria-label={navOpen ? 'Close menu' : 'Open menu'}
              onClick={() => setNavOpen((open) => !open)}
            >
              {navOpen ? 'Close' : 'Menu'}
            </button>
            <div className="topbar-eyebrow">Website management portal</div>
          </div>
          <div className="session-pill">
            <span className="session-name">{session?.displayName}</span>
            <button className="btn secondary" type="button" onClick={logout}>
              Sign out
            </button>
          </div>
        </div>
        <div className="content">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
