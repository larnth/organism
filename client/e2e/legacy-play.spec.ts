import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import { expect, test, type Page } from '@playwright/test';
import { register } from './auth';

async function boardPointerCount(page: Page) {
  return page.locator('.organism-canvas-stage svg').evaluate(svg =>
    [...svg.querySelectorAll('*')].filter(node => getComputedStyle(node).cursor === 'pointer').length);
}

async function placeStartingPieces(page: Page) {
  // The real UI: choose a starting circle, then one of its three piece icons.
  // The second placement automatically fills the last of the three spaces.
  for (let step = 0; step < 2; step++) {
    const circles = page.locator('[data-starting-space]');
    await expect(circles.first()).toBeVisible();
    await circles.first().click();
    const option = page.locator('[data-starting-element]').first();
    await expect(option).toBeVisible();
    await option.click();
  }
  // Introduction is followed by that player's first organism turn. Pass
  // its one action and confirm normally; don't bypass the real lifecycle.
  await page.locator('[data-action-type="eat"]').first().click();
  await page.getByTitle('pass this action', { exact: true }).click();
  await page.locator('.organism-action-dock').getByText('confirm turn', { exact: true }).click();
}

test('waiting accounts and spectators cannot enter another player’s placement flow', async ({ browser, baseURL }, testInfo) => {
  const suffix = randomUUID().slice(0, 8);
  const alice = `probe-a-${suffix}`;
  const bob = `probe-b-${suffix}`;
  const game = `turn-ownership-${suffix}`;
  const contexts = await Promise.all([0, 1, 2].map(() => browser.newContext({ baseURL })));
  await contexts[1].addInitScript(() => {
    const sockets: WebSocket[] = [];
    Object.defineProperty(window, '__acceptanceSockets', { value: sockets });
    const NativeSocket = window.WebSocket;
    window.WebSocket = class extends NativeSocket {
      constructor(url: string | URL, protocols?: string | string[]) {
        super(url, protocols);
        sockets.push(this);
      }
    };
  });
  const [a, b, observer] = await Promise.all(contexts.map(context => context.newPage()));
  const browserErrors: string[] = [];
  const messages: object[] = [];
  for (const [role, page] of [['alice', a], ['bob', b], ['observer', observer]] as const) {
    page.on('pageerror', error => { browserErrors.push(error.message); console.error(error.stack); });
    page.on('console', message => {
      if (message.type() === 'error') browserErrors.push(message.text());
    });
    page.on('websocket', socket => {
      const url = socket.url();
      socket.on('framesent', frame => messages.push({ role, url, direction: 'sent', payload: String(frame.payload) }));
      socket.on('framereceived', frame => messages.push({ role, url, direction: 'received', payload: String(frame.payload) }));
      socket.on('close', () => messages.push({ role, url, direction: 'closed' }));
    });
  }
  try {
    await register(contexts[0].request, alice);
    await register(contexts[1].request, bob);
    await a.goto('/organism/create');
    await a.locator('.organism-game-name-input').fill(game);
    await a.getByRole('button', { name: 'LAUNCH LOBBY', exact: true }).click();
    await expect(a).toHaveURL(`/organism/create/${game}`);
    await expect(a.getByRole('heading', { name: game, exact: true })).toBeVisible();
    await expect.poll(async () => (await contexts[0].request.get(`/api/v1/organism/games/${game}`)).status()).toBe(200);
    await expect(a.getByRole('button', { name: 'START GAME', exact: true })).toBeDisabled();
    await b.goto(`/organism/create/${game}`);
    await b.getByRole('button', { name: `Join as ${bob}`, exact: true }).first().click();
    await expect(b.getByRole('button', { name: 'Ready up', exact: true })).toBeVisible();
    await expect(b.getByRole('button', { name: 'START GAME', exact: true })).toHaveCount(0);
    await expect(a.getByRole('button', { name: 'START GAME', exact: true })).toBeDisabled();
    const lobbyMessage = `Ready to grow ${suffix}`;
    await a.getByPlaceholder('Write to the table…').fill(lobbyMessage);
    await a.getByRole('button', { name: 'SEND', exact: true }).click();
    await expect(a.getByPlaceholder('Write to the table…')).toHaveValue('');
    await expect(a.getByText(lobbyMessage, { exact: true })).toHaveCount(1);
    await expect(b.getByText(lobbyMessage, { exact: true })).toHaveCount(1);
    await a.getByRole('button', { name: 'Ready up', exact: true }).click();
    await b.getByRole('button', { name: 'Ready up', exact: true }).click();
    await expect(a.getByRole('button', { name: 'START GAME', exact: true })).toBeEnabled();
    await a.getByRole('button', { name: 'START GAME', exact: true }).click();
    // Both already-open pages must follow the authoritative start event.
    await expect(a).toHaveURL(`/organism/play/${game}`);
    await expect(b).toHaveURL(`/organism/play/${game}`);
    await expect(a.locator('.organism-action-dock')).toContainText('YOUR TURN');
    await expect(b.locator('.organism-action-dock')).toContainText(alice);
    await observer.goto(`/organism/play/${game}`);
    await expect(observer.locator('.organism-action-dock')).toBeVisible();
    const activeBoard = a.locator('.organism-canvas-stage svg');
    const waitingBoard = b.locator('.organism-canvas-stage svg');
    const activeCircles = await activeBoard.locator('circle').count();
    const waitingCircles = await waitingBoard.locator('circle').count();
    // No placement highlight layer or action controls on the waiting board.
    expect(waitingCircles).toBeLessThan(activeCircles);
    expect(await boardPointerCount(b)).toBe(0);
    expect(await boardPointerCount(observer)).toBe(0);
    await expect(b.locator('.organism-dock-actions')).toBeEmpty();
    await expect(observer.locator('.organism-dock-actions')).toBeEmpty();
    const before = await waitingBoard.innerHTML();
    await waitingBoard.locator('circle').first().click({ force: true });
    expect(await waitingBoard.innerHTML()).toBe(before);
    await placeStartingPieces(a);
    await expect(b.locator('.organism-action-dock')).toContainText('YOUR TURN');
    await expect(a.locator('.organism-action-dock')).toContainText(bob);
    await expect(a.locator('.organism-dock-actions')).toBeEmpty();
    expect(await boardPointerCount(a)).toBe(0);
    await a.reload();
    await expect(a.locator('.organism-action-dock')).toBeVisible();
    await expect(a.locator('.organism-dock-actions')).toBeEmpty();
    await b.setViewportSize({ width: 390, height: 844 });
    await placeStartingPieces(b);
    await expect(a.locator('.organism-action-dock')).toContainText('YOUR TURN');
    await expect(b.locator('.organism-dock-actions')).toBeEmpty();
    for (const page of [a, b]) {
      await page.getByRole('button', { name: 'Scores, history, help and discussion', exact: true }).click();
      await expect(page.getByText(lobbyMessage, { exact: true })).toHaveCount(1);
    }
    const liveMessage = `First round ${suffix}`;
    await b.getByPlaceholder('Write to the table…').fill(liveMessage);
    await expect(b.getByRole('button', { name: 'SEND', exact: true })).toBeEnabled();
    await b.getByPlaceholder('Write to the table…').press('Enter');
    await expect(b.getByPlaceholder('Write to the table…')).toHaveValue('');
    await expect(a.getByText(liveMessage, { exact: true })).toHaveCount(1);
    await expect(b.getByText(liveMessage, { exact: true })).toHaveCount(1);
    // Reconnect the waiting player without advancing or replacing game state.
    const beforeReconnect = await (await contexts[1].request.get(`/api/v1/organism/games/${game}`)).json();
    const reconnectFrameStart = messages.length;
    await b.evaluate(() => {
      const sockets = (window as unknown as { __acceptanceSockets: WebSocket[] }).__acceptanceSockets;
      const open = sockets.filter(socket => socket.readyState === WebSocket.OPEN);
      if (open.length !== 1) throw new Error(`Expected one live game socket, found ${open.length}`);
      open[0].close(4000, 'Acceptance disconnect');
    });
    await expect.poll(() => messages.slice(reconnectFrameStart).filter((message) =>
      'role' in message && message.role === 'bob'
      && 'direction' in message && message.direction === 'received'
      && 'payload' in message && String(message.payload).includes('initialize')).length).toBeGreaterThan(0);
    await expect.poll(async () => {
      const snapshot = await (await contexts[1].request.get(`/api/v1/organism/games/${game}`)).json();
      return snapshot.revision;
    }).toBe(beforeReconnect.revision);
    await expect(b.locator('.organism-dock-actions')).toBeEmpty();
    const afterReconnect = `Still connected ${suffix}`;
    await a.getByPlaceholder('Write to the table…').fill(afterReconnect);
    await a.getByRole('button', { name: 'SEND', exact: true }).click();
    await expect(b.getByText(afterReconnect, { exact: true })).toHaveCount(1);
    for (const page of [a, b, observer]) {
      expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
    }
    await b.screenshot({ path: testInfo.outputPath('waiting-phone.png'), fullPage: true });
    await a.screenshot({ path: testInfo.outputPath('active-desktop.png'), fullPage: true });
    expect(browserErrors).toEqual([]);
  } finally {
    const capture = testInfo.outputPath('legacy-websockets.json');
    await writeFile(capture, JSON.stringify({ game, messages, browserErrors }), { mode: 0o600 });
    await testInfo.attach('legacy-websockets', { path: capture, contentType: 'application/json' });
    await Promise.all(contexts.map(context => context.close()));
  }
});

