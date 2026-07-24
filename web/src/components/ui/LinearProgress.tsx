type Props = {
  value?: number
  indeterminate?: boolean
  className?: string
  label?: string
}

export function LinearProgress({
  value,
  indeterminate = value == null,
  className,
  label = 'Progress',
}: Props) {
  const clamped = value == null ? undefined : Math.max(0, Math.min(100, value))
  return (
    <div
      className={['md-linear-progress', indeterminate ? 'indeterminate' : '', className]
        .filter(Boolean)
        .join(' ')}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={indeterminate ? undefined : clamped}
    >
      <div
        className="md-linear-bar"
        style={indeterminate || clamped == null ? undefined : { width: `${clamped}%` }}
      />
    </div>
  )
}
