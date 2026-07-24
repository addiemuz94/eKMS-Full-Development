type Props = {
  size?: number
  className?: string
  label?: string
}

export function CircularProgress({ size = 24, className, label = 'Loading' }: Props) {
  return (
    <span
      className={['md-circular-progress', className].filter(Boolean).join(' ')}
      role="status"
      aria-label={label}
      style={{ width: size, height: size }}
    >
      <svg viewBox="0 0 48 48" width={size} height={size} aria-hidden="true">
        <circle className="md-circular-track" cx="24" cy="24" r="20" fill="none" strokeWidth="4" />
        <circle className="md-circular-indicator" cx="24" cy="24" r="20" fill="none" strokeWidth="4" />
      </svg>
    </span>
  )
}
