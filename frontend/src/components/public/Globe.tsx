/**
 * Decorative night-side globe behind the dashboard preview: SVG gradients, a gold rim light and dotted "city
 * lights" generated once from a seeded PRNG (no raster images, no runtime cost after the first render).
 */

const SIZE = 600;
const R = 286;
const C = SIZE / 2;
/** Room around the sphere for the rim glow and atmosphere, so the CSS fade mask never clips them. */
const PAD = 90;
// View centred east of the lit continents so Europe / Africa / Russia land on the visible upper-left limb.
const VIEW_LAT = (16 * Math.PI) / 180;
const VIEW_LON = (52 * Math.PI) / 180;

function mulberry32(seed: number) {
  let state = seed;
  return () => {
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface Dot {
  x: number;
  y: number;
  r: number;
  o: number;
}

/** Orthographic projection; returns null on the far side. */
function project(latDeg: number, lonDeg: number): { x: number; y: number; depth: number } | null {
  const lat = (latDeg * Math.PI) / 180;
  const lon = (lonDeg * Math.PI) / 180;
  const cosC = Math.sin(VIEW_LAT) * Math.sin(lat) + Math.cos(VIEW_LAT) * Math.cos(lat) * Math.cos(lon - VIEW_LON);
  if (cosC <= 0.04) return null;
  const x = R * Math.cos(lat) * Math.sin(lon - VIEW_LON);
  const y = R * (Math.cos(VIEW_LAT) * Math.sin(lat) - Math.sin(VIEW_LAT) * Math.cos(lat) * Math.cos(lon - VIEW_LON));
  return { x: C + x, y: C - y, depth: cosC };
}

/** Brighter towards the lit upper-left limb. */
function lightAt(x: number, y: number): number {
  const dx = (x - C) / R;
  const dy = (y - C) / R;
  return Math.max(0, Math.min(1, 0.55 - 0.55 * dx - 0.45 * dy));
}

const CLUSTERS: [lat: number, lon: number, spread: number, count: number][] = [
  [51, 6, 8, 120],
  [47, 18, 7, 80],
  [41, -4, 5, 40],
  [43, 12, 4, 30],
  [59, 16, 6, 40],
  [56, 38, 9, 70],
  [61, 62, 12, 45],
  [64, 92, 14, 30],
  [40, 30, 5, 40],
  [33, 0, 6, 40],
  [30, 31, 3, 40],
  [9, -2, 7, 45],
  [7, 7, 4, 30],
  [-2, 34, 7, 30],
  [-26, 28, 5, 24],
  [31, 46, 7, 50],
  [24, 76, 7, 70],
  [13, 79, 5, 30],
];

function buildDots(): { lights: Dot[]; grid: Dot[] } {
  const random = mulberry32(360);
  const lights: Dot[] = [];
  for (const [lat, lon, spread, count] of CLUSTERS) {
    for (let i = 0; i < count; i += 1) {
      const radius = spread * Math.sqrt(random());
      const angle = random() * Math.PI * 2;
      const point = project(lat + radius * Math.sin(angle), lon + radius * Math.cos(angle) * 1.3);
      if (!point) continue;
      const light = lightAt(point.x, point.y);
      lights.push({
        x: point.x,
        y: point.y,
        r: (0.5 + random() * random() * 1.9) * (0.55 + point.depth * 0.45),
        o: Math.min(1, 0.3 + light * 0.7 + random() * 0.25) * (0.3 + point.depth * 0.7),
      });
    }
  }
  const grid: Dot[] = [];
  for (let lat = -45; lat <= 45; lat += 15) {
    for (let lon = -180; lon < 180; lon += 5) {
      const point = project(lat, lon);
      if (point) grid.push({ x: point.x, y: point.y, r: 0.6, o: 0.03 + lightAt(point.x, point.y) * 0.1 });
    }
  }
  return { lights, grid };
}

const DOTS = buildDots();

export function Globe({ className = '' }: { className?: string }) {
  return (
    <div className={`pub-globe ${className}`.trim()} aria-hidden="true">
      <svg viewBox={`${-PAD} ${-PAD} ${SIZE + PAD * 2} ${SIZE + PAD * 2}`} focusable="false">
        <defs>
          <radialGradient id="pg-atmo" cx="30%" cy="28%" r="56%">
            <stop offset="0" stopColor="#D6B77A" stopOpacity="0.22" />
            <stop offset="1" stopColor="#D6B77A" stopOpacity="0" />
          </radialGradient>
          <radialGradient id="pg-body" cx="34%" cy="28%" r="78%">
            <stop offset="0" stopColor="#3b2a16" />
            <stop offset="0.32" stopColor="#1a130b" />
            <stop offset="0.68" stopColor="#0b0908" />
            <stop offset="1" stopColor="#050505" />
          </radialGradient>
          <linearGradient id="pg-rim" x1="0.08" y1="0.1" x2="0.78" y2="0.86">
            <stop offset="0" stopColor="#F1DFBA" stopOpacity="1" />
            <stop offset="0.3" stopColor="#D6B77A" stopOpacity="0.85" />
            <stop offset="0.58" stopColor="#96764B" stopOpacity="0.18" />
            <stop offset="1" stopColor="#96764B" stopOpacity="0" />
          </linearGradient>
          <radialGradient id="pg-limb" cx="22%" cy="20%" r="62%">
            <stop offset="0" stopColor="#F1DFBA" stopOpacity="0.34" />
            <stop offset="0.35" stopColor="#D6B77A" stopOpacity="0.12" />
            <stop offset="1" stopColor="#D6B77A" stopOpacity="0" />
          </radialGradient>
          <filter id="pg-blur" x="-20%" y="-20%" width="140%" height="140%">
            <feGaussianBlur stdDeviation="10" />
          </filter>
          <clipPath id="pg-clip">
            <circle cx={C} cy={C} r={R} />
          </clipPath>
        </defs>
        <circle cx={C} cy={C} r={R + PAD - 4} fill="url(#pg-atmo)" />
        <circle cx={C} cy={C} r={R + 6} fill="none" stroke="url(#pg-rim)" strokeWidth="24" filter="url(#pg-blur)" />
        <circle cx={C} cy={C} r={R} fill="url(#pg-body)" />
        <circle cx={C} cy={C} r={R} fill="url(#pg-limb)" />
        <g clipPath="url(#pg-clip)">
          <g fill="#D6B77A">
            {DOTS.grid.map((dot, index) => (
              <circle key={`g${index}`} cx={dot.x.toFixed(1)} cy={dot.y.toFixed(1)} r={dot.r} opacity={dot.o.toFixed(2)} />
            ))}
          </g>
          <g className="pub-globe-lights" fill="#F1DFBA">
            {DOTS.lights.map((dot, index) => (
              <circle key={`l${index}`} cx={dot.x.toFixed(1)} cy={dot.y.toFixed(1)} r={dot.r.toFixed(2)} opacity={dot.o.toFixed(2)} />
            ))}
          </g>
        </g>
        <circle cx={C} cy={C} r={R - 0.5} fill="none" stroke="url(#pg-rim)" strokeWidth="2.2" />
      </svg>
    </div>
  );
}
