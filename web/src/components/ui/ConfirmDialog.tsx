import { useCallback, useState } from 'react'
import { Check, X } from 'lucide-react'
import { Button } from './Button'

export type ConfirmOptions = {
  title?: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  danger?: boolean
}

type ConfirmDialogProps = ConfirmOptions & {
  onConfirm: () => void
  onCancel: () => void
}

export function ConfirmDialog({
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  danger = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  return (
    <div className="dialog-backdrop">
      <div className="dialog" role="alertdialog" aria-modal="true">
        <h2>{title ?? (danger ? 'Confirm' : 'Are you sure?')}</h2>
        <p className="dialog-copy">{message}</p>
        <div className="dialog-actions">
          <Button variant="outlined" icon={X} onClick={onCancel}>
            {cancelLabel}
          </Button>
          <Button variant={danger ? 'danger' : 'filled'} icon={Check} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  )
}

type PendingConfirm = {
  options: ConfirmOptions
  resolve: (confirmed: boolean) => void
}

/**
 * Themed drop-in for window.confirm(). Call `confirmAction(options)` from an async
 * handler exactly where `confirm(message)` used to sit — same "if (!(await …)) return"
 * gating shape, but resolves from Confirm/Cancel button clicks instead of a native
 * dialog. Render `dialog` once, anywhere in the component's JSX tree, and it appears
 * only while a confirmation is pending. Never resolves from a backdrop click, matching
 * every other dialog in this app.
 */
export function useConfirm() {
  const [pending, setPending] = useState<PendingConfirm | null>(null)

  const confirmAction = useCallback((options: ConfirmOptions) => {
    return new Promise<boolean>((resolve) => {
      setPending({ options, resolve })
    })
  }, [])

  function settle(confirmed: boolean) {
    pending?.resolve(confirmed)
    setPending(null)
  }

  const dialog = pending ? (
    <ConfirmDialog
      title={pending.options.title}
      message={pending.options.message}
      confirmLabel={pending.options.confirmLabel}
      cancelLabel={pending.options.cancelLabel}
      danger={pending.options.danger}
      onConfirm={() => settle(true)}
      onCancel={() => settle(false)}
    />
  ) : null

  return { confirmAction, dialog }
}
