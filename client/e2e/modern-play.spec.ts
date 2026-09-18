import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { register } from './auth';

async function snapshot(request: APIRequestContext, game: string) {
  const response = await request.get(`/api/v1/organism/games/${encodeURIComponent(game)}`);
  expect(response.status()).toBe(200);
  return response.json();
}
async function fit(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
    await page.evaluate(() => innerWidth),
  );
}
async function placement(page: Page) {
  const fields = page.getByLabel(/^Element at /);
  await expect(fields).toHaveCount(3);
  await fields.nth(0).selectOption('eat');
  await fields.nth(1).selectOption('grow');
  await expect(page.getByRole('button', { name: 'Place starting organism' })).toBeEnabled();
  await page.getByRole('button', { name: 'Place starting organism' }).click();
}
async function signature(page: Page) {
  return page.evaluate(() => JSON.stringify([
    document.querySelector('.turn-dock')?.textContent,
    [...document.querySelectorAll('.board-location--selected,.board-location--destination')].map(e => e.getAttribute('data-coordinate')),
  ]));
}
async function finishTurn(page: Page, next: APIRequestContext, game: string) {
  for (let step = 0; step < 40; step++) {
    if ((await snapshot(next, game)).viewer.canAct) return;
    const before = await snapshot(page.request, game);
    expect(before.viewer.canAct).toBe(true);
    await expect(page.getByText(new RegExp(`Revision ${before.revision}\\b`))).toBeVisible();
    const previous = await signature(page);
    const destinations = page.locator('.board-location--destination');
    const sources = page.locator('.board-location--source');
    const spatial = page.locator('.board-location--action');
    if (await destinations.count()) await destinations.first().click();
    else if (await sources.count()) await sources.first().click();
    else if (await spatial.count()) await spatial.first().click();
    else {
      const eatPlan = before.legalActions.find((a: { kind: string; options?: string[] }) => a.kind === 'choose-action-type' && a.options?.includes('eat'));
      if (eatPlan?.label) await page.getByRole('button', { name: eatPlan.label, exact: true }).click();
      else await page.locator('.action-panel button:enabled:not(.action-panel__back)').first().click();
    }
    await expect.poll(() => signature(page), { message: 'A visible action must change the board or its next choices' }).not.toBe(previous);
  }
  throw new Error('No reciprocal turn handoff after 40 actual UI choices');
}

