import { Bot, Cpu, Gauge, KeyRound, ShieldCheck, Sparkles, Timer } from 'lucide-react';
import { useAiConfig, useUpdateAiConfig } from '../../api/admin';
import { Badge } from '../../components/Badge';
import { Toggle } from '../../components/Controls';
import { KpiCard } from '../../components/KpiCard';
import { Panel } from '../../components/Panel';
import { ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { formatNumber, titleCase } from '../../lib/format';

/** Settings → AI (SETTINGS). Provider, model and limits come from the server environment; API keys are never shown or accepted. */
export function AiTab() {
  const config = useAiConfig();
  const update = useUpdateAiConfig();
  const toast = useToast();

  if (config.isLoading) return <LoadingState variant="kpis" label="Loading AI configuration…" />;
  if (config.isError || !config.data) return <ErrorState error={config.error} onRetry={() => void config.refetch()} />;
  const data = config.data;
  const llmActive = data.llmEnabled && data.keyConfigured;

  return (
    <>
      <div className="kpi-grid">
        <KpiCard icon={<Bot />} label="Provider" value={titleCase(data.provider) || '—'} caption="Set in the server environment" />
        <KpiCard icon={<Cpu />} label="Model" value={<span className="text-[15px]">{data.model ?? '—'}</span>} tone="neutral" caption="Model used for LLM answers" />
        <KpiCard
          icon={<KeyRound />}
          label="API key"
          value={data.keyConfigured ? 'Configured' : 'Not configured'}
          tone={data.keyConfigured ? 'success' : 'warning'}
          caption="Stored on the server only"
        />
        <KpiCard
          icon={<Sparkles />}
          label="Engine in use"
          value={llmActive ? 'Language model' : 'Fallback'}
          tone={llmActive ? 'gold' : 'neutral'}
          caption={llmActive ? 'Answers labelled LLM' : 'Answers labelled FALLBACK'}
        />
      </div>
      <div className="grid gap-[14px] xl:grid-cols-[1.55fr_1fr]">
        <Panel title="SA AI engine" subtitle="Only the language-model switch can be changed here; every change is audited">
          <div className="setting-row">
            <div>
              <b>Use the language model</b>
              <small>
                {data.keyConfigured
                  ? 'When off, every AI feature uses the deterministic fallback engine and is labelled as such.'
                  : 'No API key is configured on the server, so the deterministic fallback engine is used. Configure the key in the server environment to enable the language model.'}
              </small>
            </div>
            <Toggle
              checked={data.llmEnabled}
              label="Use the language model"
              disabled={update.isPending || !data.keyConfigured}
              onChange={(llmEnabled) =>
                update.mutate(
                  { llmEnabled },
                  {
                    onSuccess: () => toast.success(llmEnabled ? 'Language model enabled' : 'Using the deterministic fallback engine'),
                    onError: (error) => toast.error(error),
                  },
                )
              }
            />
          </div>
          <div className="setting-row">
            <div>
              <b className="inline-flex items-center gap-1.5">
                <Gauge size={13} className="gold-text" /> Rate limit
              </b>
              <small>Per user, per minute. Further requests receive “429 Too Many Requests” until the window resets.</small>
            </div>
            <Badge value="outline" label={`${formatNumber(data.rateLimitPerMinute)} / min`} />
          </div>
          <div className="setting-row">
            <div>
              <b className="inline-flex items-center gap-1.5">
                <Timer size={13} className="gold-text" /> Timeout
              </b>
              <small>A language-model call that takes longer is abandoned and the fallback answer is returned instead.</small>
            </div>
            <Badge value="outline" label={`${formatNumber(data.timeoutSeconds)} s`} />
          </div>
        </Panel>
        <Panel title="How the fallback works" raised>
          <div className="callout">
            <ShieldCheck size={16} />
            <div>
              SA AI always has an answer. When the language model is switched off, has no key, times out, is rate limited or returns an invalid
              response, the <b>deterministic fallback engine</b> answers instead: rules, keyword matching and statistics over your own incidents,
              services and knowledge articles. It never invents data.
            </div>
          </div>
          <ul className="mt-3 grid gap-2 pl-4 text-[11px] leading-relaxed text-[#d6cab8]">
            <li>Every AI result is labelled <b className="gold-text">LLM</b> or <b className="gold-text">FALLBACK</b>, so users always know which engine produced it.</li>
            <li>Suggestions that change data (assignments, priorities, runbooks) always need a person to confirm them.</li>
            <li>API keys live only in the server environment — they are never shown, sent or accepted by this page.</li>
          </ul>
        </Panel>
      </div>
    </>
  );
}
