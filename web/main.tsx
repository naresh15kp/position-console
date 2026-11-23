import {createRoot} from 'react-dom/client'
import {useEffect, useRef, useState} from 'react'
import type {FormEvent} from 'react'
import {api, ApiError} from './api'
import type {Alert, Point, Position, Session, Zone} from './api'
import {MapView} from './Map'
import './style.css'

type Snapshot = {items: Position[]; truncated: boolean}
type AlertPage = {items: Alert[]; nextCursor: string | null}
const emptyPositions: Snapshot = {items: [], truncated: false}

function SignIn({onLogin}: {onLogin: (session: Session) => void}) {
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const fields = new FormData(event.currentTarget)
    setBusy(true)
    setError('')
    try {
      const session = await api<Session>('auth/login', undefined, {method: 'POST', body: JSON.stringify({
        username: fields.get('username'), password: fields.get('password'),
      })})
      if (session.user.role === 'INGEST') setError('Use an operator or supervisor account.')
      else onLogin(session)
    } catch (e) {
      setError(e instanceof ApiError && e.status === 401 ? 'Check your username and password.' : 'The service is unavailable.')
    } finally {setBusy(false)}
  }
  return <main className="signin"><form onSubmit={submit}>
    <h1>Position Console</h1>
    <label>Username<input name="username" autoComplete="username" required /></label>
    <label>Password<input type="password" name="password" autoComplete="current-password" required /></label>
    {error && <p role="alert" className="error">{error}</p>}
    <button disabled={busy} type="submit">{busy ? 'Signing in…' : 'Sign in'}</button>
  </form></main>
}