test('React create, lobby, both placements, reciprocal turns, retry, Undo, replay, chat and reconnect', async ({ browser, baseURL }, info) => {
  test.setTimeout(90000);
  const id = randomUUID().slice(0, 8);
  const alice = `react-a-${id}`;
  const bob = `react-b-${id}`;
  const game = `Friday night bloom with a deliberately long table title ${id}`;
  const contexts = await Promise.all([
    browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 } }),
    browser.newContext({ baseURL, viewport: { width: 390, height: 844 } }),
    browser.newContext({ baseURL, viewport: { width: 1100, height: 800 } }),
  ]);
  await Promise.all(contexts.map(context => context.addInitScript(() => {
    const Native = window.WebSocket;
    const sockets: WebSocket[] = [];
    Object.defineProperty(window, '__acceptanceSockets', { value: sockets });
    window.WebSocket = class extends Native {
      constructor(url: string | URL, protocols?: string | string[]) { super(url, protocols); sockets.push(this); }
    };
  })));
  const [a, b, observer] = await Promise.all(contexts.map(context => context.newPage()));
  try {
    await register(contexts[0].request, alice);
    await register(contexts[1].request, bob);
    await a.goto('/organism/create');
    await a.getByLabel('Game name').fill(game);
    await a.getByLabel('Players', { exact: true }).selectOption('2');
    await a.getByLabel('Field size').selectOption('3');
    await a.getByRole('button', { name: 'Launch lobby' }).click();
    await expect.poll(() => new URL(a.url()).searchParams.get('game')).toBe(game);
    await expect(a.getByRole('region', { name: 'Lobby roster' })).toBeVisible();
    const url = `/modern/?game=${encodeURIComponent(game)}`;
    await Promise.all([b.goto(url), observer.goto(url)]);
    await b.getByRole('button', { name: 'Join seat 2' }).click();
    await expect(b.getByRole('button', { name: 'Join seat 2' })).toHaveCount(0);
    await expect(observer.getByRole('button', { name: 'Start game', exact: true })).toHaveCount(0);
    await expect(a.getByRole('button', { name: 'Start game', exact: true })).toBeDisabled();
    await fit(a); await fit(b);

    const lobbyMessage = `Ready for a real game ${id}`;
    await a.getByLabel('Message', { exact: true }).fill(lobbyMessage);
    await expect(a.getByRole('button', { name: 'Send', exact: true })).toBeEnabled();
    await a.getByLabel('Message', { exact: true }).press('Enter');
    await expect(a.getByLabel('Message', { exact: true })).toHaveValue('');
    await expect(b.getByText(lobbyMessage, { exact: true })).toHaveCount(1);
    await b.getByRole('button', { name: 'Ready up', exact: true }).click();
    await a.getByRole('button', { name: 'Ready up', exact: true }).click();
    await a.getByRole('button', { name: 'Start game', exact: true }).click();
    await expect(a.getByRole('heading', { name: 'Place your starting organism' })).toBeVisible();
    await expect(b.getByText('Your choices will appear when it is your turn.')).toBeVisible();
    await expect(b.locator('.board-location[role="button"]')).toHaveCount(0);
    await expect(observer.locator('.board-location[role="button"]')).toHaveCount(0);

    // Commit the first command, then lose the HTTP response. The retry must reuse its ID.
    const posts: Record<string, unknown>[] = [];
    a.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/commands')) posts.push(request.postDataJSON()); });
    const commandRoute = '**/api/v1/organism/games/*/commands';
    await a.route(commandRoute, async route => { await route.fetch(); await route.abort('failed'); }, { times: 1 });
    await placement(a);
    await expect(a.getByRole('button', { name: 'Retry same action' })).toBeVisible();
    const accepted = await snapshot(contexts[0].request, game);
    await a.getByRole('button', { name: 'Retry same action' }).click();
    await expect(a.getByRole('button', { name: 'Retry same action' })).toHaveCount(0);
    expect(posts).toHaveLength(2);
    expect(posts[1]).toEqual(posts[0]);
    expect((await snapshot(contexts[0].request, game)).revision).toBe(accepted.revision);

    await a.getByRole('button', { name: 'Scores, history, help and discussion' }).click();
    await expect(a.getByText(lobbyMessage, { exact: true })).toHaveCount(1);
    await a.getByRole('button', { name: 'Undo last action' }).click();
    await expect(a.getByRole('heading', { name: 'Place your starting organism' })).toBeVisible();
    expect((await snapshot(contexts[0].request, game)).revision).toBe(accepted.revision + 1);
    await placement(a);
    await finishTurn(a, contexts[1].request, game);
    await expect(b.getByRole('heading', { name: 'Place your starting organism' })).toBeVisible();
    await placement(b);
    await finishTurn(b, contexts[0].request, game);
    await expect(a.getByRole('region', { name: 'Your turn', exact: true })).toBeVisible();
    await expect(b.getByRole('region', { name: 'Your turn', exact: true })).toHaveCount(0);
    await fit(a); await fit(b);

    await a.getByRole('button', { name: 'Scores, history, help and discussion' }).click();
    const live = await snapshot(contexts[0].request, game);
    await a.getByRole('button', { name: 'Replay from start' }).click();
    await expect(a.getByText('Read-only history', { exact: true })).toBeVisible();
    await expect(a.locator('.board-location[role="button"]')).toHaveCount(0);
    expect((await snapshot(contexts[0].request, game)).revision).toBe(live.revision);
    await a.getByRole('button', { name: 'Back to live', exact: true }).click();
    await expect(a.getByRole('region', { name: 'Your turn', exact: true })).toBeVisible();

    await b.getByRole('button', { name: 'Scores, history, help and discussion' }).click();
    const chatRoute = '**/api/v1/organism/games/*/chat';
    await b.route(chatRoute, route => route.abort('failed'), { times: 1 });
    const draft = `This draft survives failure ${id}`;
    await b.getByLabel('Message', { exact: true }).fill(draft);
    await b.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(b.getByRole('button', { name: 'Retry message' })).toBeVisible();
    await expect(b.getByLabel('Message', { exact: true })).toHaveValue(draft);
    await b.getByRole('button', { name: 'Retry message' }).click();
    await expect(b.getByLabel('Message', { exact: true })).toHaveValue('');
    await expect(b.getByText(draft, { exact: true })).toHaveCount(1);
    await fit(b);
    await b.getByRole('button', { name: 'Close game details' }).click();

    const count = await b.evaluate(() => (window as unknown as { __acceptanceSockets: WebSocket[] }).__acceptanceSockets.length);
    await b.evaluate(() => (window as unknown as { __acceptanceSockets: WebSocket[] }).__acceptanceSockets.forEach(socket => socket.close(4000, 'acceptance reconnect')));
    await expect.poll(() => b.evaluate(() => (window as unknown as { __acceptanceSockets: WebSocket[] }).__acceptanceSockets.length)).toBeGreaterThan(count);
    await b.getByRole('button', { name: 'Scores, history, help and discussion' }).click();
    await a.getByRole('button', { name: 'Scores, history, help and discussion' }).click();
    const reconnected = `Delivered after reconnect ${id}`;
    await a.getByLabel('Message', { exact: true }).fill(reconnected);
    await a.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(b.getByText(reconnected, { exact: true })).toHaveCount(1);
    await b.reload();
    await b.getByRole('button', { name: 'Scores, history, help and discussion' }).click();
    await expect(b.getByText(draft, { exact: true })).toHaveCount(1);
    await expect(b.getByText(reconnected, { exact: true })).toHaveCount(1);
    await b.screenshot({ path: info.outputPath('react-phone-details.png'), fullPage: true });
  } finally {
    await Promise.all(contexts.map(context => context.close()));
  }
});
