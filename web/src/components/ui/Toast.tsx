import { useCallback, useRef, useState } from 'react'
import { X } from 'lucide-react'

export type ToastTone = 'info' | 'warning' | 'danger'

export type ToastOptions = {
  title: string
  body?: string
  tone?: ToastTone
}

type ToastItem = ToastOptions & { id: string }

const TOAST_DURATION_MS = 8000

function ToastStack({
  toasts,
  onDismiss,
}: {
  toasts: ToastItem[]
  onDismiss: (id: string) => void
}) {
  if (toasts.length === 0) return null
  return (
    <div className="toast-stack" role="region" aria-label="Notifications">
      {toasts.map((toast) => (
        <div className={`toast toast-${toast.tone ?? 'info'}`} role="status" key={toast.id}>
          <div className="toast-text">
            <p className="toast-title">{toast.title}</p>
            {toast.body && <p className="toast-body">{toast.body}</p>}
          </div>
          <button
            type="button"
            className="toast-close"
            aria-label="Dismiss notification"
            onClick={() => onDismiss(toast.id)}
          >
            <X size={14} aria-hidden="true" />
          </button>
        </div>
      ))}
    </div>
  )
}

/**
 * Minimal transient-popup queue — this app had no toast/snackbar component before (only
 * ConfirmDialog for blocking confirmations), so this is new but follows the same
 * component+hook shape, using the MD3 CSS tokens the rest of the app already uses (see
 * .toast rules in styles.css) rather than an unstyled default.
 */
export function useToastQueue() {
  const [toasts, setToasts] = useState<ToastItem[]>([])
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>())

  const dismiss = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
    const timer = timers.current.get(id)
    if (timer) {
      clearTimeout(timer)
      timers.current.delete(id)
    }
  }, [])

  const push = useCallback(
    (toast: ToastOptions) => {
      const id =
        typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
          ? crypto.randomUUID()
          : `toast-${Date.now()}-${Math.random().toString(16).slice(2)}`
      setToasts((prev) => [...prev, { ...toast, id }])
      timers.current.set(
        id,
        setTimeout(() => dismiss(id), TOAST_DURATION_MS),
      )
    },
    [dismiss],
  )

  const stack = <ToastStack toasts={toasts} onDismiss={dismiss} />
  return { push, stack }
}
