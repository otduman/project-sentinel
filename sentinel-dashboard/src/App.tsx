import { useEffect, useRef, useState, useCallback } from 'react';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type ServiceId = 'lab-rat' | 'sentinel-agent' | 'prometheus' | 'alertmanager' | 'grafana';
type ServiceStatus = 'UP' | 'DOWN' | 'UNKNOWN';

interface ServiceState {
  id: ServiceId;
  status: ServiceStatus;
  lastChecked: number;
}

interface AlertEntry {
  id: string;
  alertname: string;
  severity: string;
  state: string; // 'active' | 'suppressed' | 'resolved'
  timestamp: number;
  labels: Record<string, string>;
}

interface AppState {
  services: Record<ServiceId, ServiceState>;
  heapMB: number | null;
  alerts: AlertEntry[];
  lastPoll: number;
}

// ---------------------------------------------------------------------------
// Service configuration
// ---------------------------------------------------------------------------

const SERVICE_CONFIG: Record<ServiceId, { label: string; port: number; color: string; fill: string; borderBase: string; accent: string }> = {
  'lab-rat':        { label: 'LAB-RAT',       port: 8080, color: '#22c55e', fill: '#0d1f14', borderBase: '#1a3d24', accent: '#22c55e' },
  'sentinel-agent': { label: 'SENTINEL',       port: 8081, color: '#3b82f6', fill: '#0d1520', borderBase: '#1a2e4a', accent: '#3b82f6' },
  'prometheus':     { label: 'PROMETHEUS',     port: 9090, color: '#f97316', fill: '#1f1508', borderBase: '#3d2510', accent: '#f97316' },
  'alertmanager':   { label: 'ALERTMANAGER',   port: 9093, color: '#eab308', fill: '#1f1a06', borderBase: '#3d3010', accent: '#eab308' },
  'grafana':        { label: 'GRAFANA',        port: 3000, color: '#a855f7', fill: '#160d1f', borderBase: '#2e1a3d', accent: '#a855f7' },
};

// ---------------------------------------------------------------------------
// Polling helpers
// ---------------------------------------------------------------------------

async function checkLabRat(): Promise<ServiceStatus> {
  try {
    const res = await fetch('http://localhost:8080/actuator/health', { signal: AbortSignal.timeout(3000) });
    if (!res.ok) return 'DOWN';
    const data = await res.json();
    return data?.status === 'UP' ? 'UP' : 'DOWN';
  } catch {
    return 'DOWN';
  }
}

async function checkSentinelAgent(): Promise<ServiceStatus> {
  try {
    await fetch('http://localhost:8081/actuator/health', { signal: AbortSignal.timeout(3000) });
    return 'UP'; // any response, even 404, means alive
  } catch {
    return 'DOWN';
  }
}

async function checkNoCors(url: string): Promise<ServiceStatus> {
  try {
    await fetch(url, { mode: 'no-cors', signal: AbortSignal.timeout(3000) });
    return 'UP'; // opaque response = alive
  } catch {
    return 'DOWN';
  }
}

async function fetchHeapMB(): Promise<number | null> {
  try {
    const res = await fetch(
      'http://localhost:8081/api/proxy/heap',
      { signal: AbortSignal.timeout(3000) }
    );
    if (!res.ok) return null;
    const data = await res.json();
    const raw = data?.data?.result?.[0]?.value?.[1];
    if (raw == null) return null;
    return parseFloat(raw) / (1024 * 1024);
  } catch {
    return null;
  }
}

async function fetchAlerts(): Promise<AlertEntry[]> {
  try {
    const res = await fetch('http://localhost:8081/api/proxy/alerts', { signal: AbortSignal.timeout(3000) });
    if (!res.ok) return [];
    const data = await res.json();
    const raw: any[] = Array.isArray(data) ? data : (data?.data ?? []);
    return raw.map((a) => ({
      id: `${a.labels?.alertname ?? 'unknown'}-${a.startsAt ?? Date.now()}`,
      alertname: a.labels?.alertname ?? 'Unknown Alert',
      severity: a.labels?.severity ?? 'info',
      state: a.status?.state ?? 'active',
      timestamp: a.startsAt ? new Date(a.startsAt).getTime() : Date.now(),
      labels: a.labels ?? {},
    }));
  } catch {
    return [];
  }
}

// ---------------------------------------------------------------------------
// useCommandRoom hook — owns all polling state
// ---------------------------------------------------------------------------

function useCommandRoom() {
  const [state, setState] = useState<AppState>({
    services: {
      'lab-rat':        { id: 'lab-rat',        status: 'UNKNOWN', lastChecked: 0 },
      'sentinel-agent': { id: 'sentinel-agent',  status: 'UNKNOWN', lastChecked: 0 },
      'prometheus':     { id: 'prometheus',      status: 'UNKNOWN', lastChecked: 0 },
      'alertmanager':   { id: 'alertmanager',    status: 'UNKNOWN', lastChecked: 0 },
      'grafana':        { id: 'grafana',         status: 'UNKNOWN', lastChecked: 0 },
    },
    heapMB: null,
    alerts: [],
    lastPoll: 0,
  });

  // Track resolved alerts: once an alert from AlertManager disappears, we show it as RESOLVED for a while
  const prevAlertIds = useRef<Set<string>>(new Set());
  const resolvedBuffer = useRef<AlertEntry[]>([]);

  const poll = useCallback(async () => {
    const now = Date.now();

    const [labRat, sentinelAgent, prometheus, alertmanager, grafana, heapMB, freshAlerts] = await Promise.all([
      checkLabRat(),
      checkSentinelAgent(),
      checkNoCors('http://localhost:9090/-/healthy'),
      checkNoCors('http://localhost:9093/-/healthy'),
      checkNoCors('http://localhost:3000/api/health'),
      fetchHeapMB(),
      fetchAlerts(),
    ]);

    // Detect newly resolved alerts
    const freshIds = new Set(freshAlerts.map((a) => a.id));
    prevAlertIds.current.forEach((oldId) => {
      if (!freshIds.has(oldId)) {
        // This alert disappeared — mark resolved
        resolvedBuffer.current.push({
          id: `resolved-${oldId}-${now}`,
          alertname: oldId.split('-')[0],
          severity: 'resolved',
          state: 'resolved',
          timestamp: now,
          labels: {},
        });
      }
    });
    prevAlertIds.current = freshIds;

    // Merge fresh + resolved, cap at 20, newest first
    const combined = [...freshAlerts, ...resolvedBuffer.current]
      .sort((a, b) => b.timestamp - a.timestamp)
      .slice(0, 20);

    // Trim resolved buffer — keep only last 10 seconds worth
    resolvedBuffer.current = resolvedBuffer.current.filter((r) => now - r.timestamp < 10_000);

    setState({
      services: {
        'lab-rat':        { id: 'lab-rat',        status: labRat,        lastChecked: now },
        'sentinel-agent': { id: 'sentinel-agent',  status: sentinelAgent, lastChecked: now },
        'prometheus':     { id: 'prometheus',      status: prometheus,    lastChecked: now },
        'alertmanager':   { id: 'alertmanager',    status: alertmanager,  lastChecked: now },
        'grafana':        { id: 'grafana',         status: grafana,       lastChecked: now },
      },
      heapMB,
      alerts: combined,
      lastPoll: now,
    });
  }, []);

  useEffect(() => {
    poll();
    const interval = setInterval(poll, 3000);
    return () => clearInterval(interval);
  }, [poll]);

  return state;
}

