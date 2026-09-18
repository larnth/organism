import { defineConfig } from '@playwright/test';

const baseURL = process.env.ORGANISM_TEST_URL;
if (!baseURL || !/^organism-acceptance-[0-9a-f-]{36}$/.test(process.env.ORGANISM_TEST_DATABASE ?? '')) {
  throw new Error('Use node client/scripts/acceptance.mjs to create an isolated, owned acceptance database.');
}
const url = new URL(baseURL);
if (!['127.0.0.1', 'localhost'].includes(url.hostname)) {
  throw new Error('Legacy gameplay acceptance must use a disposable local server.');
}

export default defineConfig({
  testDir: './e2e',
  testMatch: 'legacy-play.spec.ts',
  timeout: 60_000,
  workers: 1,
  reporter: 'list',
  outputDir: process.env.ORGANISM_TEST_OUTPUT ?? '../target/legacy-browser-results',
  use: { baseURL, channel: 'chrome', headless: true, viewport: { width: 1440, height: 1000 } },
});
