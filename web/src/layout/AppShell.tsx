import { useEffect, useState } from 'react'
import { LogOut } from 'lucide-react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { Button } from '../components/ui'
import { NAV_ICONS, type NavIconName } from './NavIcons'

type NavItem = {
  to: string
  label: string
  description: string
  icon: NavIconName
  end?: boolean
}

type NavGroup = { kind: 'group'; title: string; items: NavItem[] }
type NavTopLink = { kind: 'link'; item: NavItem }
type NavEntry = NavTopLink | NavGroup

const NAV: NavEntry[] = [
  {
    kind: 'link',
    item: {
      to: '/',
      label: 'Home',
      description: 'Location map and cabinet status overview',
      icon: 'home',
      end: true,
    },
  },
  {
    kind: 'group',
    title: 'Cabinets',
    items: [
      {
        to: '/registration',
        label: 'Registration',
        description: 'Register a location, cabinet, keys, and setup code',
        icon: 'units',
      },
      {
        to: '/terminals',
        label: 'Cabinet Management',
        description: 'Configure location, cabinet, keys, and access',
        icon: 'terminals',
      },
      {
        to: '/personnel',
        label: 'User Management',
        description: 'Create and manage personnel accounts',
        icon: 'personnel',
        end: true,
      },
    ],
  },
  {
    kind: 'group',
    title: 'Reports',
    items: [
      {
        to: '/activity-report',
        label: 'Activity Report',
        description: 'Key pickup, return, and registration events',
        icon: 'keyRecords',
      },
      {
        to: '/activity-archive',
        label: 'Activity archive',
        description: 'Activity for removed cabinets',
        icon: 'recycleBin',
      },
      {
        to: '/key-records',
        label: 'Pickup & Return',
        description: 'Key pickup and return history',
        icon: 'keyRecords',
      },
      {
        to: '/operation-logs',
        label: 'Operation Log',
        description: 'Cabinet operator action history',
        icon: 'operationLogs',
      },
    ],
  },
  {
    kind: 'group',
    title: 'Logs',
    items: [
      {
        to: '/system-logs',
        label: 'System Log',
        description: 'Portal and system events',
        icon: 'systemLogs',
      },
      {
        to: '/equipment-logs',
        label: 'Equipment Log',
        description: 'Hardware and equipment events',
        icon: 'equipmentLogs',
      },
    ],
  },
  {
    kind: 'group',
    title: 'Admin',
    items: [
      {
        to: '/recycle-bin',
        label: 'Deleted items',
        description: 'Restore items removed in the last 60 days',
        icon: 'recycleBin',
      },
      {
        to: '/flush-data',
        label: 'Erase data',
        description: 'Permanently delete selected data (cannot be undone)',
        icon: 'flushData',
      },
      {
        to: '/settings',
        label: 'Website settings',
        description: 'Appearance preferences and sign out',
        icon: 'settings',
      },
    ],
  },
]

function NavItemLink({ item }: { item: NavItem }) {
  const ItemIcon = NAV_ICONS[item.icon]
  return (
    <NavLink
      to={item.to}
      end={item.end}
      className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}
      title={item.description}
    >
      <ItemIcon className="nav-icon" aria-hidden />
      <span className="nav-link-text">
        <span className="nav-link-label">{item.label}</span>
        <span className="nav-link-desc">{item.description}</span>
      </span>
    </NavLink>
  )
}

export function AppShell() {
  const location = useLocation()
  const { logout } = useAuth()
  const [navOpen, setNavOpen] = useState(false)

  useEffect(() => {
    setNavOpen(false)
  }, [location.pathname])

  useEffect(() => {
    document.body.style.overflow = navOpen ? 'hidden' : ''
    return () => {
      document.body.style.overflow = ''
    }
  }, [navOpen])

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
        <div className="sidebar-brand brand-block">
          <div className="brand-mark">EK</div>
          <div>
            <h1 className="brand">eKMS</h1>
            <p className="brand-copy">Cavotec admin</p>
          </div>
        </div>

        <nav className="sidebar-nav" aria-label="Primary">
          {NAV.map((entry) => {
            if (entry.kind === 'link') {
              return (
                <section className="sidebar-group sidebar-top-link" key={entry.item.to}>
                  <div className="nav-list">
                    <NavItemLink item={entry.item} />
                  </div>
                </section>
              )
            }
            return (
              <section className="sidebar-group open" key={entry.title}>
                <div className="sidebar-group-title sidebar-group-heading" aria-hidden={false}>
                  <span className="sidebar-group-label">{entry.title}</span>
                </div>
                <div className="nav-list">
                  {entry.items.map((item) => (
                    <NavItemLink key={item.to} item={item} />
                  ))}
                </div>
              </section>
            )
          })}
        </nav>

        <div className="sidebar-footer">
          <Button
            variant="outlined"
            className="sidebar-logout"
            icon={LogOut}
            onClick={logout}
          >
            Sign out
          </Button>
        </div>
      </aside>

      <main className="main">
        <div className="topbar topbar-minimal">
          <Button
            variant="tonal"
            className="nav-toggle"
            aria-expanded={navOpen}
            aria-label={navOpen ? 'Close menu' : 'Open menu'}
            onClick={() => setNavOpen((open) => !open)}
          >
            {navOpen ? 'Close' : 'Menu'}
          </Button>
          <Button
            variant="outlined"
            className="topbar-logout"
            icon={LogOut}
            onClick={logout}
          >
            Sign out
          </Button>
        </div>
        <div className="content">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