// ---------------------------------------------------------------------------
// Heap thresholds
// ---------------------------------------------------------------------------

const HEAP_WARN_MB = 150;
const HEAP_CRIT_MB = 200;

function heapColor(heapMB: number | null): string {
  if (heapMB == null) return '#7d8590';
  if (heapMB >= HEAP_CRIT_MB) return '#f85149';
  if (heapMB >= HEAP_WARN_MB) return '#d29922';
  return '#3fb950';
}

// ---------------------------------------------------------------------------
// SystemClock
// ---------------------------------------------------------------------------

function SystemClock() {
  const [time, setTime] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setTime(new Date()), 1000);
    return () => clearInterval(id);
  }, []);
  return (
    <span className="sys-clock">
      {time.toLocaleTimeString('en-US', { hour12: false })}
    </span>
  );
}

// ---------------------------------------------------------------------------
// Hex geometry — pointy-top, circumradius = 65px
// ---------------------------------------------------------------------------

const HEX_SIZE = 65;
const HEX_INRADIUS = HEX_SIZE * Math.sqrt(3) / 2; // ~56.3px, center to edge midpoint

function hexVertices(cx: number, cy: number, size: number): string {
  return Array.from({ length: 6 }, (_, i) => {
    const a = (Math.PI / 180) * (60 * i - 30);
    return `${cx + size * Math.cos(a)},${cy + size * Math.sin(a)}`;
  }).join(' ');
}

function axialToPixel(q: number, r: number): { x: number; y: number } {
  return {
    x: HEX_SIZE * Math.sqrt(3) * (q + r / 2),
    y: HEX_SIZE * 1.5 * r,
  };
}

// Service axial coordinates
const SERVICE_AXIAL: Record<ServiceId, [number, number]> = {
  'grafana':        [-2,  0],
  'prometheus':     [ 0,  0],
  'alertmanager':   [ 2,  0],
  'sentinel-agent': [ 0,  2],
  'lab-rat':        [-2,  4],
};

// Pixel centers computed once at module level (used as initial positions)
const SERVICE_CENTERS: Record<ServiceId, { x: number; y: number }> = Object.fromEntries(
  (Object.entries(SERVICE_AXIAL) as [ServiceId, [number, number]][]).map(
    ([id, [q, r]]) => [id, axialToPixel(q, r)]
  )
) as Record<ServiceId, { x: number; y: number }>;

// Suppress unused variable warning — exported for external use if needed
void SERVICE_CENTERS;

// ---------------------------------------------------------------------------
// Connection topology
// ---------------------------------------------------------------------------

const CONNECTIONS: [ServiceId, ServiceId][] = [
  ['grafana',        'prometheus'],
  ['prometheus',     'alertmanager'],
  ['prometheus',     'sentinel-agent'],
  ['alertmanager',   'sentinel-agent'],
  ['sentinel-agent', 'lab-rat'],
];

// ---------------------------------------------------------------------------
// Connection geometry — obstacle-aware path computation
// ---------------------------------------------------------------------------

// Distance from point (px,py) to line segment (x1,y1)→(x2,y2)
function distPointToSegment(
  px: number, py: number,
  x1: number, y1: number,
  x2: number, y2: number
): number {
  const dx = x2 - x1, dy = y2 - y1;
  const lenSq = dx * dx + dy * dy;
  if (lenSq === 0) return Math.hypot(px - x1, py - y1);
  const t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / lenSq));
  return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
}

// Intersect infinite line (p1→p2) with finite segment (p3→p4).
// Returns intersection point or null.
function segIntersect(
  p1x: number, p1y: number, p2x: number, p2y: number,
  p3x: number, p3y: number, p4x: number, p4y: number
): [number, number] | null {
  const rx = p2x - p1x, ry = p2y - p1y;
  const sx = p4x - p3x, sy = p4y - p3y;
  const denom = rx * sy - ry * sx;
  if (Math.abs(denom) < 1e-9) return null;
  const t = ((p3x - p1x) * sy - (p3y - p1y) * sx) / denom;
  const u = ((p3x - p1x) * ry - (p3y - p1y) * rx) / denom;
  if (t >= -1e-6 && t <= 1 + 1e-6 && u >= -1e-6 && u <= 1 + 1e-6) {
    return [p1x + t * rx, p1y + t * ry];
  }
  return null;
}

