import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { CalendarClock, Download, KeyRound, Laptop, Package, PackageCheck, Plus, Upload } from 'lucide-react';
import { useAssets, useAssetStats, useExpiringAssets, type AssetListParams } from '../../api/assets';
import { UserChip } from '../../components/Avatar';
import { DemoBadge } from '../../components/Badge';
import { DataTable, type SortState } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { KpiCard } from '../../components/KpiCard';
import { AssetStatusBadge, WarrantyBadge } from '../../components/ops/OpsParts';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { FILTER_CLASS, FILTER_ROW_CLASS } from '../../components/servicedesk/requestModel';
import { useAuth } from '../../hooks/useAuth';
import { useSearchInput, useUrlFilters } from '../../hooks/useUrlState';
import { downloadFile } from '../../lib/api';
import { ASSET_EDITORS } from '../../lib/constants';
import { formatDate, formatNumber } from '../../lib/format';
import { ASSET_STATUS_LABELS, ASSET_STATUSES, ASSET_TYPE_LABELS, ASSET_TYPES, daysLeftLabel, formatMoney, WARRANTY_LABELS, WARRANTY_STATUSES } from '../../lib/ops';
import type { AssetSummary } from '../../lib/opsTypes';
import { AssetEditor } from './AssetEditor';
import { AssetImportModal } from './AssetImportModal';

const KEYS = ['search', 'type', 'status', 'category', 'warranty', 'location', 'sort'] as const;
const PAGE_SIZE = 20;

function parseSort(value: string): SortState | null {
  const [key, dir] = value.split(',');
  return key ? { key, dir: dir === 'desc' ? 'desc' : 'asc' } : null;
}

