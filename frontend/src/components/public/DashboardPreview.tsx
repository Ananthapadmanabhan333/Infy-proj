import { AlertTriangle, ArrowDown, ArrowUp, Bell, ChevronDown, Layers, Plus, Search, ShieldCheck, Zap, type LucideIcon } from 'lucide-react';
import { NAV_GROUPS, type NavGroup } from '../../lib/nav';
import { BrandLockup } from '../layout/BrandMark';
import { ILLUSTRATIVE_PREVIEW_DATA as DATA, type PreviewKpi, type PreviewService } from './previewData';

const KPI_ICONS: Record<PreviewKpi['key'], LucideIcon> = {
  total: Layers,
  critical: AlertTriangle,
  sla: ShieldCheck,
  mttr: Zap,
};

// ---------------------------------------------------------------- trend chart geometry (SVG user units)
const CHART = { width: 420, height: 188, left: 34, right: 412, top: 10, bottom: 156 };
const SLOT = (CHART.right - CHART.left - 18) / DATA.trend.days.length;
const xAt = (index: number) => CHART.left + 12 + index * SLOT;
const yAt = (value: number) => CHART.bottom - (value / DATA.trend.max) * (CHART.bottom - CHART.top);
const points = (values: readonly number[]) => values.map((value, index) => `${xAt(index).toFixed(1)},${yAt(value).toFixed(1)}`);
const TICKS = [0, 50, 100, 150, 200, 250];

function TrendChart() {
  const { created, resolved, days, forecast } = DATA.trend;
  const createdPoints = points(created);
  const last = created.length - 1;
  const areaPath = `M${createdPoints.join(' L')} L${xAt(last).toFixed(1)},${CHART.bottom} L${xAt(0).toFixed(1)},${CHART.bottom} Z`;
  return (
    <svg className="pd-trend-svg" viewBox={`0 0 ${CHART.width} ${CHART.height}`} preserveAspectRatio="none" focusable="false">
      <defs>
        <linearGradient id="pd-area" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#D6B77A" stopOpacity="0.42" />
          <stop offset="1" stopColor="#D6B77A" stopOpacity="0" />
        </linearGradient>
      </defs>
      {TICKS.map((tick) => (
        <g key={tick}>
          <line x1={CHART.left} x2={CHART.right} y1={yAt(tick)} y2={yAt(tick)} className="pd-grid" />
          <text x={CHART.left - 8} y={yAt(tick) + 3.5} className="pd-axis" textAnchor="end">
            {tick}
          </text>
        </g>
      ))}
      {days.map((day, index) => (
        <text key={day} x={xAt(index)} y={CHART.height - 8} className="pd-axis" textAnchor="middle">
          {day}
        </text>
      ))}
      <path d={areaPath} fill="url(#pd-area)" className="pd-area" />
      <polyline points={points(resolved).join(' ')} className="pd-line resolved" pathLength={1} />
      <polyline points={createdPoints.join(' ')} className="pd-line created" pathLength={1} />
      <line x1={xAt(last)} y1={yAt(created[last])} x2={xAt(last + 0.85)} y2={yAt(forecast)} className="pd-line forecast" />
      {created.map((value, index) => (
        <circle key={index} cx={xAt(index)} cy={yAt(value)} r={2.6} className="pd-dot" style={{ animationDelay: `${0.5 + index * 0.12}s` }} />
      ))}
    </svg>
  );
}

/** Preview sidebar: the real nav groups (available items only, the first three per group, no administration). */
const PREVIEW_NAV: NavGroup[] = NAV_GROUPS.filter((group) => group.id !== 'administration')
  .map((group) => ({ ...group, items: group.items.filter((item) => item.available).slice(0, 3) }))
  .filter((group) => group.items.length > 0);

const SERVICE_STATUS_LABELS: Record<PreviewService['status'], string> = {
  operational: 'Operational',
  degraded: 'Degraded',
  maintenance: 'Maintenance',
};

const DAY_CLASS: Record<string, string> = { u: 'up', d: 'degraded', o: 'outage', m: 'maintenance' };