// Given a blocker hex center, find where segment (x1,y1)→(x2,y2) crosses
// the hex boundary. Returns { entry, entryEdge, exit, exitEdge } or null.
function hexBoundaryIntersections(
  x1: number, y1: number, x2: number, y2: number,
  cx: number, cy: number
): { entry: [number,number]; entryEdge: number; exit: [number,number]; exitEdge: number } | null {
  const verts: [number, number][] = Array.from({ length: 6 }, (_, i) => {
    const a = (Math.PI / 180) * (60 * i - 30);
    return [cx + HEX_SIZE * Math.cos(a), cy + HEX_SIZE * Math.sin(a)];
  });
  const hits: { pt: [number, number]; edge: number; t: number }[] = [];
  for (let i = 0; i < 6; i++) {
    const [ax, ay] = verts[i];
    const [bx, by] = verts[(i + 1) % 6];
    const pt = segIntersect(x1, y1, x2, y2, ax, ay, bx, by);
    if (pt) {
      // t along the main segment
      const len = Math.hypot(x2 - x1, y2 - y1);
      const t = len > 0 ? Math.hypot(pt[0] - x1, pt[1] - y1) / len : 0;
      hits.push({ pt, edge: i, t });
    }
  }
  if (hits.length < 2) return null;
  hits.sort((a, b) => a.t - b.t);
  return {
    entry: hits[0].pt,
    entryEdge: hits[0].edge,
    exit: hits[hits.length - 1].pt,
    exitEdge: hits[hits.length - 1].edge,
  };
}


// For pointy-top hexes, edge midpoints lie at angles 0°, 60°, 120°, 180°, 240°, 300°.
// Returns the index (0–5) of the edge on cb that the connection arrives from.
function arrivalEdgeIndex(ca: { x: number; y: number }, cb: { x: number; y: number }): number {
  const angle = Math.atan2(ca.y - cb.y, ca.x - cb.x) * (180 / Math.PI);
  const norm = ((angle % 360) + 360) % 360;
  return Math.round(norm / 60) % 6;
}

interface PortAssignment {
  startPt: { x: number; y: number } | null;
  endPt:   { x: number; y: number } | null;
}

function assignConnectionPorts(
  connections: [ServiceId, ServiceId][],
  centers: Record<ServiceId, { x: number; y: number }>
): Map<string, PortAssignment> {

  // Sort shortest connections first — they get first pick of ideal edge
  const sorted = [...connections].sort(([a1, b1], [a2, b2]) => {
    const l1 = Math.hypot(centers[b1].x - centers[a1].x, centers[b1].y - centers[a1].y);
    const l2 = Math.hypot(centers[b2].x - centers[a2].x, centers[b2].y - centers[a2].y);
    return l1 - l2;
  });

  // Edge search: try ideal, then ±1, ±2 before sharing
  const SEARCH_RADIUS = 2;
  const edgePriority = (ideal: number): number[] => [
    ideal,
    (ideal + 1) % 6, (ideal + 5) % 6,
    (ideal + 2) % 6, (ideal + 4) % 6,
    (ideal + 3) % 6,
  ];

  // occupancy: "hexId:edgeIdx" → ordered list of connection keys using that edge
  const occupancy = new Map<string, string[]>();
  const getSlot = (hexId: ServiceId, edge: number): string[] => {
    const k = `${hexId}:${edge}`;
    if (!occupancy.has(k)) occupancy.set(k, []);
    return occupancy.get(k)!;
  };

  const findFreeEdge = (hexId: ServiceId, ideal: number): number => {
    const prio = edgePriority(ideal);
    // Search up to SEARCH_RADIUS alternatives on each side
    for (let i = 0; i <= SEARCH_RADIUS * 2; i++) {
      if (getSlot(hexId, prio[i]).length === 0) return prio[i];
    }
    return ideal; // no free edge found — share ideal with spreading
  };

  // First pass: assign edge indices
  const edgeMap = new Map<string, { deptEdge: number; arrEdge: number }>();

  for (const [a, b] of sorted) {
    const connKey = `${a}-${b}`;
    const len = Math.hypot(centers[b].x - centers[a].x, centers[b].y - centers[a].y);

    if (len <= 2 * HEX_INRADIUS + 4) {
      // Adjacent stub — no port assignment needed
      edgeMap.set(connKey, { deptEdge: -1, arrEdge: -1 });
      continue;
    }

    // Face of 'a' pointing toward 'b' (departure edge)
    const idealDept = arrivalEdgeIndex(centers[b], centers[a]);
    // Face of 'b' pointing toward 'a' (arrival edge)
    const idealArr  = arrivalEdgeIndex(centers[a], centers[b]);

    const deptEdge = findFreeEdge(a, idealDept);
    const arrEdge  = findFreeEdge(b, idealArr);

    getSlot(a, deptEdge).push(connKey);
    getSlot(b, arrEdge ).push(connKey);
    edgeMap.set(connKey, { deptEdge, arrEdge });
  }

  // Second pass: compute pixel positions (spreading on shared edges)
  const edgePoint = (hexId: ServiceId, edge: number, connKey: string): { x: number; y: number } => {
    const c = centers[hexId];
    const verts: [number, number][] = Array.from({ length: 6 }, (_, i) => {
      const ang = (Math.PI / 180) * (60 * i - 30);
      return [c.x + HEX_SIZE * Math.cos(ang), c.y + HEX_SIZE * Math.sin(ang)];
    });
    const [vx1, vy1] = verts[edge];
    const [vx2, vy2] = verts[(edge + 1) % 6];
    const occupants = getSlot(hexId, edge);
    const N = occupants.length;
    if (N <= 1) return { x: (vx1 + vx2) / 2, y: (vy1 + vy2) / 2 };
    const frac = (occupants.indexOf(connKey) + 1) / (N + 1);
    return { x: vx1 + frac * (vx2 - vx1), y: vy1 + frac * (vy2 - vy1) };
  };

  const result = new Map<string, PortAssignment>();
  for (const [a, b] of sorted) {
    const connKey = `${a}-${b}`;
    const { deptEdge, arrEdge } = edgeMap.get(connKey)!;
    if (deptEdge === -1) {
      result.set(connKey, { startPt: null, endPt: null });
    } else {
      result.set(connKey, {
        startPt: edgePoint(a, deptEdge, connKey),
        endPt:   edgePoint(b, arrEdge,  connKey),
      });
    }
  }
  return result;
}

interface ConnectionPath {
  d: string;        // SVG path string (M...L... or M...Q...)
  endX: number;     // arrowhead position X
  endY: number;     // arrowhead position Y
  endAngle: number; // arrowhead rotation in degrees
}

