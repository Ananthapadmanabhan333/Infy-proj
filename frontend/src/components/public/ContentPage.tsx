import type { ReactNode } from 'react';
import { PublicLayout } from './PublicLayout';
import { PublicLink } from './PublicLink';

export interface ContentSection {
  id: string;
  label: string;
}

interface ContentPageProps {
  kicker: string;
  title: string;
  lead: ReactNode;
  /** Table of contents (anchors of the `<section id>` children). */
  sections?: ContentSection[];
  /** Under the lead (buttons, notices). */
  heroExtra?: ReactNode;
  children: ReactNode;
}

/** Docs / legal page frame: hero heading, sticky "On this page" navigation and a prose column. */
export function ContentPage({ kicker, title, lead, sections = [], heroExtra, children }: ContentPageProps) {
  return (
    <PublicLayout>
      <div className="pub-container pub-page">
        <div className="pub-page-hero">
          <p className="pub-kicker">{kicker}</p>
          <h1>{title}</h1>
          <p>{lead}</p>
          {heroExtra}
        </div>
        {sections.length > 0 ? (
          <div className="pub-doc-layout">
            <nav className="pub-toc" aria-labelledby="toc-title">
              <h2 id="toc-title">On this page</h2>
              <ul>
                {sections.map((section) => (
                  <li key={section.id}>
                    <PublicLink to={`#${section.id}`}>{section.label}</PublicLink>
                  </li>
                ))}
              </ul>
            </nav>
            <div className="pub-prose">{children}</div>
          </div>
        ) : (
          <div className="pub-prose">{children}</div>
        )}
      </div>
    </PublicLayout>
  );
}
