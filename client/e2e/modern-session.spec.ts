import { randomUUID } from 'node:crypto';
import { expect, test, type WebSocket } from '@playwright/test';
import { csrf, register } from './auth';

test('an open private table retires its old socket when shared browser cookies change account', async ({ browser, baseURL }) => {
  const suffix = randomUUID().slice(0, 8);
  const alice = `scope-a-${suffix}`;
  const bob = `scope-b-${suffix}`;
  const game = `Private session boundary ${suffix}`;
  const key = encodeURIComponent(game);
  const contexts = await Promise.all([0, 1].map(() => browser.newContext({ baseURL })));
  try {
    await register(contexts[0].request, alice);
    await register(contexts[1].request, bob);
    const bobIdentity = (await (await contexts[1].request.get('/api/v1/organism/session')).json()).player;
    const authority = await browser.newContext({ baseURL, storageState: await contexts[0].storageState() });
    contexts.push(authority);
    const bootstrap = await (await authority.request.get('/api/v1/organism/session')).json();
    expect((await authority.request.post(`/api/v1/organism/lobbies/${key}`, { data: {
      invocation: { ...bootstrap.defaults, players: [alice, ''], 'player-count': 2, 'ring-count': 4,
        visibility: 'private', 'lobby-password': randomUUID(), description: 'Private description must disappear' },
    } })).status()).toBe(200);
    const before = `Private before account change ${suffix}`;
    const after = `Private after account change ${suffix}`;
    const chat = `/api/v1/organism/games/${key}/chat`;
    expect((await authority.request.post(chat, { data: { message: before, clientId: randomUUID() } })).status()).toBe(200);
    const page = await contexts[0].newPage();
    const sockets: WebSocket[] = [];
    const closed = new Set<WebSocket>();
    page.on('websocket', socket => { sockets.push(socket); socket.on('close', () => closed.add(socket)); });
    await page.goto(`/modern/?game=${key}`);
    await expect(page.getByText(before, { exact: true })).toBeVisible();
    await page.getByRole('textbox', { name: 'Message' }).fill('Unsent private draft');
    await expect.poll(() => sockets.length).toBeGreaterThan(0);
    const originalSocket = sockets[0];

    // Same cookie jar, same open document: this models another tab signing in.
    // Cookie values stay in memory and are never logged or written to fixtures.
    await contexts[0].addCookies(await contexts[1].cookies(baseURL));
    expect((await (await contexts[0].request.get(`/api/v1/organism/games/${key}`)).json()).viewer.player).toBe(bobIdentity);
    await expect(page.getByText(before, { exact: true })).toHaveCount(0, { timeout: 15000 });
    await expect(page.getByText('Private description must disappear', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: 'Message' })).toHaveCount(0);
    await expect.poll(() => closed.has(originalSocket)).toBe(true);
    await expect.poll(() => sockets.length).toBeGreaterThan(1);
    expect((await (await contexts[0].request.get(`/api/v1/organism/games/${key}`)).json()).viewer.player).toBe(bobIdentity);
    expect((await authority.request.post(chat, { data: { message: after, clientId: randomUUID() } })).status()).toBe(200);
    await expect(page.getByText(after, { exact: true })).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: 'Message' })).toHaveCount(0);

    await contexts[0].clearCookies();
    await expect(page.getByRole('link', { name: 'Sign in to join this table' })).toBeVisible({ timeout: 15000 });
    await expect(page.getByText(before, { exact: true })).toHaveCount(0);
    await expect(page.getByText(after, { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Start game', exact: true })).toHaveCount(0);
  } finally {
    await Promise.all(contexts.map(context => context.close()));
  }
});