function computeConnectionPath(
  a: ServiceId,
  b: ServiceId,
  centers: Record<ServiceId, { x: number; y: number }>,
  overrideStart?: { x: number; y: number },
  overrideEnd?:   { x: number; y: number }
): ConnectionPath | null {
  const ca = centers[a];
  const cb = centers[b];
  const dx = cb.x - ca.x, dy = cb.y - ca.y;
  const len = Math.hypot(dx, dy);
  if (len < 1) return null;

  const nx = dx / len, ny = dy / len;
  const STUB = 5; // half-length of adjacent stub in px

  // ── Case 1: Adjacent hexes (shared border) ───────────────────────────────
  // Boundary-to-boundary would be zero length. Use a short stub centered
  // on the midpoint of the connecting line (≈ the shared border midpoint).
  if (len <= 2 * HEX_INRADIUS + 4) {
    const mx = (ca.x + cb.x) / 2;
    const my = (ca.y + cb.y) / 2;
    return {
      d: `M ${mx - nx * STUB},${my - ny * STUB} L ${mx + nx * STUB},${my + ny * STUB}`,
      endX: mx + nx * STUB,
      endY: my + ny * STUB,
      endAngle: Math.atan2(ny, nx) * (180 / Math.PI),
    };
  }

  // ── Case 2: Non-adjacent — boundary points ────────────────────────────────
  // Start just outside source boundary, end just outside destination boundary
  // (1px gap so arrowhead tip sits exactly on the hex edge)
  const x1 = overrideStart ? overrideStart.x : ca.x + nx * (HEX_INRADIUS + 1);
  const y1 = overrideStart ? overrideStart.y : ca.y + ny * (HEX_INRADIUS + 1);
  const x2 = overrideEnd   ? overrideEnd.x   : cb.x - nx * (HEX_INRADIUS + 1);
  const y2 = overrideEnd   ? overrideEnd.y   : cb.y - ny * (HEX_INRADIUS + 1);

  // ── Case 2a: Check for blocking hexes ─────────────────────────────────────
  // A hex blocks the path if its center is within 1.05 * HEX_INRADIUS of the
  // straight line segment x1,y1 → x2,y2 (tighter — only route around hexes
  // the line actually passes through).
  const others = (Object.keys(centers) as ServiceId[]).filter(id => id !== a && id !== b);
  let blocker: { center: { x: number; y: number }; dist: number } | null = null;

  for (const id of others) {
    const cc = centers[id];
    const dist = distPointToSegment(cc.x, cc.y, x1, y1, x2, y2);
    if (dist < HEX_INRADIUS * 1.05) {
      if (!blocker || dist < blocker.dist) {
        blocker = { center: centers[id], dist };
      }
    }
  }

  // ── Case 2b: Blocked — route along blocker hex perimeter ─────────────────
  if (blocker) {
    const bc = blocker.center;
    // Use full centre→centre for intersection so both entry+exit are always found
    const info = hexBoundaryIntersections(ca.x, ca.y, cb.x, cb.y, bc.x, bc.y);
    if (info) {
      const { entry, entryEdge, exit, exitEdge } = info;

      const verts: [number, number][] = Array.from({ length: 6 }, (_, i) => {
        const ang = (Math.PI / 180) * (60 * i - 30);
        return [bc.x + HEX_SIZE * Math.cos(ang), bc.y + HEX_SIZE * Math.sin(ang)];
      });

      // CW arc: (entryEdge+1)%6 … exitEdge
      const cwV: [number, number][] = [];
      { let idx = (entryEdge + 1) % 6;
        for (let n = 0; n < 6; n++) {
          cwV.push(verts[idx]);
          if (idx === exitEdge) break;
          idx = (idx + 1) % 6;
        }
      }
      // CCW arc: entryEdge … (exitEdge+1)%6
      const ccwV: [number, number][] = [];
      { let idx = entryEdge;
        for (let n = 0; n < 6; n++) {
          ccwV.push(verts[idx]);
          if (idx === (exitEdge + 1) % 6) break;
          idx = (idx + 5) % 6;
        }
      }

      const arcVerts = cwV.length <= ccwV.length ? cwV : ccwV;

      // Path: boundary-x1 → entry (blocker edge) → perimeter → exit (blocker edge) → boundary-x2
      const pts: [number, number][] = [
        [x1, y1], entry, ...arcVerts, exit, [x2, y2]
      ];
      const d = pts.map(([px, py], i) => `${i === 0 ? 'M' : 'L'} ${px},${py}`).join(' ');

      // Arrowhead direction = last segment (exit → x2,y2)
      const tanX = x2 - exit[0], tanY = y2 - exit[1];
      const tanLen = Math.hypot(tanX, tanY) || 1;
      return {
        d,
        endX: x2, endY: y2,
        endAngle: Math.atan2(tanY / tanLen, tanX / tanLen) * (180 / Math.PI),
      };
    }
  }

  // ── Case 2c: Clear path — straight line ───────────────────────────────────
  return {
    d: `M ${x1},${y1} L ${x2},${y2}`,
    endX: x2,
    endY: y2,
    endAngle: Math.atan2(ny, nx) * (180 / Math.PI),
  };
}

// ---------------------------------------------------------------------------
// Hex style interface
// ---------------------------------------------------------------------------

interface HexStyle {
  fill: string;
  stroke: string;
  strokeWidth: number;
  strokeOpacity: number;
  textColor: string;
  dotColor: string;
  glowFilter: string;
  innerGlow: boolean;
  innerGlowColor?: string;
}

// ---------------------------------------------------------------------------
// AlertPanel component
// ---------------------------------------------------------------------------

