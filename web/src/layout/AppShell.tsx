import { useEffect, useState } from 'react'
import { LogOut } from 'lucide-react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useCapabilities } from '../auth/CapabilitiesContext'
import { Button } from '../components/ui'
import { NotificationBell } from '../notifications/NotificationBell'
import { NAV_ICONS, type NavIconName } from './NavIcons'

type NavItem = {
  to: string
  label: string
  description: string
  icon: NavIconName
  end?: boolean
  /** When set, only these roles see the item. Omitted = all authenticated roles. */
  roles?: string[]
  /** When set, non-SA users also need this capability enabled (role matrix). */
  capability?: string
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
        capability: 'portal.registration',
      },
      {
        to: '/terminals',
        label: 'Cabinet Management',
        description: 'Configure location, cabinet, keys, and access',
        icon: 'terminals',
        capability: 'portal.cabinet_management',
      },
      {
        to: '/key-access',
        label: 'Key Access',
        description: 'Approve exception-access requests and manage PINs',
        icon: 'keyAccess',
        capability: 'cabinet.key_access',
      },
      {
        to: '/personnel',
        label: 'User Management',
        description: 'Create and manage personnel accounts',
        icon: 'personnel',
        end: true,
        capability: 'portal.user_management',
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
        capability: 'portal.activity_report',
      },
      {
        to: '/activity-archive',
        label: 'Activity archive',
        description: 'Activity for removed cabinets',
        icon: 'recycleBin',
        roles: ['SUPER_ADMIN'],
      },
      {
        to: '/key-records',
        label: 'Pickup & Return',
        description: 'Key pickup and return history',
        icon: 'keyRecords',
        capability: 'portal.logs',
      },
      {
        to: '/operation-logs',
        label: 'Operation Log',
        description: 'Cabinet operator action history',
        icon: 'operationLogs',
        capability: 'portal.logs',
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
        capability: 'portal.logs',
      },
      {
        to: '/equipment-logs',
        label: 'Equipment Log',
        description: 'Hardware and equipment events',
        icon: 'equipmentLogs',
        capability: 'portal.logs',
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
        capability: 'portal.deleted_items',
      },
      {
        to: '/flush-data',
        label: 'Erase data',
        description: 'Permanently delete selected data (cannot be undone)',
        icon: 'flushData',
        capability: 'portal.erase_data',
      },
      {
        to: '/role-permissions',
        label: 'Role permissions',
        description: 'Enable or disable what each role can do',
        icon: 'permissions',
        roles: ['SUPER_ADMIN'],
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

function itemVisibleToRole(
  item: NavItem,
  role: string | undefined,
  hasCapability: (key: string) => boolean,
) {
  if (item.roles && item.roles.length > 0) {
    if (!role || !item.roles.includes(role)) return false
  }
  if (item.capability && role !== 'SUPER_ADMIN') {
    if (!hasCapability(item.capability)) return false
  }
  return true
}

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
  const { logout, session } = useAuth()
  const { hasCapability } = useCapabilities()
  const [navOpen, setNavOpen] = useState(false)
  const role = session?.role

  useEffect(() => {
    setNavOpen(false)
  }, [location.pathname])

  useEffect(() => {
    document.body.style.overflow = navOpen ? 'hidden' : ''
    return () => {
      document.body.style.overflow = ''
    }
  }, [navOpen])

  const visibleNav = NAV.map((entry) => {
    if (entry.kind === 'link') {
      return itemVisibleToRole(entry.item, role, hasCapability) ? entry : null
    }
    const items = entry.items.filter((item) => itemVisibleToRole(item, role, hasCapability))
    if (items.length === 0) return null
    return { ...entry, items }
  }).filter((entry): entry is NavEntry => entry != null)

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
          {visibleNav.map((entry) => {
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
          <div className="topbar-actions">
            <NotificationBell />
          </div>
        </div>
        <div className="content">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