function Console({session, signOut}: {session: Session; signOut: () => void}) {
  const [zones, setZones] = useState<Zone[]>([])
  const [positions, setPositions] = useState(emptyPositions)
  const [page, setPage] = useState<AlertPage>({items: [], nextCursor: null})
  const [status, setStatus] = useState<Alert['status']>('OPEN')
  const [cursors, setCursors] = useState<string[]>([])
  const [connected, setConnected] = useState(false)
  const [announcement, setAnnouncement] = useState('')
  const [selected, setSelected] = useState('')
  const [zoneFilter, setZoneFilter] = useState('')
  const [assetFilter, setAssetFilter] = useState('')
  const [track, setTrack] = useState<Point[]>([])
  const [revision, setRevision] = useState(0)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState('')
  const [resolving, setResolving] = useState('')
  const [note, setNote] = useState('')
  const priorOpen = useRef(new Set<string>())
  const lastAnnouncement = useRef(0)
  const initialized = useRef(false)
  const active = positions.items.find(item => item.assetId === selected)

  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    let geometryLoaded = false
    async function poll() {
      try {
        const [snapshot, geometry] = await Promise.all([
          api<Snapshot>('assets?bbox=-180,-90,180,90&limit=5000', session),
          geometryLoaded ? Promise.resolve(null) : api<{features: Zone[]}>('zones', session),
        ])
        if (controller.signal.aborted) return
        setPositions(snapshot)
        if (geometry) {setZones(geometry.features); geometryLoaded = true}
        setConnected(true)
      } catch (e) {
        if (controller.signal.aborted) return
        setConnected(false)
        if (e instanceof ApiError && e.status === 401) signOut()
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(poll, 1000)
      }
    }
    void poll()
    return () => {controller.abort(); clearTimeout(timer)}
  }, [session, signOut])

  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout>
    async function pollAlerts() {
      const params = new URLSearchParams({status, limit: '40'})
      const cursor = cursors.at(-1)
      if (cursor) params.set('cursor', cursor)
      try {
        const nextPage = await api<AlertPage>('alerts?' + params, session)
        if (cancelled) return
        setPage(nextPage)
        if (status === 'OPEN' && !cursor) {
          const current = new Set(nextPage.items.map(a => a.alertId))
          const count = [...current].filter(id => !priorOpen.current.has(id)).length
          if (initialized.current && count && Date.now() - lastAnnouncement.current > 8000) {
            setAnnouncement(count === 1 ? 'One new open alert.' : count + ' new open alerts.')
            lastAnnouncement.current = Date.now()
          }
          priorOpen.current = current
          initialized.current = true
        }
      } catch (e) {
        if (!cancelled && e instanceof ApiError && e.status === 401) signOut()
      } finally {
        if (!cancelled) timer = setTimeout(pollAlerts, 1500)
      }
    }
    void pollAlerts()
    return () => {cancelled = true; clearTimeout(timer)}
  }, [session, status, cursors, revision, signOut])

  useEffect(() => {
    if (!selected) return
    let cancelled = false
    api<{items: Point[]}>('assets/' + encodeURIComponent(selected) + '/track?limit=100&recent=true', session)
      .then(({items}) => {if (!cancelled) setTrack(items)})
      .catch(() => {if (!cancelled) setTrack([])})
    return () => {cancelled = true}
  }, [selected, session, active?.reportId, connected])

  function select(id: string) {setTrack([]); setSelected(id)}
  const zoneName = (id: string) => zones.find(z => z.properties.zoneId === id)?.properties.name ?? id
  const visibleAssets = positions.items.filter(asset =>
    (!zoneFilter || asset.zoneIds.includes(zoneFilter)) && asset.assetId.includes(assetFilter.toUpperCase()))
  async function command(id: string, action: 'claim' | 'release' | 'resolve') {
    setBusy(id)
    setMessage('')
    try {
      await api('alerts/' + id + '/' + action, session, {method: 'POST', body: action === 'resolve' ? JSON.stringify({note}) : undefined})
      setResolving('')
      setNote('')
      setMessage('Alert ' + (action === 'release' ? 'released' : action === 'claim' ? 'claimed' : 'resolved') + '.')
    } catch (e) {
      setMessage(e instanceof ApiError && e.status === 409
        ? (e.code === 'ALERT_ALREADY_CLAIMED' ? 'Another operator claimed this alert.' : 'The alert changed. Review its current status.')
        : 'The command could not be confirmed. Review the alert before retrying.')
    } finally {
      setBusy('')
      setRevision(x => x + 1)
    }
  }

  return <>
    <header className="top"><h1>Position Console</h1>
      <span className={connected ? 'live' : 'offline'} role="status">{connected ? 'Live' : 'Reconnecting'}</span>
      <span className="identity">{session.user.username} · {session.user.role.toLowerCase()}</span>
      <button onClick={signOut}>Sign out</button>
    </header>
    <main className="layout">
      <section className="workspace" aria-label="Asset monitoring">
        <div className="section-head"><h2>Live map</h2><span>{positions.items.length.toLocaleString()} assets · {zones.length} zones</span></div>
        <MapView assets={positions.items} zones={zones} selected={selected} track={track} select={select} />
        {positions.truncated && <p role="status">The current snapshot reached its limit.</p>}
        <div className="asset-area"><div>
          <h2>Assets by zone</h2>
          <div className="filters"><label>Zone<select value={zoneFilter} onChange={e => setZoneFilter(e.target.value)}>
            <option value="">All zones</option>
            {zones.map(z => <option key={z.properties.zoneId} value={z.properties.zoneId}>{z.properties.name}</option>)}
          </select></label>
          <label>Asset ID<input value={assetFilter} onChange={e => setAssetFilter(e.target.value)} /></label></div>
          <p>{visibleAssets.length} matching assets</p>
          <ul className="asset-list">{visibleAssets.slice(0, 100).map(asset => <li key={asset.assetId}>
            <button onClick={() => select(asset.assetId)} aria-pressed={asset.assetId === selected}>{asset.assetId}</button>
            <span>{asset.zoneIds.map(zoneName).join(', ') || 'Outside zones'}</span>
          </li>)}</ul>
          {visibleAssets.length > 100 && <p>Showing 100. Filter by zone or asset ID to narrow the list.</p>}
        </div><div>
          <h2>Asset detail</h2>
          {active ? <>
            <h3>{active.assetId}</h3>
            <p>{active.kind || 'Unknown kind'} · {active.lat.toFixed(4)}°, {active.lon.toFixed(4)}°</p>
            <p>Latest report <time>{active.observedAt}</time></p>
            <p>Heading {active.headingDeg ?? 'unknown'} · Speed {active.speedKn ?? 'unknown'} kn</p>
            <h3>Recent track</h3>
            <ol className="track">{[...track].reverse().map(p => <li key={p.reportId}>
              <time>{p.observedAt}</time> · {p.lat.toFixed(4)}°, {p.lon.toFixed(4)}° · {p.source ?? 'Unknown source'}
            </li>)}</ol>
          </> : <p>Select an asset to see its position and recent track.</p>}
        </div></div>
      </section>
      <aside className="alerts" aria-label="Alerts">
        <div className="section-head"><h2>Alerts</h2><label>Status<select value={status} onChange={e => {
          setStatus(e.target.value as Alert['status']); setCursors([]); setPage({items: [], nextCursor: null})
        }}><option>OPEN</option><option>CLAIMED</option><option>RESOLVED</option></select></label></div>
        <p role="status" className="sr-only">{announcement}</p>
        {message && <p role="status" className="message">{message}</p>}
        <ul className="alert-list">{page.items.map(alert => {
          const canManage = alert.claimedBy === session.user.username || session.user.role === 'SUPERVISOR'
          return <li key={alert.alertId}>
            <div className="alert-title"><strong>{zoneName(alert.zoneId)}</strong><span>{alert.status}</span></div>
            <p><button className="text-btn" onClick={() => select(alert.assetId)}>{alert.assetId}</button> · {alert.claimedBy || 'Unassigned'}</p>
            <div className="actions">
              {alert.status === 'OPEN' && <button disabled={!!busy} onClick={() => void command(alert.alertId, 'claim')}>Claim<span className="sr-only"> alert {alert.alertId}</span></button>}
              {alert.status === 'CLAIMED' && canManage && <>
                <button disabled={!!busy} onClick={() => void command(alert.alertId, 'release')}>Release<span className="sr-only"> alert {alert.alertId}</span></button>
                <button disabled={!!busy} onClick={() => {setResolving(alert.alertId); setNote('')}}>Resolve<span className="sr-only"> alert {alert.alertId}</span></button>
              </>}
            </div>
            {resolving === alert.alertId && <form className="resolve" onSubmit={e => {e.preventDefault(); void command(alert.alertId, 'resolve')}}>
              <label>Resolution note<textarea value={note} onChange={e => setNote(e.target.value)} maxLength={500} required autoFocus /></label>
              <button disabled={!!busy || !note.trim()}>Confirm resolution</button>
              <button type="button" onClick={() => setResolving('')}>Cancel</button>
            </form>}
          </li>
        })}</ul>
        {!page.items.length && <p>No alerts on this page.</p>}
        <div className="actions">
          <button disabled={!cursors.length} onClick={() => setCursors(c => c.slice(0, -1))}>Previous</button>
          <button disabled={!page.nextCursor} onClick={() => {if (page.nextCursor) setCursors(c => [...c, page.nextCursor as string])}}>Next</button>
        </div>
      </aside>
    </main>
  </>
}

function App() {
  const [session, setSession] = useState<Session>()
  const signOut = useRef(() => setSession(undefined)).current
  return session ? <Console session={session} signOut={signOut} /> : <SignIn onLogin={setSession} />
}

createRoot(document.getElementById('root')!).render(<App />)