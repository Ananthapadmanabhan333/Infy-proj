import { useRef, useState, type FormEvent } from 'react';
import { AlertTriangle, Database, SendHorizontal, Sparkles } from 'lucide-react';
import { useAiAsk } from '../../api/ai';
import { errorMessage } from '../../lib/api';
import { formatNumber } from '../../lib/format';
import type { AiResult, AskResponse } from '../../lib/types';
import { AiResultCard } from '../AiResultCard';

interface Entry {
  id: number;
  question: string;
  result?: AiResult<AskResponse>;
  error?: string;
}

/** Grounded answer with its source label, the numbers behind it and what it was computed from. */
export function AskAnswer({ result }: { result: AiResult<AskResponse> }) {
  const { answer, data, basis } = result.data;
  const max = Math.max(1, ...data.map((point) => point.value));
  return (
    <AiResultCard result={result} title="SA AI answer">
      <p className="whitespace-pre-wrap">{answer}</p>
      {data.length > 0 && (
        <div className="data-bars" role="table" aria-label="Answer data">
          {data.map((point) => (
            <div key={point.label} role="row">
              <span role="cell" title={point.label}>
                {point.label}
              </span>
              <div className="health-track" aria-hidden="true">
                <i style={{ width: `${(point.value / max) * 100}%` }} />
              </div>
              <b role="cell">{formatNumber(point.value)}</b>
            </div>
          ))}
        </div>
      )}
      {basis && (
        <p className="mt-2 flex items-start gap-1.5 text-[10px] text-muted">
          <Database size={11} className="mt-0.5 shrink-0" aria-hidden="true" /> Based on: {basis}
        </p>
      )}
    </AiResultCard>
  );
}

/** Natural-language questions about the analytics data (POST /ai/ask), answered from live records. */
export function AskAnalytics({ suggestions }: { suggestions: string[] }) {
  const ask = useAiAsk();
  const [question, setQuestion] = useState('');
  const [entries, setEntries] = useState<Entry[]>([]);
  const nextId = useRef(1);

  const submit = (text: string) => {
    const value = text.trim();
    if (!value || ask.isPending) return;
    const id = nextId.current++;
    setEntries((current) => [{ id, question: value }, ...current].slice(0, 5));
    setQuestion('');
    ask.mutate(value, {
      onSuccess: (result) => setEntries((current) => current.map((entry) => (entry.id === id ? { ...entry, result } : entry))),
      onError: (error) => setEntries((current) => current.map((entry) => (entry.id === id ? { ...entry, error: errorMessage(error) } : entry))),
    });
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    submit(question);
  };

  return (
    <div className="grid gap-3">
      <form className="an-ask" onSubmit={onSubmit} role="search" aria-label="Ask SA AI about analytics">
        <Sparkles size={16} className="gold-text shrink-0" aria-hidden="true" />
        <input
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="Ask a question, e.g. Which services had the most incidents in the last 30 days?"
          aria-label="Question for SA AI"
          maxLength={500}
        />
        <button type="submit" className="gold-btn compact" disabled={!question.trim() || ask.isPending} aria-label="Ask SA AI">
          <SendHorizontal size={15} aria-hidden="true" />
          <span className="max-sm:hidden">{ask.isPending ? 'Asking…' : 'Ask'}</span>
        </button>
      </form>
      {suggestions.length > 0 && (
        <div className="flex flex-wrap gap-2" aria-label="Suggested questions">
          {suggestions.map((suggestion) => (
            <button key={suggestion} type="button" className="chip an-suggestion" onClick={() => submit(suggestion)} disabled={ask.isPending}>
              {suggestion}
            </button>
          ))}
        </div>
      )}
      <div className="grid gap-3" aria-live="polite">
        {entries.map((entry) => (
          <div key={entry.id} className="grid gap-2">
            <div className="an-question">{entry.question}</div>
            {!entry.result && !entry.error && (
              <div className="ai-result" role="status">
                <span className="inline-flex items-center gap-2 text-muted">
                  <Sparkles size={13} className="animate-pulse" aria-hidden="true" /> Analysing operational data…
                </span>
              </div>
            )}
            {entry.error && (
              <div className="callout red" role="alert">
                <AlertTriangle size={16} aria-hidden="true" />
                <div>{entry.error}</div>
              </div>
            )}
            {entry.result && <AskAnswer result={entry.result} />}
          </div>
        ))}
      </div>
    </div>
  );
}