function AlertPanel({ alerts }: { alerts: AlertEntry[] }) {
  const panelRef = useRef<HTMLDivElement>(null);

  const severityColor = (sev: string, state: string): string => {
    if (state === 'resolved') return '#3fb950';
    if (sev === 'critical') return '#f85149';
    if (sev === 'warning') return '#d29922';
    return '#7d8590';
  };

  const stateTag = (state: string): string => {
    if (state === 'resolved') return 'RESOLVED';
    if (state === 'suppressed') return 'SUPPRESSED';
    return 'FIRING';
  };

  return (
    <div className="alert-panel">
      <div className="panel-header">
        <span className="panel-title">ALERT LOG</span>
        <span className="panel-count">{alerts.filter(a => a.state === 'active').length} ACTIVE</span>
      </div>
      <div className="alert-list" ref={panelRef}>
        {alerts.length === 0 ? (
          <div className="alert-empty">NO ALERTS — ALL SYSTEMS NOMINAL</div>
        ) : (
          alerts.map((alert) => (
            <div
              key={alert.id}
              className="alert-entry"
              style={{ borderLeftColor: severityColor(alert.severity, alert.state) }}
            >
              <div className="alert-top">
                <span className="alert-name">{alert.alertname}</span>
                <span className="alert-state" style={{ color: severityColor(alert.severity, alert.state) }}>
                  {stateTag(alert.state)}
                </span>
              </div>
              <div className="alert-meta">
                <span className="alert-sev" style={{ color: severityColor(alert.severity, alert.state) }}>
                  [{alert.state === 'resolved' ? 'RESOLVED' : alert.severity.toUpperCase()}]
                </span>
                <span className="alert-time">
                  {new Date(alert.timestamp).toLocaleTimeString('en-US', { hour12: false })}
                </span>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// HexMap — SVG-based infrastructure map
// ---------------------------------------------------------------------------

interface HexMapProps {
  services: Record<ServiceId, ServiceState>;
  alerts: AlertEntry[];
}

function HexMap({ services, alerts }: HexMapProps) {
  const [transform, setTransform] = useState({ x: 0, y: 0, scale: 1 });
  const [isDragging, setIsDragging] = useState(false);
  const dragging = useRef(false);
  const lastMouse = useRef({ x: 0, y: 0 });
  const containerRef = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);

  // Draggable hex block state
  const [hexPositions, setHexPositions] = useState<Record<ServiceId, [number, number]>>(
    () => ({ ...SERVICE_AXIAL })
  );
  const [draggingBlock, setDraggingBlock] = useState<{ id: ServiceId; x: number; y: number } | null>(null);

  // Issue 5 — hovered hex state
  const [hoveredHex, setHoveredHex] = useState<ServiceId | null>(null);

  // Center the hex cluster on mount
  useEffect(() => {
    if (!containerRef.current) return;
    const { width, height } = containerRef.current.getBoundingClientRect();
    setTransform({ x: width / 2, y: height / 2, scale: 1 });
  }, []);

  // Non-passive wheel listener so preventDefault() actually works,
  // preventing the page from scrolling when the mouse is over the map.
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;
    const handleWheel = (e: WheelEvent) => {
      e.preventDefault();
      const delta = e.deltaY > 0 ? -0.05 : 0.05;
      setTransform(t => ({
        ...t,
        scale: Math.min(3, Math.max(0.4, t.scale + delta)),
      }));
    };
    svg.addEventListener('wheel', handleWheel, { passive: false });
    return () => svg.removeEventListener('wheel', handleWheel);
  }, []);

  const activeAlertNames = new Set(
    alerts.filter(a => a.state === 'active').map(a => a.alertname.toLowerCase())
  );

  const serviceHasAlert = (id: ServiceId): boolean => {
    if (id === 'lab-rat') {
      return [...activeAlertNames].some(n =>
        n.includes('lab') || n.includes('rat') || n.includes('heap') ||
        n.includes('memory') || n.includes('cpu') || n.includes('jvm')
      );
    }
    return false;
  };

  // Compute live pixel centers from hexPositions state
  const liveCenters: Record<ServiceId, { x: number; y: number }> = Object.fromEntries(
    (Object.keys(hexPositions) as ServiceId[]).map(id => {
      const [q, r] = hexPositions[id];
      return [id, axialToPixel(q, r)];
    })
  ) as Record<ServiceId, { x: number; y: number }>;

  // ---------------------------------------------------------------------------
  // Issue 4 + Issue 5 — getHexStyle with subtle glow and hover/drag variants
  // ---------------------------------------------------------------------------

  const getHexStyle = (id: ServiceId, isHovered: boolean, isDraggingThis: boolean): HexStyle => {
    const cfg = SERVICE_CONFIG[id];
    const status = services[id].status;
    const hasAlert = serviceHasAlert(id);

    if (status === 'DOWN') {
      return {
        fill: '#0a0a0a',
        stroke: '#30363d',
        strokeWidth: 1,
        strokeOpacity: 1,
        textColor: '#6e7681',
        dotColor: '#6e7681',
        glowFilter: 'saturate(0.25) brightness(0.6)',
        innerGlow: false,
      };
    }

    if (status === 'UNKNOWN') {
      return {
        fill: cfg.fill,
        stroke: cfg.borderBase,
        strokeWidth: 1.5,
        strokeOpacity: 1,
        textColor: '#7d8590',
        dotColor: '#6e7681',
        glowFilter: '',
        innerGlow: false,
      };
    }

    // UP — alert state
    if (hasAlert) {
      let glowFilter: string;
      if (isDraggingThis) {
        glowFilter = `drop-shadow(0 0 8px #d29922) drop-shadow(0 8px 20px rgba(0,0,0,0.7))`;
      } else if (isHovered) {
        glowFilter = `drop-shadow(0 0 5px #d2992270) drop-shadow(0 3px 8px rgba(0,0,0,0.6))`;
      } else {
        glowFilter = `drop-shadow(0 0 5px #d2992270)`;
      }
      return {
        fill: '#1a1100',
        stroke: '#d29922',
        strokeWidth: isDraggingThis ? 2.5 : 1.5,
        strokeOpacity: 0.4,
        textColor: '#e6edf3',
        dotColor: '#d29922',
        glowFilter,
        innerGlow: true,
        innerGlowColor: '#d29922',
      };
    }

    // UP — normal state
    let glowFilter: string;
    if (isDraggingThis) {
      glowFilter = `drop-shadow(0 0 8px ${cfg.accent}) drop-shadow(0 8px 20px rgba(0,0,0,0.7))`;
    } else if (isHovered) {
      glowFilter = `drop-shadow(0 0 5px ${cfg.accent}B3) drop-shadow(0 3px 8px rgba(0,0,0,0.6))`;
    } else {
      glowFilter = `drop-shadow(0 0 5px ${cfg.accent}8C)`;
    }

    return {
      fill: cfg.fill,
      stroke: cfg.accent,
      strokeWidth: isDraggingThis ? 2.5 : 1.5,
      strokeOpacity: 0.7,
      textColor: '#e6edf3',
      dotColor: cfg.accent,
      glowFilter,
      innerGlow: true,
      innerGlowColor: cfg.accent,
    };
  };

  // Convert client mouse coords to SVG content space (accounting for pan/zoom)
  const toSvgCoords = (clientX: number, clientY: number) => {
    const rect = svgRef.current!.getBoundingClientRect();
    return {
      x: (clientX - rect.left - transform.x) / transform.scale,
      y: (clientY - rect.top - transform.y) / transform.scale,
    };
  };

  // Occupied axial set
  const occupiedAxial = new Set(
    (Object.values(hexPositions) as [number, number][]).map(([q, r]) => `${q},${r}`)
  );

  // All background grid hexes not occupied — candidate snap targets
  const allGridHexes: { q: number; r: number; x: number; y: number }[] = [];
  for (let ri = 0; ri < 9; ri++) {
    for (let qi = 0; qi < 13; qi++) {
      const q = qi - 6;
      const r = ri - 4;
      if (!occupiedAxial.has(`${q},${r}`)) {
        const { x, y } = axialToPixel(q, r);
        allGridHexes.push({ q, r, x, y });
      }
    }
  }

  // Empty hexes near drag position (within 150px)
  const emptyNearbyHexes = draggingBlock
    ? allGridHexes.filter(h => Math.hypot(h.x - draggingBlock.x, h.y - draggingBlock.y) < 150)
    : [];

  // Connection flow color
  const getFlowColor = (a: ServiceId, b: ServiceId): { flowColor: string; dimColor: string; hasAlert: boolean } => {
    const aDown = services[a].status === 'DOWN';
    const bDown = services[b].status === 'DOWN';
    const alert = serviceHasAlert(a) || serviceHasAlert(b);

    if (aDown || bDown) {
      return { flowColor: '#6e7681', dimColor: '#30363d', hasAlert: false };
    }
    if (alert) {
      return { flowColor: '#d29922', dimColor: '#d29922', hasAlert: true };
    }
    return { flowColor: SERVICE_CONFIG[a].accent, dimColor: SERVICE_CONFIG[a].accent, hasAlert: false };
  };

  // ---------------------------------------------------------------------------
  // Render connection geometry — obstacle-aware paths for all connections
  // ---------------------------------------------------------------------------

  const portMap = assignConnectionPorts(CONNECTIONS, liveCenters);

  const renderConnection = (a: ServiceId, b: ServiceId) => {
    const port = portMap.get(`${a}-${b}`);
    const path = computeConnectionPath(
      a, b, liveCenters,
      port?.startPt ?? undefined,
      port?.endPt   ?? undefined
    );
    if (!path) return null;
    const { flowColor, dimColor, hasAlert } = getFlowColor(a, b);
    const markerSuffix = hasAlert ? 'alert' : a;
    return {
      key: `${a}-${b}`,
      d: path.d,
      endX: path.endX,
      endY: path.endY,
      flowColor,
      dimColor,
      hasAlert,
      markerSuffix,
    };
  };

  const connectionData = CONNECTIONS.map(([a, b]) => renderConnection(a, b)).filter(Boolean);

  return (
    <div className="svg-container" ref={containerRef}>
      <svg
        ref={svgRef}
        width="100%"
        height="100%"
        style={{ cursor: draggingBlock ? 'grabbing' : (isDragging ? 'grabbing' : 'grab'), userSelect: 'none' }}
        onMouseDown={(e) => {
          dragging.current = true;
          setIsDragging(true);
          lastMouse.current = { x: e.clientX, y: e.clientY };
        }}
        onMouseMove={(e) => {
          if (draggingBlock) {
            const svgPos = toSvgCoords(e.clientX, e.clientY);
            setDraggingBlock(prev => prev ? { ...prev, x: svgPos.x, y: svgPos.y } : null);
            return;
          }
          if (!dragging.current) return;
          const dx = e.clientX - lastMouse.current.x;
          const dy = e.clientY - lastMouse.current.y;
          lastMouse.current = { x: e.clientX, y: e.clientY };
          setTransform(t => ({ ...t, x: t.x + dx, y: t.y + dy }));
        }}
        onMouseUp={(e) => {
          if (draggingBlock) {
            const svgPos = toSvgCoords(e.clientX, e.clientY);
            let nearest: { q: number; r: number } | null = null;
            let nearestDist = 60;
            for (const h of allGridHexes) {
              const dist = Math.hypot(h.x - svgPos.x, h.y - svgPos.y);
              if (dist < nearestDist) {
                nearestDist = dist;
                nearest = { q: h.q, r: h.r };
              }
            }
            if (nearest) {
              const { q, r } = nearest;
              setHexPositions(prev => ({ ...prev, [draggingBlock.id]: [q, r] }));
            }
            setDraggingBlock(null);
            dragging.current = false;
            setIsDragging(false);
            return;
          }
          dragging.current = false;
          setIsDragging(false);
        }}
        onMouseLeave={() => {
          if (draggingBlock) {
            setDraggingBlock(null);
          }
          dragging.current = false;
          setIsDragging(false);
        }}
      >
        <defs>
          <filter id="glow-green" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="3" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
          <filter id="glow-amber" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="3" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
          {/* Arrowhead markers — one per service accent color */}
          {(Object.keys(SERVICE_CONFIG) as ServiceId[]).map(id => (
            <marker key={id} id={`arrow-${id}`} markerWidth="6" markerHeight="4"
              refX="6" refY="2" orient="auto">
              <polygon points="0,0 6,2 0,4" fill={SERVICE_CONFIG[id].accent} />
            </marker>
          ))}
          <marker id="arrow-alert" markerWidth="6" markerHeight="4" refX="6" refY="2" orient="auto">
            <polygon points="0,0 6,2 0,4" fill="#d29922" />
          </marker>
        </defs>

        {/*
          Issue 1 fix — strict layer ordering (SVG document order = painter's algorithm):
          1. Background hex grid
          2. Snap target highlights
          3. Connection BASE lines (static, dim) — under hex fills
          4. Hex FILLS only (no stroke)
          5. Hex STROKES only (fill:none) — on top of fills
          6. Connection FLOW dashes (animated) — on top of hex borders
          7. Hex LABELS (text + status dot) — always topmost
          [drag ghost rendered last, outside transform group]
        */}
        <g transform={`translate(${transform.x}, ${transform.y}) scale(${transform.scale})`}>

          {/* ── Layer 1: Background hex grid ── */}
          <g opacity={0.25}>
            {Array.from({ length: 9 }, (_, ri) => ri - 4).map(r =>
              Array.from({ length: 13 }, (_, qi) => qi - 6).map(q => {
                const { x, y } = axialToPixel(q, r);
                return (
                  <polygon
                    key={`bg-${q}-${r}`}
                    points={hexVertices(x, y, HEX_SIZE)}
                    fill="none"
                    stroke="#30363d"
                    strokeWidth={0.6}
                  />
                );
              })
            )}
          </g>

          {/* ── Layer 2: Snap target highlights ── */}
          {draggingBlock && emptyNearbyHexes.map(({ q, r, x, y }) => (
            <polygon
              key={`snap-${q}-${r}`}
              points={hexVertices(x, y, HEX_SIZE)}
              fill="none"
              stroke="#58a6ff"
              strokeWidth={1.5}
              strokeDasharray="6 4"
              opacity={0.65}
              className="snap-target-pulse"
            />
          ))}

          {/* ── Layer 3: Connection BASE lines (static, dim) ── */}
          <g>
            {connectionData.map(conn => {
              if (!conn) return null;
              return (
                <path
                  key={`base-${conn.key}`}
                  d={conn.d}
                  stroke={conn.dimColor}
                  strokeWidth={1}
                  opacity={0.25}
                  fill="none"
                />
              );
            })}
          </g>

          {/* ── Layer 4: Hex FILLS only (polygon fill, no stroke) ── */}
          <g>
            {(Object.keys(SERVICE_AXIAL) as ServiceId[]).map((id) => {
              const isDraggingThis = draggingBlock?.id === id;
              // Skip the dragged hex here — it renders in the overlay at the end
              if (isDraggingThis) return null;
              const isHovered = hoveredHex === id;
              const center = liveCenters[id];
              const { x: cx, y: cy } = center;
              const style = getHexStyle(id, isHovered, false);

              // Issue 5 — y-lift on hover
              const ty = isHovered ? cy - 3 : cy;

              return (
                <polygon
                  key={`fill-${id}`}
                  points={hexVertices(cx, ty, HEX_SIZE)}
                  fill={style.fill}
                  stroke="none"
                />
              );
            })}
          </g>

          {/* ── Layer 5: Hex STROKES only (fill:none, stroke only) ── */}
          <g>
            {(Object.keys(SERVICE_AXIAL) as ServiceId[]).map((id) => {
              const isDraggingThis = draggingBlock?.id === id;
              if (isDraggingThis) return null;
              const isHovered = hoveredHex === id;
              const center = liveCenters[id];
              const { x: cx, y: cy } = center;
              const style = getHexStyle(id, isHovered, false);
              const ty = isHovered ? cy - 3 : cy;

              return (
                <g key={`stroke-${id}`}>
                  <polygon
                    points={hexVertices(cx, ty, HEX_SIZE)}
                    fill="none"
                    stroke={style.stroke}
                    strokeWidth={style.strokeWidth}
                    strokeOpacity={style.strokeOpacity}
                  />
                  {/* Inner glow overlay on stroke layer */}
                  {style.innerGlow && (
                    <polygon
                      points={hexVertices(cx, ty, HEX_SIZE)}
                      fill="none"
                      stroke={style.innerGlowColor}
                      strokeWidth={6}
                      opacity={0.12}
                      strokeLinejoin="round"
                    />
                  )}
                </g>
              );
            })}
          </g>

          {/* ── Layer 6: Connection FLOW dashes (animated, on top of hex borders) ── */}
          <g>
            {connectionData.map(conn => {
              if (!conn) return null;

              return (
                <g key={`flow-${conn.key}`} className={conn.hasAlert ? 'border-pulse' : ''}>
                  <path
                    d={conn.d}
                    stroke={conn.flowColor}
                    strokeWidth={2.5}
                    strokeLinecap="round"
                    strokeDasharray="4 8"
                    className="flow-dash"
                    markerEnd={`url(#arrow-${conn.markerSuffix})`}
                    fill="none"
                  />
                </g>
              );
            })}
          </g>

          {/* ── Layer 7: Hex LABELS (text + status dot) — always topmost ── */}
          {/*
            The invisible hit-area polygon here captures mouse events for hover/drag.
            We also attach the filter (glow) here so it applies to the whole hex group
            but the label text stays crisp above everything.
          */}
          <g>
            {(Object.keys(SERVICE_AXIAL) as ServiceId[]).map((id) => {
              const cfg = SERVICE_CONFIG[id];
              const isDraggingThis = draggingBlock?.id === id;
              if (isDraggingThis) return null;
              const isHovered = hoveredHex === id;
              const center = liveCenters[id];
              const { x: cx, y: cy } = center;
              const style = getHexStyle(id, isHovered, false);
              const status = services[id].status;
              const ty = isHovered ? cy - 3 : cy;

              return (
                <g
                  key={`label-${id}`}
                  className="hex-cell-group"
                  style={{ filter: style.glowFilter, transition: 'filter 200ms ease' }}
                  onMouseEnter={() => {
                    if (!draggingBlock) setHoveredHex(id);
                  }}
                  onMouseLeave={() => setHoveredHex(null)}
                  onMouseDown={(e) => {
                    e.stopPropagation();
                    dragging.current = false;
                    setIsDragging(false);
                    setHoveredHex(null);
                    const svgPos = toSvgCoords(e.clientX, e.clientY);
                    setDraggingBlock({ id, x: svgPos.x, y: svgPos.y });
                  }}
                >
                  {/* Invisible hit area — full hex shape for reliable mouse events */}
                  <polygon
                    points={hexVertices(cx, ty, HEX_SIZE)}
                    fill="transparent"
                    stroke="none"
                    style={{ cursor: 'grab' }}
                  />
                  {/* Service name */}
                  <text
                    x={cx}
                    y={ty - 8}
                    textAnchor="middle"
                    dominantBaseline="middle"
                    fontSize={11}
                    fontFamily="Inter, system-ui, sans-serif"
                    fontWeight={500}
                    fill={style.textColor}
                    style={{ pointerEvents: 'none' }}
                  >
                    {cfg.label}
                  </text>
                  {/* Port */}
                  <text
                    x={cx}
                    y={ty + 10}
                    textAnchor="middle"
                    dominantBaseline="middle"
                    fontSize={10}
                    fontFamily="'JetBrains Mono', 'Courier New', monospace"
                    fill="#7d8590"
                    style={{ pointerEvents: 'none' }}
                  >
                    :{cfg.port}
                  </text>
                  {/* Status dot */}
                  <circle
                    cx={cx}
                    cy={ty + 26}
                    r={4}
                    fill={style.dotColor}
                    className={status === 'DOWN' ? 'dot-pulse' : ''}
                    style={{ pointerEvents: 'none' }}
                  />
                </g>
              );
            })}
          </g>

          {/* ── Drag ghost overlay — dragged hex renders last (topmost) ── */}
          {draggingBlock && (() => {
            const id = draggingBlock.id;
            const cfg = SERVICE_CONFIG[id];
            const style = getHexStyle(id, false, true);
            const status = services[id].status;
            const { x: gx, y: gy } = { x: draggingBlock.x, y: draggingBlock.y - 6 };

            return (
              <g
                key={`ghost-${id}`}
                style={{
                  filter: style.glowFilter,
                  cursor: 'grabbing',
                  opacity: 0.92,
                }}
              >
                {/* Fill */}
                <polygon
                  points={hexVertices(gx, gy, HEX_SIZE * 1.08)}
                  fill={style.fill}
                  stroke="none"
                />
                {/* Stroke */}
                <polygon
                  points={hexVertices(gx, gy, HEX_SIZE * 1.08)}
                  fill="none"
                  stroke={style.stroke}
                  strokeWidth={style.strokeWidth}
                  strokeOpacity={style.strokeOpacity}
                />
                {/* Inner glow */}
                {style.innerGlow && (
                  <polygon
                    points={hexVertices(gx, gy, HEX_SIZE * 1.08)}
                    fill="none"
                    stroke={style.innerGlowColor}
                    strokeWidth={6}
                    opacity={0.15}
                    strokeLinejoin="round"
                  />
                )}
                {/* Label */}
                <text
                  x={gx}
                  y={gy - 8}
                  textAnchor="middle"
                  dominantBaseline="middle"
                  fontSize={11}
                  fontFamily="Inter, system-ui, sans-serif"
                  fontWeight={500}
                  fill={style.textColor}
                  style={{ pointerEvents: 'none' }}
                >
                  {cfg.label}
                </text>
                <text
                  x={gx}
                  y={gy + 10}
                  textAnchor="middle"
                  dominantBaseline="middle"
                  fontSize={10}
                  fontFamily="'JetBrains Mono', 'Courier New', monospace"
                  fill="#7d8590"
                  style={{ pointerEvents: 'none' }}
                >
                  :{cfg.port}
                </text>
                <circle
                  cx={gx}
                  cy={gy + 26}
                  r={4}
                  fill={style.dotColor}
                  className={status === 'DOWN' ? 'dot-pulse' : ''}
                  style={{ pointerEvents: 'none' }}
                />
              </g>
            );
          })()}

        </g>
      </svg>

      {/* Zoom controls — positioned absolute within the svg-container */}
      <div className="zoom-controls" style={{ userSelect: 'none' }}>
        <button
          className="zoom-btn"
          onClick={() => setTransform(t => ({ ...t, scale: Math.max(0.4, t.scale - 0.25) }))}
          aria-label="Zoom out"
        >
          −
        </button>
        <span className="zoom-level">{Math.round(transform.scale * 100)}%</span>
        <button
          className="zoom-btn"
          onClick={() => setTransform(t => ({ ...t, scale: Math.min(3, t.scale + 0.25) }))}
          aria-label="Zoom in"
        >
          +
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// App — main layout
// ---------------------------------------------------------------------------

export default function App() {
  const { services, heapMB, alerts, lastPoll } = useCommandRoom();

  const upCount = Object.values(services).filter(s => s.status === 'UP').length;
  const totalCount = Object.values(services).length;
  const systemHealthy = upCount === totalCount;

  const hc = heapColor(heapMB);

  return (
    <div className="command-room">

      {/* Top HUD bar */}
      <header className="hud-bar">
        <div className="hud-left">
          <span className="hud-logo">PROJECT SENTINEL</span>
          <span className="hud-sub">SRE COMMAND ROOM v2.0</span>
        </div>
        <div className="hud-center">
          <div className={`sys-status ${systemHealthy ? 'status-ok' : 'status-degraded'}`}>
            <span className="sys-status-dot" />
            <span>{systemHealthy ? 'ALL SYSTEMS OPERATIONAL' : `${upCount}/${totalCount} SERVICES UP`}</span>
          </div>
        </div>
        <div className="hud-right">
          <span className="hud-label">LAST SYNC</span>
          <span className="hud-value">
            {lastPoll > 0 ? new Date(lastPoll).toLocaleTimeString('en-US', { hour12: false }) : '--:--:--'}
          </span>
          <span className="hud-sep" />
          <SystemClock />
        </div>
      </header>

      {/* Main content: SVG map + alert panel */}
      <div className="main-layout">
        <HexMap services={services} alerts={alerts} />
        <AlertPanel alerts={alerts} />
      </div>

      {/* Bottom status bar */}
      <footer className="status-bar">
        {Object.entries(services).map(([id, svc]) => {
          const cfg = SERVICE_CONFIG[id as ServiceId];
          const isUp = svc.status === 'UP';
          const isLabRat = id === 'lab-rat';
          return (
            <div key={id} className="status-bar-item">
              <span className={`status-bar-dot ${isUp ? 'sbar-dot-up' : 'sbar-dot-down'}`} />
              <span className="status-bar-name">{cfg.label}</span>
              <span className={`status-bar-state ${isUp ? 'state-up' : 'state-down'}`}>
                {svc.status}
              </span>
              {isLabRat && (
                <span className="heap-reading" style={{ color: hc }}>
                  HEAP: {heapMB != null ? `${heapMB.toFixed(0)}MB` : '--MB'}
                </span>
              )}
            </div>
          );
        })}
        <div className="status-bar-item status-bar-poll">
          <span className="poll-indicator" />
          POLLING 3s
        </div>
      </footer>
    </div>
  );
}
