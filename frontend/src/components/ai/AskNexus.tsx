import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { AlertTriangle, ArrowRight, Database, Eraser, Sparkles } from 'lucide-react';
import { useAiAsk } from '../../api/ai';
import { formatNumber } from '../../lib/format';
import type { AiResult, AskResponse } from '../../lib/types';
import { AiResultCard } from '../AiResultCard';
import { aiErrorMessage } from './aiUtils';

/** Starter prompts (questions, not data); every answer is computed from live records. */
const SUGGESTIONS = [
  'Summarize today’s critical incidents',
  'Which open incidents are most likely to breach their SLA?',
  'Which services have the most open incidents?',
  'What are the most frequent recurring incident patterns this week?',
];

interface ThreadEntry {
  id: number;
  question: string;
  result?: AiResult<AskResponse>;
  error?: string;
}

/** Grounded figures returned with an answer, as a compact bar table. */
export function AnswerData({ data }: { data: AskResponse['data'] }) {
  if (!data.length) return null;
  const max = Math.max(1, ...data.map((point) => point.value));
  return (
    <table className="nx-data" aria-label="Answer data">
      <tbody>
        {data.map((point) => (
          <tr key={point.label}>
            <th scope="row" title={point.label}>
              {point.label}
            </th>
            <td aria-hidden="true">
              <div className="health-track">
                <i style={{ width: `${(point.value / max) * 100}%` }} />
              </div>
            </td>
            <td>
              <b>{formatNumber(point.value)}</b>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function AnswerView({ result }: { result: AiResult<AskResponse> }) {
  const { answer, data, basis } = result.data;
  return (
    <AiResultCard result={result} title="SA AI" compact>
      <p className="whitespace-pre-wrap">{answer}</p>
      <AnswerData data={data} />
      {basis && (
        <p className="nx-basis">
          <Database size={11} aria-hidden="true" /> Based on: {basis}
        </p>
      )}
    </AiResultCard>
  );
}

/**
 * "Ask SA AI anything…" bar with a real question/answer thread (kept in component state).
 * A `?q=` question handed over from the Command Center is asked once.
 */
export function AskNexus() {
  const [params, setParams] = useSearchParams();
  const [prompt, setPrompt] = useState('');
  const [thread, setThread] = useState<ThreadEntry[]>([]);
  const ask = useAiAsk();
  const nextId = useRef(1);
  const autoAsked = useRef(false);
  const endRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const submit = (question: string) => {
    const text = question.trim();
    if (!text || ask.isPending) return;
    const id = nextId.current++;
    setThread((entries) => [...entries, { id, question: text }]);
    setPrompt('');
    ask.mutate(text, {
      onSuccess: (result) => setThread((entries) => entries.map((entry) => (entry.id === id ? { ...entry, result } : entry))),
      onError: (error) => setThread((entries) => entries.map((entry) => (entry.id === id ? { ...entry, error: aiErrorMessage(error) } : entry))),
    });
  };

  useEffect(() => {
    const q = params.get('q');
    if (q && !autoAsked.current) {
      autoAsked.current = true;
      submit(q);
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          next.delete('q');
          return next;
        },
        { replace: true },
      );
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    endRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' });
  }, [thread]);

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    submit(prompt);
  };

  return (
    <section className="nx-ask" aria-label="Ask SA AI">
      {thread.length > 0 && (
        <div className="nx-thread" aria-live="polite" aria-label="Conversation">
          {thread.map((entry) => (
            <div key={entry.id} className="grid gap-2">
              <div className="nx-bubble-q">{entry.question}</div>
              <div className="nx-bubble-a">
                <span className="nx-bubble-avatar" aria-hidden="true">
                  <Sparkles size={13} />
                </span>
                <div className="min-w-0 flex-1">
                  {!entry.result && !entry.error && (
                    <div className="ai-result" role="status">
                      <span className="inline-flex items-center gap-2 text-muted">
                        <Sparkles size={13} className="animate-pulse" /> Analysing operational data…
                      </span>
                    </div>
                  )}
                  {entry.error && (
                    <div className="callout red" role="alert">
                      <AlertTriangle size={16} />
                      <div>{entry.error}</div>
                    </div>
                  )}
                  {entry.result && <AnswerView result={entry.result} />}
                </div>
              </div>
            </div>
          ))}
          <div ref={endRef} />
        </div>
      )}
      <form className="nx-ask-bar" onSubmit={onSubmit}>
        <Sparkles size={15} aria-hidden="true" />
        <input
          ref={inputRef}
          value={prompt}
          onChange={(event) => setPrompt(event.target.value)}
          placeholder="Ask SA AI anything…"
          aria-label="Ask SA AI anything"
          maxLength={500}
        />
        <button type="submit" aria-label="Send question" disabled={!prompt.trim() || ask.isPending}>
          <ArrowRight size={16} />
        </button>
      </form>
      <div className="nx-ask-foot">
        {thread.length === 0 ? (
          <div className="nx-suggestions" aria-label="Suggested questions">
            {SUGGESTIONS.map((suggestion) => (
              <button key={suggestion} type="button" onClick={() => submit(suggestion)} disabled={ask.isPending}>
                {suggestion}
              </button>
            ))}
          </div>
        ) : (
          <button
            type="button"
            className="link-btn text-[11px]"
            onClick={() => {
              setThread([]);
              inputRef.current?.focus();
            }}
            disabled={ask.isPending}
          >
            <Eraser size={12} /> Clear conversation
          </button>
        )}
        <small>Answers are grounded in SA records; each shows its source — an LLM or the deterministic fallback engine.</small>
      </div>
    </section>
  );
}
