import type { ButtonHTMLAttributes, ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'
import { CircularProgress } from './CircularProgress'

type Variant = 'filled' | 'tonal' | 'outlined' | 'danger' | 'link'

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant
  loading?: boolean
  /** Leading glyph — always paired with the text label, never rendered icon-only. */
  icon?: LucideIcon
  children: ReactNode
}

const VARIANT_CLASS: Record<Variant, string> = {
  filled: 'btn',
  tonal: 'btn tonal',
  outlined: 'btn outlined',
  danger: 'btn danger',
  link: 'btn linkish',
}

export function Button({
  variant = 'filled',
  loading = false,
  icon: Icon,
  disabled,
  children,
  className,
  type = 'button',
  ...rest
}: Props) {
  const classes = [VARIANT_CLASS[variant], className].filter(Boolean).join(' ')
  return (
    <button className={classes} type={type} disabled={disabled || loading} {...rest}>
      {loading ? <CircularProgress size={18} /> : Icon ? <Icon size={18} aria-hidden="true" /> : null}
      {children}
    </button>
  )
}
