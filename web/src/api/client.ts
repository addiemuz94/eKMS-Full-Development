import type {
  ActivityLogRow,
  ActivitySummaryResponse,
  CredentialStatusDto,
  KeyAccessRequestDto,
  KeyDto,
  KeySlotDto,
  ListResponse,
  LoginResponse,
  RegeneratePairingCodeResponse,
  RegionDto,
  SiteDto,
  TerminalCabinetSettingsDto,
  TerminalDto,
  TerminalRegistrationResponse,
  UserDto,
} from './types'

const SESSION_KEY = 'ekms_web_session'

export type Session = {
  accessToken: string
  refreshToken: string
  userId?: string
  displayName: string
  email: string
  role: string
}

export function loadSession(): Session | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY)
    if (!raw) return null
    return JSON.parse(raw) as Session
  } catch {
    return null
  }
}

export function saveSession(session: Session | null) {
  if (!session) localStorage.removeItem(SESSION_KEY)
  else localStorage.setItem(SESSION_KEY, JSON.stringify(session))
}

let accessToken: string | null = loadSession()?.accessToken ?? null

export function setAccessToken(token: string | null) {
  accessToken = token
}

type SessionExpiredHandler = () => void
type SessionRefreshedHandler = (session: Session) => void

let onSessionExpired: SessionExpiredHandler | null = null
let onSessionRefreshed: SessionRefreshedHandler | null = null
let sessionExpiredHandled = false
let refreshInFlight: Promise<boolean> | null = null

/** Register from AuthProvider — clear React session + navigate to login. */
export function setOnSessionExpired(handler: SessionExpiredHandler | null) {
  onSessionExpired = handler
}

/** Keep AuthContext tokens in sync after a successful refresh. */
export function setOnSessionRefreshed(handler: SessionRefreshedHandler | null) {
  onSessionRefreshed = handler
}

function clearSessionExpiredFlag() {
  sessionExpiredHandled = false
}

function notifySessionExpired() {
  if (sessionExpiredHandled) return
  sessionExpiredHandled = true
  accessToken = null
  saveSession(null)
  onSessionExpired?.()
}

async function refreshAccessToken(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight
  refreshInFlight = (async () => {
    const session = loadSession()
    if (!session?.refreshToken) return false
    try {
      const res = await fetch('/v1/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: session.refreshToken }),
      })
      if (!res.ok) return false
      const json = (await res.json()) as {
        accessToken?: string
        refreshToken?: string
      }
      if (!json.accessToken) return false
      const next: Session = {
        ...session,
        accessToken: json.accessToken,
        refreshToken: json.refreshToken || session.refreshToken,
      }
      accessToken = next.accessToken
      saveSession(next)
      onSessionRefreshed?.(next)
      return true
    } catch {
      return false
    }
  })().finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

/** On 401 for an authenticated call: refresh once, else expire the session. */
async function recoverFromUnauthorized(alreadyRetried: boolean): Promise<boolean> {
  if (alreadyRetried) {
    notifySessionExpired()
    return false
  }
  const refreshed = await refreshAccessToken()
  if (refreshed) return true
  notifySessionExpired()
  return false
}

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  opts?: { auth?: boolean; idempotent?: boolean; _retried?: boolean },
): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  if (opts?.auth !== false) {
    if (!accessToken) {
      const existing = loadSession()
      if (existing?.accessToken) {
        accessToken = existing.accessToken
      } else {
        if (existing) notifySessionExpired()
        throw new ApiError(401, 'Sign in required')
      }
    }
    headers.Authorization = `Bearer ${accessToken}`
  }
  if (opts?.idempotent) {
    headers['Idempotency-Key'] =
      typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : `idem-${Date.now()}-${Math.random().toString(16).slice(2)}`
  }

  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  const text = await res.text()
  let json: unknown = null
  if (text) {
    try {
      json = JSON.parse(text)
    } catch {
      json = null
    }
  }

  if (!res.ok) {
    if (res.status === 401 && opts?.auth !== false) {
      const recovered = await recoverFromUnauthorized(Boolean(opts?._retried))
      if (recovered) {
        return request<T>(method, path, body, { ...opts, _retried: true })
      }
      throw new ApiError(401, 'Your session has expired. Sign in to continue.')
    }
    const msg =
      (json as { message?: string; error?: string } | null)?.message ||
      (json as { error?: string } | null)?.error ||
      friendlyHttpError(res.status, text, res.headers.get('content-type'))
    throw new ApiError(res.status, msg)
  }

  return (json ?? {}) as T
}