test('every learning example has a real poster and decodable playback', async ({ page }, testInfo) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/organism/learn');
  const videos = page.locator('video');
  await expect(videos).toHaveCount(10);
  for (let index = 0; index < 10; index++) {
    const video = videos.nth(index);
    await video.scrollIntoViewIfNeeded();
    expect(await video.evaluate(async element => {
      const image = new Image();
      image.src = (element as HTMLVideoElement).poster;
      await image.decode();
      return image.naturalWidth > 0;
    })).toBe(true);
    expect(await video.evaluate(element => (element as HTMLVideoElement).paused)).toBe(true);
    await video.evaluate(element => (element as HTMLVideoElement).play());
    await expect.poll(() => video.evaluate(element => (element as HTMLVideoElement).currentTime)).toBeGreaterThan(0);
    expect(await video.evaluate(element => (element as HTMLVideoElement).videoWidth)).toBeGreaterThan(0);
    await video.evaluate(element => (element as HTMLVideoElement).pause());
  }
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
  }
  await videos.first().scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath('learning-phone.png') });
});

test('missing learning media shows an explanation instead of a black box', async ({ page }) => {
  await page.route('**/video/clip_eat.mp4*', route => route.fulfill({ status: 404, body: 'Missing' }));
  await page.goto('/organism/learn');
  const first = page.locator('.clip').first();
  await first.scrollIntoViewIfNeeded();
  await expect(first.locator('.video-error')).toBeVisible();
  await expect(first.locator('video')).toBeHidden();
  await expect(first.getByRole('link', { name: 'Read the complete rules' })).toHaveAttribute('href', '/img/organism-rulebook.pdf');
});