function ExpiringPanel() {
  const expiring = useExpiringAssets();
  return (
    <Panel title="Warranties and licences ending" subtitle="Active assets whose warranty or licence ends within the warning window, or has ended">
      {expiring.isLoading && <LoadingState rows={3} />}
      {expiring.isError && <ErrorState error={expiring.error} onRetry={() => void expiring.refetch()} compact />}
      {expiring.data && expiring.data.length === 0 && <EmptyState title="Nothing ending soon" description="No active warranty or licence ends within the window." icon={<CalendarClock size={18} />} />}
      {expiring.data && expiring.data.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Expiring warranties and licences">
          {expiring.data.map((item) => (
            <li key={`${item.kind}-${item.asset.id}`} className="list-row flex-wrap sm:flex-nowrap">
              <div className="min-w-0">
                <b className="flex flex-wrap items-center gap-1.5">
                  <Link to={`/assets/${item.asset.id}`} className="id-cell">
                    {item.asset.assetTag}
                  </Link>
                  <span className="break-words">{item.asset.name}</span>
                </b>
                <small className="block">
                  {item.kind === 'LICENSE' ? 'Licence' : 'Warranty'} ends {formatDate(item.expiresOn)}
                  {item.asset.assignedToName ? ` · ${item.asset.assignedToName}` : ''}
                  {item.asset.location ? ` · ${item.asset.location}` : ''}
                </small>
              </div>
              <ToneBadge value={item.expired ? 'EXPIRED' : 'EXPIRING'} label={daysLeftLabel(item.daysLeft, item.expired)} tone={item.expired ? 'critical' : 'warning'} />
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

/** /assets (OPERATIONS, readers): inventory KPIs, filters, expiring warranties, CSV export; asset editors create and import. */
export default function AssetsPage() {
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const canEdit = hasRole(...ASSET_EDITORS);
  const { values, setValues, page, setPage } = useUrlFilters(KEYS);
  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const [creating, setCreating] = useState(false);
  const [importing, setImporting] = useState(false);
  const [exporting, setExporting] = useState(false);
  const stats = useAssetStats();
  const sort = parseSort(values.sort) ?? { key: 'assetTag', dir: 'asc' };
  const filters: AssetListParams = {
    search: values.search || undefined,
    type: values.type || undefined,
    status: values.status || undefined,
    category: values.category || undefined,
    warranty: values.warranty || undefined,
    location: values.location || undefined,
  };
  const assets = useAssets({ ...filters, page, size: PAGE_SIZE, sort: `${sort.key},${sort.dir}` });
  const s = stats.data;
  const filtering = Object.values(filters).some(Boolean);

  const exportCsv = async () => {
    setExporting(true);
    try {
      await downloadFile('/assets/export', 'assets.csv', { ...filters });
      toast.success('Export downloaded');
    } catch (error) {
      toast.error(error);
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      <PageHeader
        title="Assets"
        subtitle="Hardware and software inventory through its lifecycle"
        actions={
          <>
            <button type="button" className="subtle-btn" onClick={() => void exportCsv()} disabled={exporting}>
              <Download size={14} aria-hidden="true" /> {exporting ? 'Exporting…' : 'Export CSV'}
            </button>
            {canEdit && (
              <button type="button" className="subtle-btn" onClick={() => setImporting(true)}>
                <Upload size={14} aria-hidden="true" /> Import CSV
              </button>
            )}
            {canEdit && (
              <button type="button" className="gold-btn" onClick={() => setCreating(true)}>
                <Plus size={16} aria-hidden="true" /> New asset
              </button>
            )}
          </>
        }
      />
      <div className="page-stack">
        {stats.isError && <ErrorState error={stats.error} onRetry={() => void stats.refetch()} compact />}
        <div className="kpi-grid">
          <KpiCard icon={<Package />} label="Assets" value={formatNumber(s?.total)} tone="gold" loading={stats.isLoading} caption={s ? `${formatNumber(s.hardware)} hardware · ${formatNumber(s.licences)} licences` : undefined} />
          <KpiCard
            icon={<Laptop />}
            label="In use"
            value={formatNumber(s?.byStatus.IN_USE ?? 0)}
            tone="success"
            loading={stats.isLoading}
            caption={s ? `${formatNumber(s.byStatus.IN_STOCK ?? 0)} in stock · ${formatNumber(s.byStatus.IN_REPAIR ?? 0)} in repair` : undefined}
          />
          <KpiCard
            icon={<KeyRound />}
            label="Licence seats used"
            value={s ? `${formatNumber(s.seatsUsed)} / ${formatNumber(s.seatsTotal)}` : '—'}
            tone={s && s.seatsTotal > 0 && s.seatsUsed >= s.seatsTotal ? 'warning' : 'neutral'}
            loading={stats.isLoading}
            caption={s ? (s.totalCost !== null ? `Inventory value ${formatMoney(s.totalCost, 'INR')}${s.mixedCurrencies ? ' (mixed currencies)' : ''}` : undefined) : undefined}
          />
          <KpiCard
            icon={<CalendarClock />}
            label="Ending soon"
            value={formatNumber(s?.expiringWithinWindow)}
            tone={s?.expired ? 'critical' : s?.expiringWithinWindow ? 'warning' : 'success'}
            loading={stats.isLoading}
            caption={s ? `Next ${s.warningDays} days · ${formatNumber(s.expired)} already ended` : undefined}
            to="/assets?warranty=EXPIRING"
          />
        </div>

        <div className="grid items-start gap-[14px] xl:grid-cols-[1.75fr_1fr]">
          <Panel aria-label="Asset list" className="min-w-0">
            <div className="toolbar">
              <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search by tag, name, serial, model or holder…" label="Search assets" />
              <div className={FILTER_ROW_CLASS}>
                <FilterSelect className={FILTER_CLASS} label="Type" value={values.type} onChange={(type) => setValues({ type })} options={ASSET_TYPES.map((value) => ({ value, label: ASSET_TYPE_LABELS[value] }))} />
                <FilterSelect className={FILTER_CLASS} label="Status" value={values.status} onChange={(status) => setValues({ status })} options={ASSET_STATUSES.map((value) => ({ value, label: ASSET_STATUS_LABELS[value] }))} />
                <FilterSelect
                  className={FILTER_CLASS}
                  label="Category"
                  value={values.category}
                  onChange={(category) => setValues({ category })}
                  options={[
                    { value: 'HARDWARE', label: 'Hardware' },
                    { value: 'SOFTWARE', label: 'Software' },
                  ]}
                />
                <FilterSelect className={FILTER_CLASS} label="Warranty" value={values.warranty} onChange={(warranty) => setValues({ warranty })} options={WARRANTY_STATUSES.map((value) => ({ value, label: WARRANTY_LABELS[value] }))} />
              </div>
            </div>
            {assets.isLoading && <LoadingState rows={6} label="Loading assets…" />}
            {assets.isError && <ErrorState error={assets.error} onRetry={() => void assets.refetch()} />}
            {assets.data && assets.data.content.length === 0 && (
              <EmptyState
                title={filtering ? 'No assets match these filters' : 'No assets yet'}
                description={canEdit && !filtering ? 'Add assets one by one, or import a CSV (download the template from the import dialog).' : undefined}
                icon={<PackageCheck size={20} />}
              />
            )}
            {assets.data && assets.data.content.length > 0 && (
              <>
                <DataTable<AssetSummary>
                  caption="Assets"
                  rows={assets.data.content}
                  rowKey={(asset) => asset.id}
                  rowLabel={(asset) => `${asset.assetTag} ${asset.name}`}
                  onRowClick={(asset) => navigate(`/assets/${asset.id}`)}
                  sort={sort}
                  onSortChange={(next) => setValues({ sort: `${next.key},${next.dir}` })}
                  columns={[
                    { key: 'tag', header: 'Tag', sortKey: 'assetTag', render: (asset) => <span className="id-cell">{asset.assetTag}</span> },
                    {
                      key: 'name',
                      header: 'Asset',
                      sortKey: 'name',
                      className: 'summary-cell',
                      render: (asset) => (
                        <span className="inline-flex min-w-0 flex-col">
                          <span className="inline-flex flex-wrap items-center gap-1.5">
                            <span className="break-words font-semibold">{asset.name}</span>
                            <DemoBadge demo={asset.demo} />
                          </span>
                          {(asset.manufacturer || asset.model) && <small className="text-muted">{[asset.manufacturer, asset.model].filter(Boolean).join(' ')}</small>}
                        </span>
                      ),
                    },
                    { key: 'type', header: 'Type', sortKey: 'assetType', render: (asset) => ASSET_TYPE_LABELS[asset.assetType] },
                    { key: 'status', header: 'Status', sortKey: 'status', render: (asset) => <AssetStatusBadge status={asset.status} /> },
                    {
                      key: 'holder',
                      header: 'Holder / seats',
                      render: (asset) =>
                        asset.category === 'SOFTWARE' ? (
                          <span>
                            {formatNumber(asset.seatsUsed)} / {formatNumber(asset.licenseSeats ?? 0)} seats
                          </span>
                        ) : (
                          <UserChip name={asset.assignedToName} emptyLabel="Unassigned" />
                        ),
                    },
                    { key: 'location', header: 'Location', render: (asset) => asset.location ?? asset.departmentName ?? '—' },
                    {
                      key: 'warranty',
                      header: 'Warranty / licence',
                      sortKey: 'warrantyExpires',
                      render: (asset) => {
                        const ends = asset.category === 'SOFTWARE' ? asset.licenseExpires : asset.warrantyExpires;
                        return ends ? (
                          <span className="inline-flex flex-wrap items-center gap-1">
                            {formatDate(ends)} <WarrantyBadge status={asset.warrantyStatus === 'ACTIVE' ? null : asset.warrantyStatus} />
                          </span>
                        ) : (
                          '—'
                        );
                      },
                    },
                    {
                      key: 'ci',
                      header: 'CI',
                      render: (asset) =>
                        asset.ciId ? (
                          <Link to={`/cmdb/${asset.ciId}`} onClick={(event) => event.stopPropagation()}>
                            {asset.ciName}
                          </Link>
                        ) : (
                          '—'
                        ),
                    },
                  ]}
                />
                <Pagination page={assets.data.page} totalPages={assets.data.totalPages} totalElements={assets.data.totalElements} size={assets.data.size} onPageChange={setPage} label="assets" />
              </>
            )}
          </Panel>
          <ExpiringPanel />
        </div>
      </div>
      {creating && <AssetEditor detail={null} onClose={() => setCreating(false)} onSaved={(saved) => navigate(`/assets/${saved.asset.id}`)} />}
      {importing && <AssetImportModal onClose={() => setImporting(false)} />}
    </>
  );
}
