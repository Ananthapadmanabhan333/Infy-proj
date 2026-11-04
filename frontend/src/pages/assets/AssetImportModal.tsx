import { useRef, useState } from 'react';
import '../../components/ops/ops.css';
import { Download, FileCheck2, Upload } from 'lucide-react';
import { useImportAssets } from '../../api/assets';
import { DataTable } from '../../components/DataTable';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { downloadFile, errorMessage } from '../../lib/api';
import type { ImportResult } from '../../lib/opsTypes';
import { formatBytes, titleCase } from '../../lib/format';
import { checkImportFile, describeImport, rowLabel, type ImportRowView } from './assetImport';

/**
 * CSV import (asset editors): validate with a dry run (row-level errors, nothing stored), then apply the same file —
 * the API stores every row in one transaction or none.
 */
export function AssetImportModal({ onClose }: { onClose: () => void }) {
  const importer = useImportAssets();
  const toast = useToast();
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [downloading, setDownloading] = useState(false);
  const view = result ? describeImport(result) : null;

  const run = (dryRun: boolean) => {
    const problem = checkImportFile(file);
    setFileError(problem);
    if (problem || !file) return;
    importer.mutate(
      { file, dryRun },
      {
        onSuccess: (next) => {
          setResult(next);
          if (!next.dryRun && next.applied) toast.success(`Imported: ${next.created} created, ${next.updated} updated`);
        },
      },
    );
  };

  const template = async () => {
    setDownloading(true);
    try {
      await downloadFile('/assets/import-template', 'asset-import-template.csv');
    } catch (error) {
      toast.error(error);
    } finally {
      setDownloading(false);
    }
  };

  const applied = view?.stage === 'applied';

  return (
    <Modal
      open
      onClose={onClose}
      title="Import assets from CSV"
      eyebrow="ASSET INVENTORY"
      description="UTF-8 CSV with a header row, up to 1,000 rows and 2 MB. A row whose asset tag exists updates that asset; other rows create assets."
      size="xl"
      dismissible={!importer.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={importer.isPending}>
            {applied ? 'Close' : 'Cancel'}
          </button>
          {!applied && (
            <button type="button" className={view?.canApply ? 'subtle-btn' : 'gold-btn'} onClick={() => run(true)} disabled={!file || importer.isPending}>
              <FileCheck2 size={14} aria-hidden="true" /> {importer.isPending && importer.variables?.dryRun ? 'Validating…' : result ? 'Validate again' : 'Validate (dry run)'}
            </button>
          )}
          {view?.canApply && (
            <button type="button" className="gold-btn" onClick={() => run(false)} disabled={importer.isPending}>
              <Upload size={14} aria-hidden="true" /> {importer.isPending && !importer.variables?.dryRun ? 'Importing…' : `Apply import (${result?.totalRows ?? 0} rows)`}
            </button>
          )}
        </>
      }
    >
      <div className="mb-3 flex flex-wrap items-end gap-2">
        <div className="field mb-0 min-w-0 flex-1">
          <div className="field-label">
            <label htmlFor="asset-import-file">CSV file</label>
          </div>
          <input
            id="asset-import-file"
            ref={inputRef}
            type="file"
            accept=".csv,text/csv"
            className="control"
            disabled={importer.isPending || applied}
            onChange={(event) => {
              const next = event.target.files?.[0] ?? null;
              setFile(next);
              setResult(null);
              importer.reset();
              setFileError(next ? checkImportFile(next) : null);
            }}
          />
          {file && !fileError && <span className="field-hint">{`${file.name} · ${formatBytes(file.size)}`}</span>}
          {fileError && (
            <span className="field-error" role="alert">
              {fileError}
            </span>
          )}
        </div>
        <button type="button" className="subtle-btn mb-[2px]" onClick={() => void template()} disabled={downloading}>
          <Download size={14} aria-hidden="true" /> {downloading ? 'Downloading…' : 'Download template'}
        </button>
      </div>

      {importer.isError && (
        <div className="callout red mb-3" role="alert">
          <div>{errorMessage(importer.error)}</div>
        </div>
      )}

      {view && result && (
        <div className="grid gap-3">
          <div className={`callout ${view.tone === 'success' ? 'green' : view.tone === 'critical' ? 'red' : ''}`} role="status">
            <div>
              <b>{result.dryRun ? 'Dry run' : view.stage === 'applied' ? 'Import applied' : 'Import rejected'}</b> · {view.headline}
              <div className="mt-1 text-[10px] text-muted">
                {result.totalRows} rows · {result.validRows} valid · {result.created} create · {result.updated} update
              </div>
            </div>
          </div>
          {view.fileErrors.length > 0 && (
            <div>
              <h4 className="m-0 mb-1 text-[11px] font-semibold">File errors</h4>
              <ul className="m-0 grid list-none gap-1 p-0" aria-label="File errors">
                {view.fileErrors.map((error, index) => (
                  <li key={index} className="text-[11px]">
                    <span className="red-text">{rowLabel(error.row)}</span>
                    {error.field ? <code className="mx-1">{error.field}</code> : ' '}
                    {error.message}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {view.rows.length > 0 && (
            <DataTable<ImportRowView>
              caption="Import rows"
              rows={view.rows}
              rowKey={(row) => row.row}
              rowClassName={(row) => (row.errors.length ? 'row-invalid' : '')}
              columns={[
                { key: 'row', header: 'Row', render: (row) => rowLabel(row.row) },
                { key: 'tag', header: 'Asset tag', render: (row) => <span className="id-cell">{row.assetTag ?? 'new'}</span> },
                { key: 'name', header: 'Name', className: 'summary-cell', render: (row) => <span className="break-words">{row.name ?? '—'}</span> },
                { key: 'type', header: 'Type', render: (row) => (row.assetType ? titleCase(row.assetType) : '—') },
                { key: 'action', header: 'Action', render: (row) => <ToneBadge value={row.action} tone={row.action === 'CREATE' ? 'gold' : 'champagne'} /> },
                {
                  key: 'result',
                  header: 'Result',
                  render: (row) =>
                    row.errors.length ? (
                      <ul className="m-0 list-none p-0 text-[11px] red-text" aria-label={`Errors in ${rowLabel(row.row)}`}>
                        {row.errors.map((error, index) => (
                          <li key={index} className="break-words">
                            {error.field ? <code className="mr-1">{error.field}</code> : null}
                            {error.message}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <span className="green-text">OK</span>
                    ),
                },
              ]}
            />
          )}
        </div>
      )}
    </Modal>
  );
}
