import { Link } from 'react-router-dom';
import { TriangleAlert } from 'lucide-react';
import { ContentPage, type ContentSection } from '../../components/public/ContentPage';
import { usePageMeta } from '../../hooks/usePageMeta';

const SECTIONS: ContentSection[] = [
  { id: 'acceptance', label: 'Acceptance' },
  { id: 'accounts', label: 'Accounts & access' },
  { id: 'acceptable-use', label: 'Acceptable use' },
  { id: 'data', label: 'Your data' },
  { id: 'ai', label: 'AI-assisted features' },
  { id: 'availability', label: 'Availability' },
  { id: 'liability', label: 'Liability' },
  { id: 'changes', label: 'Changes & contact' },
];

/** Template terms — the operating organisation must review and replace them (spec §6). */
export default function TermsPage() {
  usePageMeta('SA | Terms and Conditions', 'Template terms of use for an SA deployment, to be reviewed and replaced by the operating organisation.');
  return (
    <ContentPage
      kicker="Legal"
      title="Terms and Conditions"
      lead="Terms of use for this SA workspace."
      sections={SECTIONS}
      heroExtra={
        <div className="pub-callout warning" role="note" aria-label="Template notice">
          <TriangleAlert size={18} aria-hidden="true" />
          <div>
            <p>
              <strong>Template — not yet adopted.</strong> This text is a starting point supplied with the SA software.
              The organisation operating this deployment must review it with its legal advisers and replace the bracketed
              placeholders (for example <code>[Organisation]</code>) before relying on it.
            </p>
            <p>It is not legal advice.</p>
          </div>
        </div>
      }
    >
      <section id="acceptance" aria-labelledby="acceptance-title">
        <h2 id="acceptance-title">1. Acceptance</h2>
        <p>
          SA is provided by [Organisation] (&ldquo;we&rdquo;) to its employees and authorised contractors (&ldquo;you&rdquo;)
          for IT operations and service management. By signing in you agree to these terms and to [Organisation]&apos;s
          internal IT and information-security policies.
        </p>
      </section>

      <section id="accounts" aria-labelledby="accounts-title">
        <h2 id="accounts-title">2. Accounts &amp; access</h2>
        <ul>
          <li>Accounts are created by [Organisation]&apos;s administrators; there is no open self-registration.</li>
          <li>Your account is personal. Keep your password confidential and do not share invitation or reset links.</li>
          <li>Your role determines what you can see and do. Do not try to access data or functions outside your role.</li>
          <li>[Organisation] may suspend or remove access at any time, for example when you change role or leave.</li>
        </ul>
      </section>

      <section id="acceptable-use" aria-labelledby="acceptable-use-title">
        <h2 id="acceptable-use-title">3. Acceptable use</h2>
        <ul>
          <li>Use SA only for legitimate work purposes of [Organisation].</li>
          <li>Record accurate information in incidents, requests and knowledge articles.</li>
          <li>Do not upload malware, unlawful content or data you are not authorised to share.</li>
          <li>Do not attempt to disrupt, probe or circumvent the platform&apos;s security controls.</li>
        </ul>
      </section>

      <section id="data" aria-labelledby="terms-data-title">
        <h2 id="terms-data-title">4. Your data</h2>
        <p>
          Records you create belong to [Organisation]. How personal data is processed is described in the{' '}
          <Link to="/privacy">Privacy Policy</Link>. Activity is recorded in an append-only audit log for security and
          compliance purposes.
        </p>
      </section>

      <section id="ai" aria-labelledby="terms-ai-title">
        <h2 id="terms-ai-title">5. AI-assisted features</h2>
        <p>
          SA AI suggestions may be incomplete or wrong. Each result is labelled with its source, and proposed changes
          require a human approval — you remain responsible for decisions you take on the basis of a suggestion.
        </p>
      </section>

      <section id="availability" aria-labelledby="availability-title">
        <h2 id="availability-title">6. Availability</h2>
        <p>
          [Organisation] aims to keep SA available but does not guarantee uninterrupted service. Planned maintenance
          is announced in the workspace where possible. [Insert any internal service levels here.]
        </p>
      </section>

      <section id="liability" aria-labelledby="liability-title">
        <h2 id="liability-title">7. Liability</h2>
        <p>[Insert the limitation-of-liability wording approved by [Organisation]&apos;s legal team, and the governing law: [Jurisdiction].]</p>
      </section>

      <section id="changes" aria-labelledby="changes-title">
        <h2 id="changes-title">8. Changes &amp; contact</h2>
        <p>
          [Organisation] may update these terms; the current version is always available at this address. Questions:
          see the <Link to="/support">Support page</Link>. [Insert an effective date when the terms are adopted.]
        </p>
      </section>
    </ContentPage>
  );
}