/** Avoid dumping Caddy/SPA HTML into the red error bar when an API route is missing. */
function friendlyHttpError(
  status: number,
  body: string,
  contentType: string | null,
  fallback?: string,
): string {
  const looksHtml =
    (contentType ?? '').includes('text/html') ||
    /^\s*<(!DOCTYPE|html)\b/i.test(body)
  if (looksHtml) {
    if (status === 404) {
      return 'Report export is not available on the server. Contact an administrator to redeploy the API.'
    }
    if (status >= 500) {
      return 'Server error while generating the PDF. Retry the export, or contact an administrator to review API logs.'
    }
    return fallback || `Request failed (HTTP ${status}). The server returned a web page instead of API data.`
  }
  const trimmed = body.trim()
  if (trimmed.length > 280) return trimmed.slice(0, 280) + '…'
  return trimmed || fallback || `HTTP ${status}`
}

function listPath(path: string) {
  return () => request<ListResponse<Record<string, unknown>>>('GET', path).then((r) => r.items ?? [])
}

function createPath(path: string) {
  return (payload: Record<string, unknown>) =>
    request<Record<string, unknown>>('POST', path, payload, { idempotent: true })
}

function deletePath(path: string) {
  return (id: string) =>
    request<Record<string, unknown>>('DELETE', `${path}/${id}`, undefined, { idempotent: true })
}

function updatePath(path: string) {
  return (id: string, payload: Record<string, unknown>) =>
    request<Record<string, unknown>>('PATCH', `${path}/${id}`, payload, { idempotent: true })
}

export type RecycleBinEntry = {
  id: string
  recordType: string
  recordId: string
  recordLabel: string
  deletedByUserId: string
  deletedAtEpochMillis: number
  expiresAtEpochMillis: number
  restorePayloadVersion: number
}

export type FlushScope =
  | 'TERMINALS'
  | 'KEYS'
  | 'USERS'
  | 'SITES'
  | 'ACCESS_GRANTS'
  | 'ALL'

export type FlushPreviewResponse = {
  scope: FlushScope
  counts: Record<string, number>
  previewToken: string
  confirmTokenRequired: string
  note?: string
}

export type FlushResultResponse = {
  ok: boolean
  scope: FlushScope
  deleted: Record<string, number>
  serverTimeEpochMillis: number
}

export type AuditEvent = {
  id?: string
  eventType?: string
  actorUserId?: string | null
  siteId?: string | null
  terminalId?: string | null
  entityType?: string | null
  entityId?: string | null
  detail?: string | null
  createdAtEpochMillis?: number
}

