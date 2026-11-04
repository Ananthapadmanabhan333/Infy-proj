import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { categories, laptop, powerBi } from '../components/servicedesk/fixtures.test-utils';
import { json, mockApi } from '../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../test/utils';
import CatalogPage from './CatalogPage';

function catalogRoutes() {
  mockApi({ 'GET /catalog/categories': json(categories), 'GET /catalog/items': json([powerBi, laptop]) });
}

describe('CatalogPage (/catalog)', () => {
  it('lists the catalogue with its header and lets employees request items', async () => {
    catalogRoutes();
    renderWithProviders(<CatalogPage />, { route: '/catalog', auth: makeAuth(makeUser('EMPLOYEE')) });
    expect(screen.getByRole('heading', { level: 1, name: 'Service Catalogue' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Request New laptop' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: /New item/ })).not.toBeInTheDocument();
  });

  it('is read-only for auditors', async () => {
    catalogRoutes();
    renderWithProviders(<CatalogPage />, { route: '/catalog', auth: makeAuth(makeUser('AUDITOR')) });
    expect(await screen.findByRole('article', { name: 'New laptop' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Request / })).not.toBeInTheDocument();
  });

  it('shows the administration actions to configuration admins', async () => {
    catalogRoutes();
    renderWithProviders(<CatalogPage />, { route: '/catalog', auth: makeAuth(makeUser('IT_ADMIN')) });
    expect(await screen.findByRole('button', { name: /New item/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /New category/ })).toBeInTheDocument();
  });
});