test('generation GET is read-only and explicit POST starts actual bot progress', async ({ page }) => {
  const counts = () => {
    const database = process.env.ORGANISM_TEST_DATABASE;
    if (!/^organism-acceptance-[0-9a-f-]{36}$/.test(database ?? '')) throw new Error('Expected isolated database');
    return execFileSync('docker', ['exec', process.env.ORGANISM_TEST_MONGO_CONTAINER ?? 'organism-responsive-mongo',
      'mongosh', '--quiet', '--eval', `const d=db.getSiblingDB(${JSON.stringify(database)}); print(JSON.stringify([d.games.countDocuments({}),d.getCollection('open-games').countDocuments({})]));`], { encoding: 'utf8' }).trim();
  };
  const before = counts();
  // GET is read-only; only the explicit confirmation submits creation.
  await page.goto('/organism/generate');
  await expect(page.getByRole('button', { name: 'Generate bot game', exact: true })).toBeVisible();
  await expect(page.locator('.organism-action-dock')).toHaveCount(0);
  expect(counts()).toBe(before);
  const creation = page.waitForResponse(response => response.url().endsWith('/organism/generate') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Generate bot game', exact: true }).click();
  expect((await creation).status()).toBe(200);
  await page.waitForFunction(() => typeof (window as unknown as { playKey?: string }).playKey === 'string');
  const game = await page.evaluate(() => (window as unknown as { playKey: string }).playKey);
  expect(game).toMatch(/^generate-/);
  await expect.poll(async () => {
    const snapshot = await (await page.request.get(`/api/v1/organism/games/${game}`)).json();
    return snapshot.revision;
  }, { timeout: 30_000 }).toBeGreaterThan(0);
});

test('an engine-recorded bot completion is durable and read-only on reopen', async ({ page }, testInfo) => {
  // The owned test server starts the real durable bot runner on a recorded
  // simulation's final engine transition, before any observer is connected.
  // Random simulations are not required to converge within a fixed deadline.
  const game = 'acceptance-bot-completion';
  await page.goto(`/organism/play/${game}`);
  const dock = page.locator('.organism-action-dock');
  await expect(dock).toBeVisible();
  await expect(page.locator('.organism-dock-actions')).toBeEmpty();
  await expect(dock).toContainText('GAME OVER');
  await expect(dock).toContainText(' wins!');
  await expect(page.locator('.organism-dock-actions')).toBeEmpty();
  expect(await boardPointerCount(page)).toBe(0);
  const snapshot = await (await page.request.get(`/api/v1/organism/games/${game}`)).json();
  expect(snapshot.status).toBe('completed');
  expect(snapshot.legalActions).toEqual([]);
  await expect(dock).toContainText(`${snapshot.game.state.winner} wins!`);
  await page.goto(`/organism/play/${game}`);
  await expect(dock).toContainText('GAME OVER');
  await expect(dock).toContainText(`${snapshot.game.state.winner} wins!`);
  await page.screenshot({ path: testInfo.outputPath('bot-result-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
  await page.screenshot({ path: testInfo.outputPath('bot-result-phone.png'), fullPage: true });
  console.log(JSON.stringify({ game, winner: snapshot.game.state.winner, status: snapshot.status, revision: snapshot.revision }));
});