export const api = {
  async login(identifier: string, password: string): Promise<LoginResponse> {
    const login = await request<LoginResponse>(
      'POST',
      '/v1/auth/login',
      {
        identifier: identifier.trim(),
        password,
        clientType: 'WEB',
        deviceId: 'web-react',
      },
      { auth: false },
    )
    clearSessionExpiredFlag()
    setAccessToken(login.accessToken)
    saveSession({
      accessToken: login.accessToken,
      refreshToken: login.refreshToken,
      userId: login.profile.id,
      displayName: login.profile.displayName,
      email: login.profile.email,
      role: login.profile.role || login.role || '',
    })
    return login
  },

  getBootstrapStatus: () =>
    request<{ hasSuperAdmin: boolean; superAdminCount: number }>('GET', '/v1/admin/bootstrap-status'),

  logout() {
    setAccessToken(null)
    saveSession(null)
  },

  listSites: () =>
    request<ListResponse<SiteDto>>('GET', '/v1/admin/sites').then((r) => r.items),

  listRegions: () =>
    request<ListResponse<RegionDto>>('GET', '/v1/admin/regions').then((r) => r.items),
  createSite: (payload: Record<string, unknown>) =>
    request<SiteDto>('POST', '/v1/admin/sites', payload, { idempotent: true }),
  updateSite: (id: string, payload: Record<string, unknown>) =>
    request<SiteDto>('PATCH', `/v1/admin/sites/${id}`, payload, { idempotent: true }),
  deleteSite: (id: string) =>
    request<SiteDto>('DELETE', `/v1/admin/sites/${id}`, undefined, { idempotent: true }),

  listTerminals: () =>
    request<ListResponse<TerminalDto>>('GET', '/v1/admin/terminals').then((r) =>
      (r.items ?? []).filter((t) => !t.lifecycle?.state || t.lifecycle.state === 'ACTIVE'),
    ),
  createTerminal: (payload: Record<string, unknown>) =>
    request<TerminalRegistrationResponse>('POST', '/v1/admin/terminals', payload, {
      idempotent: true,
    }),
  updateTerminal: (id: string, payload: Record<string, unknown>) =>
    request<TerminalDto>('PATCH', `/v1/admin/terminals/${id}`, payload, { idempotent: true }),
  deleteTerminal: (id: string, opts?: { cascade?: boolean }) =>
    request<TerminalDto & { cascade?: { slotCount: number; keyCount: number; grantCount: number } }>(
      'DELETE',
      `/v1/admin/terminals/${id}`,
      opts?.cascade ? { cascade: true } : undefined,
      { idempotent: true },
    ),
  regenerateTerminalPairingCode: (id: string) =>
    request<RegeneratePairingCodeResponse>(
      'POST',
      `/v1/admin/terminals/${id}/pairing-code`,
      {},
      { idempotent: true },
    ),
  getCabinetSettings: (terminalId: string) =>
    request<TerminalCabinetSettingsDto>('GET', `/v1/admin/terminals/${terminalId}/cabinet-settings`),
  updateCabinetSettings: (terminalId: string, payload: Record<string, unknown>) =>
    request<TerminalCabinetSettingsDto>(
      'PATCH',
      `/v1/admin/terminals/${terminalId}/cabinet-settings`,
      payload,
      { idempotent: true },
    ),

  previewFlush: (scope: FlushScope) =>
    request<FlushPreviewResponse>('GET', `/v1/admin/flush/preview?scope=${encodeURIComponent(scope)}`),
  flushData: (payload: { scope: FlushScope; confirmToken: string; previewToken: string }) =>
    request<FlushResultResponse>('POST', '/v1/admin/flush', payload, { idempotent: true }),

  listUsers: () =>
    request<ListResponse<UserDto>>('GET', '/v1/admin/users').then((r) => r.items),
  createUser: (payload: Record<string, unknown>) =>
    request<UserDto>('POST', '/v1/admin/users', payload, { idempotent: true }),
  updateUser: (id: string, payload: Record<string, unknown>) =>
    request<UserDto>('PATCH', `/v1/admin/users/${id}`, payload, { idempotent: true }),
  deleteUser: (id: string) =>
    request<UserDto>('DELETE', `/v1/admin/users/${id}`, undefined, { idempotent: true }),

  listUserCredentials: (userId: string) =>
    request<ListResponse<CredentialStatusDto>>(
      'GET',
      `/v1/admin/users/${userId}/credentials`,
    ).then((r) => r.items ?? []),

  requestCredentialEnrollment: (
    userId: string,
    payload: {
      credentialKind: string
      terminalId?: string | null
      note?: string
      expectedRevision?: number
    },
  ) =>
    request<CredentialStatusDto>(
      'POST',
      `/v1/admin/users/${userId}/credentials`,
      payload,
      { idempotent: true },
    ),

  listKeys: () =>
    request<ListResponse<KeyDto>>('GET', '/v1/admin/keys').then((r) => r.items),
  createKey: (payload: Record<string, unknown>) =>
    request<KeyDto>('POST', '/v1/admin/keys', payload, { idempotent: true }),
  updateKey: (id: string, payload: Record<string, unknown>) =>
    request<KeyDto>('PATCH', `/v1/admin/keys/${id}`, payload, { idempotent: true }),
  deleteKey: (id: string) =>
    request<KeyDto>('DELETE', `/v1/admin/keys/${id}`, undefined, { idempotent: true }),

  // Key Slots — physical cabinet node ↔ key binding (id, terminalId, nodeAddress, managedKeyId).
  // No dedicated admin page yet (see CLAUDE_WEB.md); called from the Registration wizard's Keys
  // step and the standalone Keys page to auto-assign/release a node when a key is added/deleted.
  listKeySlots: (terminalId?: string) =>
    request<ListResponse<KeySlotDto>>(
      'GET',
      terminalId ? `/v1/admin/key-slots?terminalId=${terminalId}` : '/v1/admin/key-slots',
    ).then((r) => r.items),
  createKeySlot: (payload: { terminalId: string; nodeAddress: number; managedKeyId?: string | null }) =>
    request<KeySlotDto>('POST', '/v1/admin/key-slots', payload, { idempotent: true }),
  updateKeySlot: (
    id: string,
    payload: {
      terminalId: string
      nodeAddress: number
      managedKeyId?: string | null
      expectedRevision: number
    },
  ) => request<KeySlotDto>('PATCH', `/v1/admin/key-slots/${id}`, payload, { idempotent: true }),
  deleteKeySlot: (id: string) =>
    request<KeySlotDto>('DELETE', `/v1/admin/key-slots/${id}`, undefined, { idempotent: true }),

  listAccessGrants: listPath('/v1/admin/access-grants'),
  createAccessGrant: createPath('/v1/admin/access-grants'),
  updateAccessGrant: updatePath('/v1/admin/access-grants'),
  deleteAccessGrant: deletePath('/v1/admin/access-grants'),

  listKeyAccessRequests: (status = 'ALL') =>
    request<ListResponse<KeyAccessRequestDto>>(
      'GET',
      `/v1/admin/key-access-requests?status=${encodeURIComponent(status)}`,
    ).then((r) => r.items ?? []),
  approveKeyAccessRequest: (id: string) =>
    request<Record<string, unknown>>('POST', `/v1/admin/key-access-requests/${id}/approve`, {}, {
      idempotent: true,
    }),
  rejectKeyAccessRequest: (id: string) =>
    request<KeyAccessRequestDto>('POST', `/v1/admin/key-access-requests/${id}/reject`, {}, {
      idempotent: true,
    }),
  revokeKeyAccessRequest: (id: string) =>
    request<KeyAccessRequestDto>('POST', `/v1/admin/key-access-requests/${id}/revoke`, {}, {
      idempotent: true,
    }),

  listEvents: listPath('/v1/admin/event-definitions'),
  createEvent: createPath('/v1/admin/event-definitions'),
  updateEvent: updatePath('/v1/admin/event-definitions'),
  deleteEvent: deletePath('/v1/admin/event-definitions'),

  listSchedules: listPath('/v1/admin/schedules'),
  createSchedule: createPath('/v1/admin/schedules'),
  updateSchedule: updatePath('/v1/admin/schedules'),
  deleteSchedule: deletePath('/v1/admin/schedules'),

  listPersonnelGroups: listPath('/v1/admin/personnel-groups'),
  createPersonnelGroup: createPath('/v1/admin/personnel-groups'),
  updatePersonnelGroup: updatePath('/v1/admin/personnel-groups'),
  deletePersonnelGroup: deletePath('/v1/admin/personnel-groups'),

  listKeyGroups: listPath('/v1/admin/key-groups'),
  createKeyGroup: createPath('/v1/admin/key-groups'),
  updateKeyGroup: updatePath('/v1/admin/key-groups'),
  deleteKeyGroup: deletePath('/v1/admin/key-groups'),

  listMultiAuthRules: listPath('/v1/admin/multi-authentication-rules'),
  createMultiAuthRule: createPath('/v1/admin/multi-authentication-rules'),
  updateMultiAuthRule: updatePath('/v1/admin/multi-authentication-rules'),
  deleteMultiAuthRule: deletePath('/v1/admin/multi-authentication-rules'),

  listSyncConflicts: listPath('/v1/admin/sync-conflicts'),
  resolveSyncConflict: (id: string, payload: Record<string, unknown>) =>
    request<Record<string, unknown>>('POST', `/v1/admin/sync-conflicts/${id}/resolve`, payload, {
      idempotent: true,
    }),

  listRecycleBin: () =>
    request<{ entries: RecycleBinEntry[] }>('GET', '/v1/admin/recycle-bin').then(
      (r) => r.entries ?? [],
    ),
  restoreRecycleBin: (payload: {
    recordType: string
    recordId: string
    expectedRevision?: number
  }) => request('POST', '/v1/admin/recycle-bin/restore', payload, { idempotent: true }),
  purgeRecycleBin: (payload: { recordType: string; recordId: string }) =>
    request('POST', '/v1/admin/recycle-bin/purge', payload, { idempotent: true }),

  listAuditEvents: (filter?: {
    siteId?: string
    terminalId?: string
    limit?: number
  }) => {
    const params = new URLSearchParams()
    if (filter?.siteId) params.set('siteId', filter.siteId)
    if (filter?.terminalId) params.set('terminalId', filter.terminalId)
    params.set('limit', String(filter?.limit ?? 200))
    const qs = params.toString()
    return request<ListResponse<AuditEvent>>('GET', `/v1/audit/events?${qs}`).then(
      (r) => r.items ?? [],
    )
  },

  listKeyOperations: (filter?: {
    siteId?: string
    terminalId?: string
    limit?: number
  }) => {
    const params = new URLSearchParams()
    if (filter?.siteId) params.set('siteId', filter.siteId)
    if (filter?.terminalId) params.set('terminalId', filter.terminalId)
    if (filter?.limit) params.set('limit', String(filter.limit))
    const qs = params.toString()
    return request<ListResponse<Record<string, unknown>>>(
      'GET',
      `/v1/reports/key-operations${qs ? `?${qs}` : ''}`,
    ).then((r) => r.items ?? [])
  },

  listActivityLogs: (filter?: {
    siteId?: string
    terminalId?: string
    fromEpochMillis?: number
    untilEpochMillis?: number
    categories?: string[]
    limit?: number
    cabinetScope?: 'ACTIVE' | 'DELETED'
  }) => {
    const params = new URLSearchParams()
    if (filter?.siteId) params.set('siteId', filter.siteId)
    if (filter?.terminalId) params.set('terminalId', filter.terminalId)
    if (filter?.fromEpochMillis != null) params.set('fromEpochMillis', String(filter.fromEpochMillis))
    if (filter?.untilEpochMillis != null) params.set('untilEpochMillis', String(filter.untilEpochMillis))
    if (filter?.categories?.length) params.set('categories', filter.categories.join(','))
    if (filter?.limit) params.set('limit', String(filter.limit))
    if (filter?.cabinetScope) params.set('cabinetScope', filter.cabinetScope)
    const qs = params.toString()
    return request<{ items: ActivityLogRow[] }>(
      'GET',
      `/v1/reports/activity-logs${qs ? `?${qs}` : ''}`,
    ).then((r) => r.items ?? [])
  },

  getActivitySummary: (filter?: {
    siteId?: string
    terminalId?: string
    fromEpochMillis?: number
    untilEpochMillis?: number
    categories?: string[]
    cabinetScope?: 'ACTIVE' | 'DELETED'
  }) => {
    const params = new URLSearchParams()
    if (filter?.siteId) params.set('siteId', filter.siteId)
    if (filter?.terminalId) params.set('terminalId', filter.terminalId)
    if (filter?.fromEpochMillis != null) params.set('fromEpochMillis', String(filter.fromEpochMillis))
    if (filter?.untilEpochMillis != null) params.set('untilEpochMillis', String(filter.untilEpochMillis))
    if (filter?.categories?.length) params.set('categories', filter.categories.join(','))
    if (filter?.cabinetScope) params.set('cabinetScope', filter.cabinetScope)
    const qs = params.toString()
    return request<ActivitySummaryResponse>(
      'GET',
      `/v1/reports/activity-summary${qs ? `?${qs}` : ''}`,
    )
  },

  createReportExport: (payload: {
    kind: 'KEY_OPERATIONS' | 'SYSTEM_OPERATION_LOGS' | 'EQUIPMENT_OPERATION_LOGS' | 'ACTIVITY_LOGS'
    format: 'PDF' | 'EXCEL'
    filter?: {
      siteId?: string
      terminalId?: string
      fromEpochMillis?: number
      untilEpochMillis?: number
      limit?: number
      categories?: string[]
      cabinetScope?: 'ACTIVE' | 'DELETED'
    }
  }) =>
    request<{
      jobId: string
      downloadPath: string
      rowCount: number
    }>('POST', '/v1/reports/exports', payload, { idempotent: true }),

  async downloadReportExport(
    downloadPath: string,
    filename: string,
    retried = false,
  ): Promise<void> {
    if (!accessToken) {
      const existing = loadSession()
      if (existing?.accessToken) accessToken = existing.accessToken
      else {
        if (existing) notifySessionExpired()
        throw new ApiError(401, 'Sign in required')
      }
    }
    const res = await fetch(downloadPath, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    const contentType = res.headers.get('content-type') || ''
    if (!res.ok) {
      if (res.status === 401) {
        const recovered = await recoverFromUnauthorized(retried)
        if (recovered) return api.downloadReportExport(downloadPath, filename, true)
        throw new ApiError(401, 'Your session has expired. Sign in to continue.')
      }
      const text = await res.text()
      throw new ApiError(res.status, friendlyHttpError(res.status, text, contentType))
    }
    if (!contentType.includes('application/pdf') && !contentType.includes('octet-stream')) {
      const text = await res.text()
      throw new ApiError(
        res.status,
        friendlyHttpError(
          res.status,
          text,
          contentType,
          'PDF download failed — the server did not return a PDF file.',
        ),
      )
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    anchor.click()
    URL.revokeObjectURL(url)
  },
  listSystemLogs: () =>
    request<ListResponse<Record<string, unknown>>>('GET', '/v1/reports/system-operation-logs').then(
      (r) => r.items ?? [],
    ),
  listEquipmentLogs: () =>
    request<ListResponse<Record<string, unknown>>>(
      'GET',
      '/v1/reports/equipment-operation-logs',
    ).then((r) => r.items ?? []),
}
