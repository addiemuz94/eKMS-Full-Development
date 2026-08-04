import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { api } from '../api/client'
import type { NotificationStreamEventType, NotificationStreamPayload } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { useToastQueue } from '../components/ui'

const STORAGE_KEY = 'ekms_web_notifications'
const MAX_STORED = 100
const BASE_RECONNECT_DELAY_MS = 3000
const MAX_RECONNECT_DELAY_MS = 30_000

export type StoredNotification = {
  id: string
  eventType: NotificationStreamEventType
  title: string
  body: string
  receivedAtEpochMillis: number
  read: boolean
  payload: NotificationStreamPayload
}

function randomId(prefix: string) {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function loadStored(): StoredNotification[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as StoredNotification[]) : []
  } catch {
    return []
  }
}

function saveStored(items: StoredNotification[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items.slice(0, MAX_STORED)))
  } catch {
    /* localStorage unavailable (private mode, etc.) — list still works for this session */
  }
}

/**
 * The SSE payload only carries checkoutId/keyId/dueAtEpochMillis (see
 * backend/src/deadlineMonitor.js — title/body text is only ever built for the FCM push, not
 * broadcast to the portal), so the human-readable message is synthesized here instead.
 */
function describeEvent(
  eventType: NotificationStreamEventType,
  payload: NotificationStreamPayload,
): { title: string; body: string } {
  const dueLabel = Number.isFinite(payload.dueAtEpochMillis)
    ? new Date(payload.dueAtEpochMillis).toLocaleString(undefined, {
        dateStyle: 'medium',
        timeStyle: 'short',
      })
    : 'an unknown time'
  const checkoutLabel = payload.checkoutId ? payload.checkoutId.slice(0, 8) : 'unknown'
  if (eventType === 'CHECKOUT_OVERDUE') {
    return {
      title: 'Key return overdue',
      body: `Checkout ${checkoutLabel} was due back ${dueLabel}.`,
    }
  }
  return {
    title: 'Key return due soon',
    body: `Checkout ${checkoutLabel} is due back ${dueLabel}.`,
  }
}

type NotificationsState = {
  notifications: StoredNotification[]
  unreadCount: number
  markAllRead: () => void
  clearAll: () => void
}

const EMPTY_STATE: NotificationsState = {
  notifications: [],
  unreadCount: 0,
  markAllRead: () => {},
  clearAll: () => {},
}

const NotificationsContext = createContext<NotificationsState | null>(null)

/**
 * Live checkout-deadline notifications for Super Admin only — connects an SSE stream after
 * Super Admin login, disconnects on logout/role change. Mirrors ThemeProvider/AuthContext's
 * provider-hook shape. Backend contract: POST /v1/notifications/stream/ticket (Bearer-authed,
 * mints a single-use 30s ticket) then GET /v1/notifications/stream?ticket=... (ticket-authed —
 * EventSource cannot set an Authorization header). See notificationsStream.js/notifications.js.
 */
export function NotificationsProvider({ children }: { children: ReactNode }) {
  const { session } = useAuth()
  const isSuperAdmin = session?.role === 'SUPER_ADMIN'
  const [notifications, setNotifications] = useState<StoredNotification[]>([])
  const hydrated = useRef(false)
  const { push: pushToast, stack: toastStack } = useToastQueue()

  // Hydrate from localStorage only once, only for a Super Admin session — never for any other
  // role, so nothing from a prior Super Admin session on this browser is exposed elsewhere.
  useEffect(() => {
    if (!isSuperAdmin || hydrated.current) return
    hydrated.current = true
    setNotifications(loadStored())
  }, [isSuperAdmin])

  useEffect(() => {
    if (!isSuperAdmin) return
    saveStored(notifications)
  }, [isSuperAdmin, notifications])

  const handleEvent = useCallback(
    (eventType: NotificationStreamEventType, payload: NotificationStreamPayload) => {
      const { title, body } = describeEvent(eventType, payload)
      const entry: StoredNotification = {
        id: randomId('notif'),
        eventType,
        title,
        body,
        receivedAtEpochMillis: Date.now(),
        read: false,
        payload,
      }
      setNotifications((prev) => [entry, ...prev].slice(0, MAX_STORED))
      pushToast({ title, body, tone: eventType === 'CHECKOUT_OVERDUE' ? 'danger' : 'warning' })
    },
    [pushToast],
  )

  useEffect(() => {
    if (!isSuperAdmin) return

    let cancelled = false
    let es: EventSource | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null
    let backoffMs = BASE_RECONNECT_DELAY_MS

    function scheduleReconnect() {
      if (cancelled) return
      if (reconnectTimer) clearTimeout(reconnectTimer)
      reconnectTimer = setTimeout(() => {
        backoffMs = Math.min(backoffMs * 2, MAX_RECONNECT_DELAY_MS)
        connect()
      }, backoffMs)
    }

    function onEvent(eventType: NotificationStreamEventType) {
      return (event: MessageEvent<string>) => {
        try {
          const payload = JSON.parse(event.data) as NotificationStreamPayload
          handleEvent(eventType, payload)
        } catch {
          // Malformed event payload — drop it rather than crash the stream handler.
        }
      }
    }

    async function connect() {
      if (cancelled) return
      try {
        const { ticket } = await api.mintNotificationStreamTicket()
        if (cancelled) return
        const stream = new EventSource(
          `/v1/notifications/stream?ticket=${encodeURIComponent(ticket)}`,
        )
        es = stream
        // Named SSE events only — EventSource has no catch-all listener for custom `event:`
        // names (onmessage only fires for the unnamed default "message" event), so a new
        // eventType added server-side needs a matching addEventListener call here too.
        stream.addEventListener('CHECKOUT_WARNING_15MIN', onEvent('CHECKOUT_WARNING_15MIN'))
        stream.addEventListener('CHECKOUT_OVERDUE', onEvent('CHECKOUT_OVERDUE'))
        stream.onopen = () => {
          backoffMs = BASE_RECONNECT_DELAY_MS
        }
        stream.onerror = () => {
          // The ticket is single-use and already burned by this point — EventSource's native
          // auto-reconnect would retry this exact (now-dead) URL forever and never succeed, so
          // close it ourselves and reconnect through connect() (which mints a fresh ticket)
          // instead, with capped exponential backoff so a genuinely-down server isn't hammered.
          stream.close()
          if (es === stream) es = null
          scheduleReconnect()
        }
      } catch {
        // Ticket mint failed (network down, server unreachable, etc.) — same backoff-and-retry.
        scheduleReconnect()
      }
    }

    connect()

    return () => {
      cancelled = true
      if (reconnectTimer) clearTimeout(reconnectTimer)
      es?.close()
    }
  }, [isSuperAdmin, handleEvent])

  const markAllRead = useCallback(() => {
    setNotifications((prev) =>
      prev.every((n) => n.read) ? prev : prev.map((n) => ({ ...n, read: true })),
    )
  }, [])

  const clearAll = useCallback(() => {
    setNotifications([])
  }, [])

  const unreadCount = useMemo(() => notifications.filter((n) => !n.read).length, [notifications])

  const value = useMemo(
    () => ({ notifications, unreadCount, markAllRead, clearAll }),
    [notifications, unreadCount, markAllRead, clearAll],
  )

  return (
    <NotificationsContext.Provider value={isSuperAdmin ? value : EMPTY_STATE}>
      {children}
      {isSuperAdmin && toastStack}
    </NotificationsContext.Provider>
  )
}

export function useNotifications() {
  const ctx = useContext(NotificationsContext)
  if (!ctx) throw new Error('useNotifications outside NotificationsProvider')
  return ctx
}