test('replacement waiting tables reject delayed private deliveries and preserve their own launch identity', async ({ browser, baseURL }) => {
  const suffix = randomUUID().slice(0, 8);
  const alice = `lifetime-a-${suffix}`;
  const bob = `lifetime-b-${suffix}`;
  const game = `Reusable private table ${suffix}`;
  const key = encodeURIComponent(game);
  const lobbyUrl = `/api/v1/organism/lobbies/${key}`;
  const gameUrl = `/api/v1/organism/games/${key}`;
  const contexts = await Promise.all([0, 1].map(() => browser.newContext({ baseURL })));
  let releaseChat = () => {};
  let releaseReads = () => {};
  try {
    await register(contexts[0].request, alice);
    await register(contexts[1].request, bob);
    const owner = contexts[0].request;
    const session = await (await owner.get('/api/v1/organism/session')).json();
    const password = randomUUID();
    const creation = { createOnly: true, invocation: { ...session.defaults,
      players: [alice, ''], 'player-count': 2, 'ring-count': 4,
      visibility: 'private', 'lobby-password': password } };
    const original = await (await owner.post(lobbyUrl, { data: creation })).json();
    expect(original.instanceId).toEqual(expect.any(String));
    const page = await contexts[0].newPage();
    await page.goto(`/modern/?game=${key}`);
    await expect(page.getByRole('textbox', { name: 'Message' })).toBeVisible();
    const chatGate = new Promise<void>(resolve => { releaseChat = resolve; });
    let sawChat!: (value: { expectedInstanceId: string }) => void;
    const chatSent = new Promise<{ expectedInstanceId: string }>(resolve => { sawChat = resolve; });
    await page.route(`**${gameUrl}/chat`, async route => {
      sawChat(route.request().postDataJSON());
      await chatGate;
      await route.continue().catch(() => {});
    });
    await page.getByRole('textbox', { name: 'Message' }).fill('Private text for the retired table');
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    expect((await chatSent).expectedInstanceId).toBe(original.instanceId);
    // Hold refreshes across the gap so replacement must be detected by lifetime,
    // not accidentally by an intermediate 404 or a changed player/game name.
    const readGate = new Promise<void>(resolve => { releaseReads = resolve; });
    await page.route(`**${gameUrl}`, async route => { await readGate; await route.continue().catch(() => {}); });
    const deletion = await owner.post(`/organism/play/${key}/delete`, { form: {
      '__anti-forgery-token': await csrf(owner, '/organism/generate'),
    } });
    expect(deletion.status()).toBe(200);
    const replacement = await (await owner.post(lobbyUrl, { data: creation })).json();
    expect(replacement.instanceId).toEqual(expect.any(String));
    expect(replacement.instanceId).not.toBe(original.instanceId);
    expect((await contexts[1].request.post(`${lobbyUrl}/join`, { data: {
      index: 1, password, expectedInstanceId: replacement.instanceId,
    } })).status()).toBe(200);
    releaseReads();
    await expect(page.getByRole('textbox', { name: 'Message' })).toHaveValue('', { timeout: 15000 });
    await expect(page.getByRole('textbox', { name: 'Message' })).not.toHaveAttribute('readonly');
    const late = page.waitForResponse(response => response.url().endsWith(`${gameUrl}/chat`));
    releaseChat();
    expect((await late).status()).toBe(409);
    for (const [operation, body] of [['ready', { ready: true }], ['kick', { index: 1 }]] as const) {
      expect((await owner.post(`${lobbyUrl}/${operation}`, { data: { ...body, expectedInstanceId: original.instanceId } })).status()).toBe(409);
    }
    const clean = await (await contexts[1].request.get(gameUrl)).json();
    expect(clean.chat).toEqual([]);
    expect(clean.invocation.players).toContain(bob);
    expect(clean.lobby.readiness[alice]).not.toBe(true);
    await page.getByRole('textbox', { name: 'Message' }).fill('A draft for this replacement table');
    for (const context of contexts) expect((await context.request.post(`${lobbyUrl}/ready`, { data: { ready: true, expectedInstanceId: replacement.instanceId } })).status()).toBe(200);
    const started = await (await owner.post(`${lobbyUrl}/start`, { data: { expectedInstanceId: replacement.instanceId } })).json();
    expect(started.instanceId).toBe(replacement.instanceId);
    await expect(page.getByRole('img', { name: `${game} game board`, exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Scores, history, help and discussion' }).click();
    await expect(page.getByRole('textbox', { name: 'Message' })).toHaveValue('A draft for this replacement table');
    await expect(page.getByText('Private text for the retired table', { exact: true })).toHaveCount(0);
  } finally {
    releaseChat(); releaseReads();
    await Promise.all(contexts.map(context => context.close()));
  }
});
