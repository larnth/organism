import { defineConfig } from '@playwright/test';
import legacy from './playwright.legacy.config';

export default defineConfig({
  ...legacy,
  testMatch: ['legacy-play.spec.ts', 'modern-contract.spec.ts', 'modern-play.spec.ts', 'modern-session.spec.ts'],
});
