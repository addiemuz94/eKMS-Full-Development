type Props = {
  width?: string | number
  height?: string | number
  className?: string
  radius?: string | number
}

export function Skeleton({ width = '100%', height = 16, className, radius }: Props) {
  return (
    <span
      className={['md-skeleton', className].filter(Boolean).join(' ')}
      aria-hidden="true"
      style={{
        width,
        height,
        borderRadius: radius ?? 8,
      }}
    />
  )
}

export function MetricSkeleton() {
  return (
    <div className="metric">
      <Skeleton width="40%" height={12} />
      <div style={{ marginTop: 12 }}>
        <Skeleton width="48%" height={28} />
      </div>
    </div>
  )
}
