import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';
import { setAccessToken, setSessionExpiredHandler } from '../lib/api';
import { resetMockApi } from './mockApi';

beforeEach(() => {
  resetMockApi();
});

afterEach(() => {
  cleanup();
  setAccessToken(null);
  setSessionExpiredHandler(null);
});
