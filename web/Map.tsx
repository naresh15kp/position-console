import {useEffect, useRef, useState} from 'react'
import type {Point, Position, Zone} from './api'

const center = {lon: 174, lat: 39, scale: 35}
// Use the shortest signed longitude delta so panning remains continuous at ±180°.
const wrap = (delta: number) => ((delta + 180) % 360 + 360) % 360 - 180

export function MapView({assets, zones, selected, track, select}: {
  assets: Position[]; zones: Zone[]; selected: string; track: Point[]; select: (id: string) => void
}) {
  const host = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const drag = useRef<{x: number; y: number; moved: boolean} | null>(null)
  const [size, setSize] = useState({width: 600, height: 440})
  const [view, setView] = useState(center)
  const project = (lon: number, lat: number) => [
    size.width / 2 + wrap(lon - view.lon) * view.scale,
    size.height / 2 - (lat - view.lat) * view.scale,
  ]
  const zoom = (factor: number) => setView(v => ({...v, scale: Math.max(2, Math.min(2600, v.scale * factor))}))

  useEffect(() => {
    const observer = new ResizeObserver(([entry]) => setSize({width: entry.contentRect.width, height: entry.contentRect.height}))
    if (host.current) observer.observe(host.current)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    const element = canvas.current
    const context = element?.getContext('2d')
    if (!element || !context) return
    // Cap the backing-store multiplier to keep large high-DPI canvases bounded.
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    element.width = size.width * dpr
    element.height = size.height * dpr
    context.scale(dpr, dpr)
    context.fillStyle = '#101d29'
    context.fillRect(0, 0, size.width, size.height)
    const step = view.scale > 200 ? 0.25 : view.scale > 18 ? 2 : 15
    context.strokeStyle = '#243746'
    context.fillStyle = '#9db0bf'
    context.font = '10px system-ui'
    for (let lon = Math.floor((view.lon - 180) / step) * step; lon < view.lon + 180; lon += step) {
      const [x] = project(lon, view.lat)
      if (x < 0 || x > size.width) continue
      context.beginPath(); context.moveTo(x, 0); context.lineTo(x, size.height); context.stroke()
      context.fillText(wrap(lon) + '°', x + 4, size.height - 10)
    }
    for (let lat = -90; lat <= 90; lat += step) {
      const [, y] = project(view.lon, lat)
      if (y < 0 || y > size.height) continue
      context.beginPath(); context.moveTo(0, y); context.lineTo(size.width, y); context.stroke()
      context.fillText(lat + '°', 6, y - 4)
    }
    for (const zone of zones) {
      const {geometry, properties} = zone
      const parts = geometry.type === 'Polygon' ? [geometry.coordinates] : geometry.coordinates
      const isRestricted = properties.class === 'RESTRICTED'
      context.strokeStyle = isRestricted ? '#f8b568' : '#73c4db'
      context.fillStyle = isRestricted ? '#e28f3536' : '#68cbe32b'
      for (const rings of parts) {
        context.beginPath()
        for (const ring of rings) {
          ring.forEach(([lon, lat], i) => {
            const [x, y] = project(lon, lat)
            if (i === 0) context.moveTo(x, y); else context.lineTo(x, y)
          })
          context.closePath()
        }
        // Even-odd filling preserves holes regardless of ring winding.
        context.fill('evenodd'); context.stroke()
      }
      const first = parts[0]?.[0]?.[0]
      if (first) {
        const [x, y] = project(...first)
        context.fillStyle = isRestricted ? '#ffcb93' : '#a4dfed'
        context.fillText(properties.name, x + 5, y - 5)
      }
    }
    context.strokeStyle = '#f9e8a2'
    context.beginPath()
    track.forEach((point, i) => {
      const [x, y] = project(point.lon, point.lat)
      if (i === 0) context.moveTo(x, y); else context.lineTo(x, y)
    })
    context.stroke()
    for (const asset of assets) {
      const [x, y] = project(asset.lon, asset.lat)
      if (x < -10 || y < -10 || x > size.width + 10 || y > size.height + 10) continue
      const active = asset.assetId === selected
      const angle = (asset.headingDeg ?? 0) * Math.PI / 180
      const radius = active ? 9 : 5
      context.fillStyle = active ? '#fff0a6' : asset.zoneIds.length ? '#f9aa70' : '#77e1c8'
      context.beginPath()
      for (const offset of [0, 2.45, -2.45]) {
        const a = angle + offset
        context.lineTo(x + Math.sin(a) * radius, y - Math.cos(a) * radius)
      }
      context.closePath(); context.fill()
    }
  })

  return <div className="map-wrap" ref={host}>
    <canvas ref={canvas} aria-label="Live map of assets and zones" role="img"
      onPointerDown={event => {
        event.currentTarget.setPointerCapture(event.pointerId)
        drag.current = {x: event.clientX, y: event.clientY, moved: false}
      }}
      onPointerCancel={() => {drag.current = null}}
      onPointerMove={event => {
        const previous = drag.current
        if (!previous) return
        const dx = event.clientX - previous.x, dy = event.clientY - previous.y
        if (!dx && !dy) return
        drag.current = {x: event.clientX, y: event.clientY, moved: true}
        setView(v => ({...v, lon: wrap(v.lon - dx / v.scale), lat: Math.max(-90, Math.min(90, v.lat + dy / v.scale))}))
      }}
      onPointerUp={event => {
        if (drag.current && !drag.current.moved) {
          const rect = event.currentTarget.getBoundingClientRect()
          let distance = 14, closest = ''
          for (const asset of assets) {
            const [x, y] = project(asset.lon, asset.lat)
            const d = Math.hypot(event.clientX - rect.left - x, event.clientY - rect.top - y)
            if (d < distance) {distance = d; closest = asset.assetId}
          }
          if (closest) select(closest)
        }
        drag.current = null
      }} />
    <div className="map-buttons"><button onClick={() => zoom(1.35)} aria-label="Zoom in">+</button><button onClick={() => zoom(1 / 1.35)} aria-label="Zoom out">−</button><button onClick={() => setView(center)}>Reset view</button></div>
    <div className="legend"><span className="restricted">Restricted</span><span className="advisory">Advisory</span><span className="position">Asset</span></div>
  </div>
}