function ServiceHealth() {
  return (
    <div className="pd-card pd-health">
      <div className="pd-card-head">
        <b>Service Health</b>
        <span className="pd-legend">Last 30 days</span>
      </div>
      <ul className="pd-health-list">
        {DATA.services.map((service) => (
          <li key={service.name}>
            <span className={`pd-health-dot ${service.status}`} />
            <span className="pd-health-name">{service.name}</span>
            <span className="pd-health-bars">
              {Array.from(service.days).map((day, index) => (
                <i key={index} className={DAY_CLASS[day]} />
              ))}
            </span>
            <em className={service.status}>{SERVICE_STATUS_LABELS[service.status]}</em>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ActivityFeed() {
  return (
    <div className="pd-card pd-activity">
      <div className="pd-card-head">
        <b>Operational Activity</b>
        <span className="pd-legend">Live feed</span>
      </div>
      <ul className="pd-activity-list">
        {DATA.activity.map((event) => (
          <li key={event.text}>
            <i className={event.tone} />
            <span>{event.text}</span>
            <small>{event.ago}</small>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Donut() {
  const total = DATA.distribution.reduce((sum, slice) => sum + slice.value, 0);
  let offset = 0;
  return (
    <div className="pd-donut">
      <svg viewBox="0 0 120 120" focusable="false">
        <circle cx="60" cy="60" r="46" className="pd-donut-track" />
        <g transform="rotate(-90 60 60)">
          {DATA.distribution.map((slice) => {
            const share = (slice.value / total) * 100;
            const gap = share > 3 ? 0.9 : 0.3;
            const segment = (
              <circle
                key={slice.label}
                cx="60"
                cy="60"
                r="46"
                pathLength={100}
                className="pd-donut-seg"
                stroke={slice.color}
                style={{ strokeDasharray: `${Math.max(share - gap, 0.4)} ${100 - Math.max(share - gap, 0.4)}`, strokeDashoffset: -offset }}
              />
            );
            offset += share;
            return segment;
          })}
        </g>
      </svg>
      <div className="pd-donut-center">
        <b>{total.toLocaleString('en-US')}</b>
        <small>Incidents</small>
      </div>
    </div>
  );
}

/**
 * Static, angled preview of the Operations Command Center (spec §6): React + CSS + inline SVG, sample data from
 * `ILLUSTRATIVE_PREVIEW_DATA`, no API calls and no chart library.
 */
export function DashboardPreview() {
  const total = DATA.distribution.reduce((sum, slice) => sum + slice.value, 0);
  return (
    <figure className="pd-figure" aria-labelledby="pd-caption">
      <div className="pd-stage">
        <div className="pd-frame">
          <div className="pd-dash" aria-hidden="true">
            <aside className="pd-side">
              <div className="pd-brand">
                <BrandLockup label={null} />
              </div>
              {PREVIEW_NAV.map((group, groupIndex) => (
                <div key={group.id} className="pd-side-group">
                  <p className="pd-side-label">{group.label}</p>
                  <ul>
                    {group.items.map((item, index) => {
                      const Icon = item.icon;
                      return (
                        <li key={item.to} className={groupIndex === 0 && index === 0 ? 'active' : ''}>
                          <Icon className="pd-nav-icon" strokeWidth={1.8} />
                          <span>{item.label}</span>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              ))}
            </aside>

            <div className="pd-main">
              <div className="pd-top">
                <div className="pd-search">
                  <Search className="pd-ico" />
                  <span>Search incidents, services, users, knowledge…</span>
                </div>
                <div className="pd-top-icons">
                  <Bell className="pd-ico" />
                  <Plus className="pd-ico" />
                </div>
                <div className="pd-user">
                  <span className="pd-avatar">AP</span>
                  <span>
                    <b>{DATA.user.displayName}</b>
                    <small>{DATA.user.role}</small>
                  </span>
                </div>
              </div>

              <div className="pd-head">
                <div>
                  <p className="pd-welcome">
                    Welcome back, {DATA.user.firstName} <span className="pd-wave">👋</span>
                  </p>
                  <p className="pd-title">Operations Command Center</p>
                  <p className="pd-sub">Real-time IT operations, incident intelligence and service health</p>
                </div>
                <span className="pd-period">
                  {DATA.period} <ChevronDown className="pd-ico sm" />
                </span>
              </div>

              <div className="pd-kpis">
                {DATA.kpis.map((kpi, index) => {
                  const Icon = KPI_ICONS[kpi.key];
                  const Arrow = kpi.direction === 'up' ? ArrowUp : ArrowDown;
                  return (
                    <div key={kpi.key} className={`pd-kpi ${kpi.key}`} style={{ animationDelay: `${0.25 + index * 0.08}s` }}>
                      <Icon className="pd-kpi-icon" strokeWidth={1.6} />
                      <div>
                        <small>{kpi.label}</small>
                        <b>{kpi.value}</b>
                        <em className={kpi.tone}>
                          <Arrow className="pd-ico xs" /> {kpi.delta}
                        </em>
                      </div>
                    </div>
                  );
                })}
              </div>

              <div className="pd-charts">
                <div className="pd-card pd-trend">
                  <div className="pd-card-head">
                    <b>Incident Trend &amp; Forecast</b>
                    <span className="pd-legend">
                      <i className="created" /> Created <i className="resolved" /> Resolved <i className="forecast" /> Forecast
                    </span>
                  </div>
                  <TrendChart />
                </div>
                <div className="pd-card pd-dist">
                  <div className="pd-card-head">
                    <b>Incident Distribution</b>
                  </div>
                  <div className="pd-dist-body">
                    <Donut />
                    <ul className="pd-dist-legend">
                      {DATA.distribution.map((slice) => (
                        <li key={slice.label}>
                          <i style={{ background: slice.color }} />
                          <span>{slice.label}</span>
                          <b>
                            {slice.value} ({Math.round((slice.value / total) * 100)}%)
                          </b>
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>
              </div>

              <div className="pd-ops">
                <ServiceHealth />
                <ActivityFeed />
              </div>
            </div>
          </div>
        </div>
      </div>
      <figcaption id="pd-caption" className="pd-caption">
        <span className="pd-badge">Demo preview · illustrative data</span>
        <span className="pub-sr-only">
          {' '}
          — an illustration of the Operations Command Center with sample KPIs, a seven-day incident trend with forecast,
          the incident distribution by priority, service health and an operational activity feed. It does not show live
          data.
        </span>
      </figcaption>
    </figure>
  );
}
