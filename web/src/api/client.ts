import type {
  KeyDto,
  ListResponse,
  LoginResponse,
  SiteDto,
  TerminalDto,
  UserDto,
} from './types'

const SESSION_KEY = 'ekms_web_session'

export type Session = {
  accessToken: string
  refreshToken: string
  displayName: string
  email: string
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
  opts?: { auth?: boolean; idempotent?: boolean },
): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  if (opts?.auth !== false) {
    if (!accessToken) throw new ApiError(401, 'Not signed in')
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
    const msg =
      (json as { message?: string; error?: string } | null)?.message ||
      (json as { error?: string } | null)?.error ||
      text ||
      `HTTP ${res.status}`
    throw new ApiError(res.status, msg)
  }

  return (json ?? {}) as T
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
    setAccessToken(login.accessToken)
    saveSession({
      accessToken: login.accessToken,
      refreshToken: login.refreshToken,
      displayName: login.profile.displayName,
      email: login.profile.email,
    })
    return login
  },

  logout() {
    setAccessToken(null)
    saveSession(null)
  },

  listSites: () =>
    request<ListResponse<SiteDto>>('GET', '/v1/admin/sites').then((r) => r.items),
  createSite: (payload: Record<string, unknown>) =>
    request<SiteDto>('POST', '/v1/admin/sites', payload, { idempotent: true }),
  deleteSite: (id: string) =>
    request<SiteDto>('DELETE', `/v1/admin/sites/${id}`, undefined, { idempotent: true }),

  listTerminals: () =>
    request<ListResponse<TerminalDto>>('GET', '/v1/admin/terminals').then((r) => r.items),
  createTerminal: (payload: Record<string, unknown>) =>
    request<TerminalDto>('POST', '/v1/admin/terminals', payload, { idempotent: true }),
  deleteTerminal: (id: string) =>
    request<TerminalDto>('DELETE', `/v1/admin/terminals/${id}`, undefined, { idempotent: true }),

  listUsers: () =>
    request<ListResponse<UserDto>>('GET', '/v1/admin/users').then((r) => r.items),
  createUser: (payload: Record<string, unknown>) =>
    request<UserDto>('POST', '/v1/admin/users', payload, { idempotent: true }),
  deleteUser: (id: string) =>
    request<UserDto>('DELETE', `/v1/admin/users/${id}`, undefined, { idempotent: true }),

  listKeys: () =>
    request<ListResponse<KeyDto>>('GET', '/v1/admin/keys').then((r) => r.items),
  createKey: (payload: Record<string, unknown>) =>
    request<KeyDto>('POST', '/v1/admin/keys', payload, { idempotent: true }),
  deleteKey: (id: string) =>
    request<KeyDto>('DELETE', `/v1/admin/keys/${id}`, undefined, { idempotent: true }),

  listAccessGrants: listPath('/v1/admin/access-grants'),
  createAccessGrant: createPath('/v1/admin/access-grants'),
  deleteAccessGrant: deletePath('/v1/admin/access-grants'),

  listEvents: listPath('/v1/admin/event-definitions'),
  createEvent: createPath('/v1/admin/event-definitions'),
  deleteEvent: deletePath('/v1/admin/event-definitions'),

  listSchedules: listPath('/v1/admin/schedules'),
  createSchedule: createPath('/v1/admin/schedules'),
  deleteSchedule: deletePath('/v1/admin/schedules'),

  listPersonnelGroups: listPath('/v1/admin/personnel-groups'),
  createPersonnelGroup: createPath('/v1/admin/personnel-groups'),
  deletePersonnelGroup: deletePath('/v1/admin/personnel-groups'),

  listKeyGroups: listPath('/v1/admin/key-groups'),
  createKeyGroup: createPath('/v1/admin/key-groups'),
  deleteKeyGroup: deletePath('/v1/admin/key-groups'),

  listMultiAuthRules: listPath('/v1/admin/multi-authentication-rules'),
  createMultiAuthRule: createPath('/v1/admin/multi-authentication-rules'),
  deleteMultiAuthRule: deletePath('/v1/admin/multi-authentication-rules'),

  listAppointments: listPath('/v1/admin/appointments'),
  createAppointment: createPath('/v1/admin/appointments'),
  reviewAppointment: (id: string, payload: Record<string, unknown>) =>
    request<Record<string, unknown>>('POST', `/v1/admin/appointments/${id}/review`, payload, {
      idempotent: true,
    }),
  deleteAppointment: deletePath('/v1/admin/appointments'),

  listAppointmentReasons: listPath('/v1/admin/appointment-reasons'),
  createAppointmentReason: createPath('/v1/admin/appointment-reasons'),
  deleteAppointmentReason: deletePath('/v1/admin/appointment-reasons'),

  listAppointmentPermissions: listPath('/v1/admin/appointment-permissions'),
  createAppointmentPermission: createPath('/v1/admin/appointment-permissions'),
  deleteAppointmentPermission: deletePath('/v1/admin/appointment-permissions'),

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

  listAuditEvents: () =>
    request<ListResponse<AuditEvent>>('GET', '/v1/audit/events?limit=200').then(
      (r) => r.items ?? [],
    ),

  listKeyOperations: () =>
    request<ListResponse<Record<string, unknown>>>('GET', '/v1/reports/key-operations').then(
      (r) => r.items ?? [],
    ),
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
