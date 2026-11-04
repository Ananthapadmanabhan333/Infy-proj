import type { ReactNode } from 'react';
import { pageDescriptions } from '../lib/nav';
import { PageHeader, type Crumb } from './PageHeader';

interface PageHeadingProps {
  /** Nav label used for the default description. */
  page: string;
  /** Heading text (defaults to the page label). */
  title?: ReactNode;
  description?: ReactNode;
  crumbs?: Crumb[];
  actions?: ReactNode;
}

/** Release 1 API kept for existing pages; new screens use <PageHeader>. */
export function PageHeading({ page, title, description, crumbs, actions }: PageHeadingProps) {
  return <PageHeader title={title ?? page} subtitle={description ?? pageDescriptions[page]} crumbs={crumbs} actions={actions} />;
}
