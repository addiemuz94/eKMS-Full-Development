import type { ReactNode } from 'react'

type IconProps = { className?: string }

function Svg({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <svg
      className={className}
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  )
}

export function IconHome({ className }: IconProps) {
  return (
    <Svg className={className}>
      <path d="M3 10.5 12 3l9 7.5" />
      <path d="M5 9.5V21h14V9.5" />
    </Svg>
  )
}

export function IconBuilding({ className }: IconProps) {
  return (
    <Svg className={className}>
      <path d="M4 21V4h10v17" />
      <path d="M14 10h6v11" />
      <path d="M8 8h2M8 12h2M8 16h2M17 14h1M17 17h1" />
    </Svg>
  )
}

export function IconTerminal({ className }: IconProps) {
  return (
    <Svg className={className}>
      <rect x="3" y="4" width="18" height="14" rx="2" />
      <path d="M8 21h8M12 18v3" />
    </Svg>
  )
}

export function IconUsers({ className }: IconProps) {
  return (
    <Svg className={className}>
      <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="3" />
      <path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a3 3 0 0 1 0 5.74" />
    </Svg>
  )
}

export function IconKey({ className }: IconProps) {
  return (
    <Svg className={className}>
      <circle cx="8" cy="15" r="4" />
      <path d="M11.5 12.5 21 3M17 3h4v4" />
    </Svg>
  )
}

export function IconShield({ className }: IconProps) {
  return (
    <Svg className={className}>
      <path d="M12 3 4 6v6c0 5 3.5 8.5 8 9 4.5-.5 8-4 8-9V6l-8-3Z" />
    </Svg>
  )
}

export function IconCalendar({ className }: IconProps) {
  return (
    <Svg className={className}>
      <rect x="3" y="5" width="18" height="16" rx="2" />
      <path d="M16 3v4M8 3v4M3 11h18" />
    </Svg>
  )
}

export function IconClock({ className }: IconProps) {
  return (
    <Svg className={className}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </Svg>
  )
}

export function IconLock({ className }: IconProps) {
  return (
    <Svg className={className}>
      <rect x="5" y="11" width="14" height="10" rx="2" />
      <path d="M8 11V8a4 4 0 0 1 8 0v3" />
    </Svg>
  )
}

export function IconGroup({ className }: IconProps) {
  return (
    <Svg className={className}>
      <circle cx="9" cy="8" r="3" />
      <circle cx="17" cy="9" r="2.5" />
      <path d="M3 19a6 6 0 0 1 12 0M14 19a5 5 0 0 1 7 0" />
    </Svg>
  )
}

export function IconLayers({ className }: IconProps) {
  return (
    <Svg className={className}>
      <path d="m12 3 9 5-9 5-9-5 9-5Z" />
      <path d="m3 12 9 5 9-5M3 16l9 5 9-5" />
    </Svg>
  )
}

export function IconSync({ className }: IconProps) {
  return (
    <Svg className={className}>
      <path d="M21 12a9 9 0 0 1-15.5 6.3L3 21" />
      <path d="M3 12a9 9 0 0 1 15.5-6.3L21 3" />
      <path d="M3 21v-5h5M21 3v5h-5" />
    </Svg>
  )
}

export function IconClipboard({ className }: IconProps) {
  return (
    <Svg className={className}>
      <rect x="6" y="5" width="12" height="16" rx="2" />
      <path d="M9 5V4h6v1M9 11h6M9 15h4" />
    </Svg>
  )
}

export function IconList({ className }: IconProps) {
  return (
    <Svg className={className}>
      <path d="M8 7h13M8 12h13M8 17h13M3 7h.01M3 12h.01M3 17h.01" />
    </Svg>
  )
}

export function IconFile({ className }: IconProps) {
  return (
    <Svg className={className}>
      <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5Z" />
      <path d="M14 3v5h5M9 13h6M9 17h4" />
    </Svg>
  )
}

export function IconTrash({ className }: IconProps) {
  return (
    <Svg className={className}>
      <path d="M4 7h16M9 7V5h6v2M8 7l1 13h6l1-13" />
    </Svg>
  )
}

export function IconFolder({ className }: IconProps) {
  return (
    <Svg className={className}>
      <path d="M3 8a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8Z" />
    </Svg>
  )
}

export function IconReport({ className }: IconProps) {
  return (
    <Svg className={className}>
      <path d="M4 19V5M4 19h16" />
      <path d="M8 16v-5M12 16V8M16 16v-3" />
    </Svg>
  )
}

export function IconAppointments({ className }: IconProps) {
  return (
    <Svg className={className}>
      <rect x="3" y="5" width="18" height="16" rx="2" />
      <path d="M16 3v4M8 3v4M3 11h18M10 16l1.5 1.5L15 14" />
    </Svg>
  )
}

export function IconLogs({ className }: IconProps) {
  return (
    <Svg className={className}>
      <path d="M5 4h14v16H5z" />
      <path d="M8 8h8M8 12h8M8 16h5" />
    </Svg>
  )
}

export function IconAdmin({ className }: IconProps) {
  return (
    <Svg className={className}>
      <circle cx="12" cy="8" r="3.5" />
      <path d="M5 20a7 7 0 0 1 14 0" />
    </Svg>
  )
}

export const NAV_ICONS = {
  home: IconHome,
  units: IconBuilding,
  terminals: IconTerminal,
  personnel: IconUsers,
  keys: IconKey,
  permissions: IconShield,
  events: IconCalendar,
  schedules: IconClock,
  multiAuth: IconLock,
  userGroups: IconGroup,
  keyGroups: IconLayers,
  sync: IconSync,
  keyRecords: IconClipboard,
  operationLogs: IconList,
  appointments: IconAppointments,
  appointmentReasons: IconFile,
  appointmentPermissions: IconShield,
  systemLogs: IconLogs,
  equipmentLogs: IconTerminal,
  recycleBin: IconTrash,
  groupHome: IconHome,
  groupSettings: IconFolder,
  groupSync: IconSync,
  groupReports: IconReport,
  groupAppointments: IconCalendar,
  groupLogs: IconLogs,
  groupAdmin: IconAdmin,
} as const

export type NavIconName = keyof typeof NAV_ICONS
