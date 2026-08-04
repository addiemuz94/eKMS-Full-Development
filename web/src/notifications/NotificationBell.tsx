import { useEffect, useRef, useState } from 'react'
import { Bell, X } from 'lucide-react'
import { useAuth } from '../auth/AuthContext'
import { Button } from '../components/ui'
import { useNotifications, type StoredNotification } from './NotificationsContext'

function relativeTime(epochMillis: number): string {
  const diffSeconds = Math.round((epochMillis - Date.now()) / 1000)
  const abs = Math.abs(diffSeconds)
  if (abs < 45) return 'just now'
  const minutes = Math.round(abs / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.round(hours / 24)
  return `${days}d ago`
}

function NotificationRow({ item }: { item: StoredNotification }) {
  return (
    <div className={`notif-item${item.read ? '' : ' unread'}`}>
      <p className="notif-item-title">{item.title}</p>
      <p className="notif-item-body">{item.body}</p>
      <p className="notif-item-time">{relativeTime(item.receivedAtEpochMillis)}</p>
    </div>
  )
}

/** Bell + badge + dropdown panel, mounted next to Sign out in AppShell's topbar. Super Admin only
 * — every other role gets nothing rendered here at all (checked directly, not just via the
 * NotificationsProvider's empty state, so there's no dead/hidden markup for other roles). */
export function NotificationBell() {
  const { session } = useAuth()
  const { notifications, unreadCount, markAllRead, clearAll } = useNotifications()
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function onPointerDown(event: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [open])

  if (session?.role !== 'SUPER_ADMIN') return null

  return (
    <div className="notif-bell-wrap" ref={wrapRef}>
      <Button
        variant="outlined"
        className="notif-bell-btn"
        aria-label={unreadCount > 0 ? `Notifications, ${unreadCount} unread` : 'Notifications'}
        aria-expanded={open}
        onClick={() => {
          setOpen((next) => {
            const willOpen = !next
            if (willOpen) markAllRead()
            return willOpen
          })
        }}
      >
        <Bell size={18} aria-hidden="true" />
        {unreadCount > 0 && (
          <span className="notif-badge">{unreadCount > 9 ? '9+' : unreadCount}</span>
        )}
      </Button>

      {open && (
        <div className="notif-panel" role="dialog" aria-label="Notifications">
          <div className="notif-panel-header">
            <h3>Notifications</h3>
            <button
              type="button"
              className="notif-panel-close"
              aria-label="Close notifications"
              onClick={() => setOpen(false)}
            >
              <X size={16} aria-hidden="true" />
            </button>
          </div>
          <div className="notif-list">
            {notifications.length === 0 ? (
              <p className="notif-empty">No notifications yet.</p>
            ) : (
              notifications.map((item) => <NotificationRow item={item} key={item.id} />)
            )}
          </div>
          {notifications.length > 0 && (
            <div className="notif-panel-footer">
              <Button variant="link" onClick={clearAll}>
                Clear all
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
