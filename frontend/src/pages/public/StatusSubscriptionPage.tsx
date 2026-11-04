import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { BellOff, BellRing, Link2Off, Loader2, RefreshCw, WifiOff } from 'lucide-react';
import { SUBSCRIPTION_TOKEN, useStatusSubscriptionAction, type SubscriptionAction } from '../../api/statusPage';
import { PublicLayout } from '../../components/public/PublicLayout';
import { usePageMeta } from '../../hooks/usePageMeta';
import { toApiError } from '../../lib/api';
import { pageTitle } from '../../lib/brand';
import './status.css';

type Outcome = 'pending' | 'done' | 'invalid' | 'failed';

/**
 * The API answers the same for valid and unknown tokens (it never reveals whether an address is subscribed), so the
 * "done" copy is deliberately conditional — the API's own message is shown when it sends one.
 */
const COPY: Record<SubscriptionAction, { page: string; pending: string; done: string; doneText: string }> = {
  confirm: {
    page: 'Confirm status updates',
    pending: 'Confirming your subscription…',
    done: 'Confirmation received',
    doneText: 'If the link was valid, your subscription to status updates is now confirmed.',
  },
  unsubscribe: {
    page: 'Unsubscribe from status updates',
    pending: 'Unsubscribing…',
    done: 'Unsubscribe request received',
    doneText: 'If the link was valid, you will no longer receive status updates.',
  },
};

/** A token must look like the ones in the e-mails (URL-safe characters, bounded length); anything else is invalid. */
function usableToken(token: string | null | undefined): string | null {
  const value = token?.trim() ?? '';
  return SUBSCRIPTION_TOKEN.test(value) ? value : null;
}

interface StatusSubscriptionPageProps {
  action: SubscriptionAction;
  /** Token passed by `/status?confirm=…` / `/status?unsubscribe=…` (the links the API e-mails); else `?token=`. */
  token?: string | null;
}

/**
 * Landing state of the subscription links: `/status?confirm=<token>`, `/status?unsubscribe=<token>` (the links in the
 * e-mails) and the aliases `/status/confirm?token=` and `/status/unsubscribe?token=`. The token is sent once (guarded
 * against React strict-mode double effects).
 */
export default function StatusSubscriptionPage({ action, token: tokenProp }: StatusSubscriptionPageProps) {
  const [params] = useSearchParams();
  const token = usableToken(tokenProp !== undefined ? tokenProp : params.get('token'));
  const copy = COPY[action];
  const mutation = useStatusSubscriptionAction();
  const [outcome, setOutcome] = useState<Outcome>(token ? 'pending' : 'invalid');
  const [message, setMessage] = useState<string | null>(null);
  const sent = useRef<string | null>(null);
  usePageMeta(pageTitle(copy.page), copy.page);

  const run = useCallback(() => {
    if (!token) return;
    setOutcome('pending');
    mutation.mutate(
      { action, token },
      {
        onSuccess: (serverMessage) => {
          setMessage(serverMessage);
          setOutcome('done');
        },
        onError: (error) => {
          const status = toApiError(error).status;
          setOutcome(status === 0 || status >= 500 || status === 429 ? 'failed' : 'invalid');
        },
      },
    );
  }, [action, token, mutation]);

  useEffect(() => {
    if (!token || sent.current === `${action}:${token}`) return;
    sent.current = `${action}:${token}`;
    run();
  }, [action, token, run]);

  return (
    <PublicLayout>
      <div className="pub-container pub-page sp-page">
        <div className="sp-state-card sp-subscription" aria-live="polite">
          {outcome === 'pending' && (
            <>
              <Loader2 size={30} className="sp-spin" aria-hidden="true" />
              <h1>{copy.pending}</h1>
            </>
          )}
          {outcome === 'done' && (
            <>
              {action === 'confirm' ? <BellRing size={30} strokeWidth={1.5} aria-hidden="true" /> : <BellOff size={30} strokeWidth={1.5} aria-hidden="true" />}
              <h1>{copy.done}</h1>
              <p>{message ?? copy.doneText}</p>
            </>
          )}
          {outcome === 'invalid' && (
            <>
              <Link2Off size={30} strokeWidth={1.5} aria-hidden="true" />
              <h1>This link is invalid or has expired</h1>
              <p>
                {action === 'confirm'
                  ? 'Subscribe again from the status page to receive a new confirmation link.'
                  : 'If you still receive status e-mails, use the unsubscribe link in the most recent message.'}
              </p>
            </>
          )}
          {outcome === 'failed' && (
            <>
              <WifiOff size={30} strokeWidth={1.5} aria-hidden="true" />
              <h1>We could not complete this right now</h1>
              <p>The status service is not reachable at the moment. Please try again shortly.</p>
              <div className="pub-btn-row">
                <button type="button" className="pub-btn pub-btn-gold" onClick={run} disabled={mutation.isPending}>
                  <RefreshCw size={16} aria-hidden="true" /> Try again
                </button>
              </div>
            </>
          )}
          {outcome !== 'pending' && (
            <div className="pub-btn-row">
              <Link to="/status" className="pub-btn pub-btn-outline">
                Open the status page
              </Link>
            </div>
          )}
        </div>
      </div>
    </PublicLayout>
  );
}
