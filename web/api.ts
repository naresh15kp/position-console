export type User = {username: string; role: 'INGEST' | 'OPERATOR' | 'SUPERVISOR'}
export type Session = {user: User; accessToken: string}
export type SourceType = 'SATELLITE' | 'GROUND' | 'MOBILE' | 'UNKNOWN'
export type Point = {reportId: string; observedAt: string; lat: number; lon: number; source?: string | null; sourceType?: SourceType}
export type Position = Point & {assetId: string; kind?: string | null; headingDeg?: number | null; speedKn?: number | null; zoneIds: string[]}
export type Alert = {alertId: string; assetId: string; zoneId: string; status: 'OPEN' | 'CLAIMED' | 'RESOLVED'; openedAt: string; claimedBy?: string | null}
type Coordinate = [number, number]
type Polygon = Coordinate[][]
export type Zone = {properties: {zoneId: string; name: string; class: 'RESTRICTED' | 'ADVISORY'}; geometry: {type: 'Polygon'; coordinates: Polygon} | {type: 'MultiPolygon'; coordinates: Polygon[]}}

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code) }
}

export async function api<T>(path: string, session?: Session, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (session) headers.set('Authorization', 'Bearer ' + session.accessToken)
  if (options.body) headers.set('Content-Type', 'application/json')
  const response = await fetch('/api/v1/' + path, {...options, headers, signal: options.signal ?? AbortSignal.timeout(8000)})
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as {code?: string} | null
    throw new ApiError(response.status, problem?.code ?? 'REQUEST_FAILED')
  }
  return response.json() as Promise<T>
}