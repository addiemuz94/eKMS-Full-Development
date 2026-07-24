export type SiteDto = {
  id: string
  name: string
  province?: string | null
  city?: string | null
  parentSiteId?: string | null
  address?: string | null
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
  revision: number
}

export type UserDto = {
  id: string
  displayName: string
  email: string
  role: string
  assignedSiteIds?: string[]
  accountStatus?: string
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
