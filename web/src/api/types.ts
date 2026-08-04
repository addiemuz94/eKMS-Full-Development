export type SiteDto = {
  id: string
  name: string
  province?: string | null
  city?: string | null
  parentSiteId?: string | null
  address?: string | null
  regionId?: string | null
  revision: number
}

export type RegionDto = {
  id: string
  name: string
  displayOrder: number
  maxKeyAccessDurationMinutes: number
  revision: number
}

export type TerminalDto = {
  id: string
  siteId: string
  name: string
  boxAddress: number
  serialNumber?: string | null
  configuredSlotCount: number
  connectionState?: string
  vendorDeviceId?: string | null
  nodeRows?: number | null
  nodesPerRow?: number | null
  latitude?: number | null
  longitude?: number | null
  /** True after a terminal redeemed a pairing code successfully. */
  paired?: boolean
  revision: number
  /** Present when API returns lifecycle; list endpoints default to ACTIVE only. */
  lifecycle?: { state?: string }
}

export type TerminalRegistrationResponse = {
  terminal: TerminalDto
  pairingCode: string
  pairingCodeExpiresAtEpochMillis: number
}

export type RegeneratePairingCodeResponse = {
  terminalId: string
  code: string
  expiresAtEpochMillis: number
}

export type TerminalCabinetSettingsDto = {
  terminalId: string
  takeWarningTimeSeconds: number
  doorCloseWarningTimeSeconds: number
  keyReturnCertificationEnabled: boolean
  returnKeyVideoEnabled: boolean
  keyRetrievalVideoEnabled: boolean
  revision: number
}

export type UserDto = {
  id: string
  displayName: string
  email: string
  role: string
  assignedSiteIds?: string[]
  accountStatus?: string
  /** External / employee identifier, distinct from id. */
  staffId?: string | null
  revision: number
}

export type CredentialStatusDto = {
  id: string
  userId: string
  credentialKind: string
  enrollmentStatus: string
  terminalId?: string | null
  enrollmentReference?: string | null
  note?: string | null
  revision: number
}

export type KeyDto = {
  id: string
  siteId: string
  displayName: string
  fobEnrollmentReference?: string | null
  revision: number
}

export type KeySlotDto = {
  id: string
  terminalId: string
  nodeAddress: number
  managedKeyId?: string | null
  revision: number
}

export type AuthUserProfile = {
  id: string
  displayName: string
  email: string
  role: string
}

export type LoginResponse = {
  accessToken: string
  refreshToken: string
  expiresAtEpochMillis: number
  profile: AuthUserProfile
  role: string
  permittedSiteIds?: string[]
}

export type ListResponse<T> = { items: T[] }

export type KeyAccessRequestDto = {
  id: string
  requesterUserId: string
  requesterRole: string
  requesterDisplayName?: string | null
  siteId: string
  siteName?: string | null
  cabinetNames?: string[]
  keyIds?: string[]
  requestedAtEpochMillis: number
  requestedDurationMinutes: number
  reason?: string | null
  pickupAtEpochMillis?: number | null
  returnAtEpochMillis?: number | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED'
  approvedByUserId?: string | null
  approvedAtEpochMillis?: number | null
  passkeyExpiresAtEpochMillis?: number | null
}

export type ReportCategory =
  | 'KEY_TAKE'
  | 'KEY_RETURN'
  | 'CABINET_REGISTRATION'
  | 'PERSONNEL_REGISTRATION'

export type ActivityLogRow = {
  id: string
  occurredAtEpochMillis: number
  eventType: string
  category: ReportCategory
  terminalId?: string | null
  siteId?: string | null
  actorUserId?: string | null
  entityType?: string | null
  entityId?: string | null
  detail?: string | null
  siteName?: string | null
  terminalName?: string | null
  actorName?: string | null
}

export type ActivitySummaryResponse = {
  total: number
  byCategory: Partial<Record<ReportCategory, number>>
}

// Checkout-deadline live SSE events — see backend/src/deadlineMonitor.js. The wire payload only
// ever carries these three fields (no title/body text — that's rendered client-side).
export type NotificationStreamEventType = 'CHECKOUT_WARNING_15MIN' | 'CHECKOUT_OVERDUE'

export type NotificationStreamPayload = {
  checkoutId: string
  keyId: string
  dueAtEpochMillis: number
}
