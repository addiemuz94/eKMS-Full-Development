import { useEffect, useRef, useState } from 'react'
import { AlertTriangle, Bell, BellOff, Clock3, X } from 'lucide-react'
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
  const overdue = item.eventType === 'CHECKOUT_OVERDUE'
  const Icon = overdue ? AlertTriangle : Clock3
  return (
    <article
      className={`notif-item${item.read ? '' : ' unread'}${overdue ? ' notif-item-danger' : ' notif-item-warning'}`}
    >
      <span className="notif-item-icon" aria-hidden="true">
        <Icon size={16} />
      </span>
      <div className="notif-item-copy">
        <p className="notif-item-title">{item.title}</p>
        <p className="notif-item-body">{item.body}</p>
        <p className="notif-item-time">{relativeTime(item.receivedAtEpochMillis)}</p>
      </div>
    </article>
  )
}

/** Top-right bell + dropdown. Super Admin and Regional Admin only — other roles render nothing. */
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
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  const role = session?.role
  if (role !== 'SUPER_ADMIN' && role !== 'REGIONAL_ADMIN') return null

  return (
    <div className="notif-bell-wrap" ref={wrapRef}>
      <button
        type="button"
        className={`notif-bell-btn${unreadCount > 0 ? ' has-unread' : ''}${open ? ' is-open' : ''}`}
        aria-label={unreadCount > 0 ? `Notifications, ${unreadCount} unread` : 'Notifications'}
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={() => {
          setOpen((next) => {
            const willOpen = !next
            if (willOpen) markAllRead()
            return willOpen
          })
        }}
      >
        <Bell size={18} strokeWidth={2} aria-hidden="true" />
        {unreadCount > 0 && (
          <span className="notif-badge">{unreadCount > 9 ? '9+' : unreadCount}</span>
        )}
      </button>

      {open && (
        <div className="notif-panel" role="dialog" aria-label="Notifications">
          <div className="notif-panel-header">
            <div className="notif-panel-heading">
              <h3>Notifications</h3>
              <p className="notif-panel-sub">
                {notifications.length === 0
                  ? 'Checkout deadline alerts'
                  : unreadCount > 0
                    ? `${unreadCount} unread`
                    : `${notifications.length} recent`}
              </p>
            </div>
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
              <div className="notif-empty">
                <BellOff size={28} strokeWidth={1.5} aria-hidden="true" />
                <p className="notif-empty-title">You’re all caught up</p>
                <p className="notif-empty-body">Key return warnings will show up here.</p>
              </div>
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
