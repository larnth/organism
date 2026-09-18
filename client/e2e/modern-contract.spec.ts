import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { register } from './auth';

async function snapshot(request: APIRequestContext, game: string) {
  const response = await request.get(`/api/v1/organism/games/${encodeURIComponent(game)}`);
  expect(response.status()).toBe(200);
  expect(response.headers()['cache-control']).toBe('private, no-store');
  return response.json();
}

async function listen(page: Page, game: string) {
  await page.goto('/healthz');
  await page.evaluate(key => {
    const events: unknown[] = [];
    Object.defineProperty(window, '__events', { value: events });
    const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/api/v1/organism/games/${encodeURIComponent(key)}/events`);
    socket.onmessage = message => events.push(JSON.parse(message.data));
    Object.defineProperty(window, '__socket', { value: socket });
  }, game);
  await expect.poll(() => page.evaluate(() => (window as unknown as { __events: unknown[] }).__events.length)).toBeGreaterThan(0);
}

async function lastEvent(page: Page) {
  return page.evaluate(() => (window as unknown as { __events: Array<{ projection: any }> }).__events.at(-1));
}

test('real JSON lobby, scoped streams, idempotent commands, Undo, replay and chat compose', async ({ browser, baseURL }) => {
  const suffix = randomUUID().slice(0, 8);
  const game = `modern contract ${suffix}`;
  const key = encodeURIComponent(game);
  const alice = `json-a-${suffix}`;
  const bob = `json-b-${suffix}`;
  const roomPassword = randomUUID();
  const contexts = await Promise.all([0, 1, 2].map(() => browser.newContext({ baseURL })));
  const [a, b, outsider] = contexts.map(context => context.request);
  const pages = await Promise.all(contexts.map(context => context.newPage()));
  try {
    await register(a, alice);
    await register(b, bob);
    expect((await (await outsider.get('/api/v1/organism/session')).json()).player).toBeNull();
    const bootstrap = await (await a.get('/api/v1/organism/session')).json();
    const create = await a.post(`/api/v1/organism/lobbies/${key}`, { data: {
      invocation: { ...bootstrap.defaults, players: [alice, ''], 'player-count': 2, 'ring-count': 4,
        visibility: 'private', 'lobby-password': roomPassword, description: 'Members only rehearsal' },
    } });
    expect(create.status()).toBe(200);
    const hidden = await snapshot(outsider, game);
    expect(hidden.invocation.players).toEqual(['Occupied', '']);
    expect(hidden.invocation.description).toBeUndefined();
    expect(hidden.chat).toEqual([]);
    expect(hidden.lobby.canStart).toBe(false);
    expect((await b.post(`/api/v1/organism/lobbies/${key}/start`, { data: {} })).status()).toBe(409);
    expect((await b.post(`/api/v1/organism/lobbies/${key}/join`, { data: { index: 1 } })).status()).toBe(409);
    expect((await b.post(`/api/v1/organism/lobbies/${key}/join`, { data: { index: 1, password: roomPassword } })).status()).toBe(200);
    for (const request of [a, b]) {
      expect((await request.post(`/api/v1/organism/lobbies/${key}/ready`, { data: { ready: true } })).status()).toBe(200);
    }
    expect((await snapshot(a, game)).lobby.canStart).toBe(true);
    await Promise.all(pages.map(page => listen(page, game)));
    const started = await a.post(`/api/v1/organism/lobbies/${key}/start`, { data: {} });
    expect(started.status()).toBe(200);
    await expect.poll(async () => (await lastEvent(pages[1]))?.projection.status).toBe('active');
    let active = await snapshot(a, game);
    const initial = active;
    expect(active.viewer.canAct).toBe(true);
    expect((await snapshot(b, game)).legalActions).toEqual([]);
    expect((await snapshot(outsider, game)).legalActions).toEqual([]);
    const command = { actionId: active.legalActions[0].actionId, expectedRevision: active.revision, commandId: randomUUID() };
    const commands = `/api/v1/organism/games/${key}/commands`;
    expect((await b.post(commands, { data: command })).status()).toBe(403);
    expect((await outsider.post(commands, { data: command })).status()).toBe(401);
    const accepted = await a.post(commands, { data: command });
    expect(accepted.status()).toBe(200);
    active = await accepted.json();
    expect(active.revision).toBe(initial.revision + 1);
    const retried = await a.post(commands, { data: command });
    expect(retried.status()).toBe(200);
    expect((await retried.json()).revision).toBe(active.revision);
    expect((await a.post(commands, { data: { ...command, actionId: 'not-the-accepted-command' } })).status()).toBe(409);
    expect((await a.post(commands, { data: { ...command, commandId: randomUUID() } })).status()).toBe(409);
    await expect.poll(async () => (await lastEvent(pages[1]))?.projection.revision).toBe(active.revision);
    expect((await lastEvent(pages[2]))?.projection.legalActions).toEqual([]);
    expect(active.viewer.canUndo).toBe(true);
    const undo = { operation: 'undo', expectedRevision: active.revision, commandId: randomUUID() };
    const undone = await a.post(commands, { data: undo });
    expect(undone.status()).toBe(200);
    const restored = await undone.json();
    expect(restored.revision).toBe(active.revision + 1);
    expect(restored.game).toEqual(initial.game);
    expect(restored.historySummary.entries).toBe(initial.historySummary.entries);
    expect((await (await a.post(commands, { data: undo })).json()).revision).toBe(restored.revision);
    const replay = await (await a.get(`/api/v1/organism/games/${key}/history/0`)).json();
    expect(replay.historyCursor).toBe(0);
    expect(replay.viewer.canAct).toBe(false);
    expect(replay.viewer.canUndo).toBe(false);
    expect(replay.legalActions).toEqual([]);
    const message = { message: `Exactly once ${suffix}`, clientId: randomUUID() };
    const chat = `/api/v1/organism/games/${key}/chat`;
    const delivered = await a.post(chat, { data: message });
    expect(delivered.status()).toBe(200);
    expect((await (await a.post(chat, { data: message })).json())).toEqual(await delivered.json());
    expect((await a.post(chat, { data: { ...message, message: 'Changed content' } })).status()).toBe(409);
    expect((await outsider.post(chat, { data: message })).status()).toBe(401);
    await expect.poll(async () => (await lastEvent(pages[1]))?.projection.chat.filter((entry: any) => entry['client-id'] === message.clientId).length).toBe(1);
    expect((await snapshot(a, game)).revision).toBe(restored.revision);
    // The same command adapter must carry both initial placements and reciprocal handoffs.
    for (const [request, actor, next] of [[a, alice, bob], [b, bob, alice]] as const) {
      let current = await snapshot(request, game);
      for (let step = 0; current.viewer.canAct && step < 60; step++) {
        const action = current.legalActions.find((choice: any) => choice.kind === 'pass') ?? current.legalActions[0];
        expect(action).toBeTruthy();
        const result = await request.post(commands, { data: { actionId: action.actionId,
          expectedRevision: current.revision, commandId: randomUUID() } });
        expect(result.status()).toBe(200);
        current = await result.json();
      }
      expect(current.game.state['player-turn'].player, `${actor}'s handoff`).toBe(next);
      expect(current.viewer.canAct).toBe(false);
      expect(current.legalActions).toEqual([]);
    }
    const checkpointPath = process.env.ORGANISM_TEST_CHECKPOINT;
    if (!checkpointPath) throw new Error('Acceptance checkpoint path is required');
    const checkpoints = await Promise.all([game, 'acceptance-bot-completion'].map(async key => {
      const { gameId, revision, status, game: savedGame, historySummary, chat } = await snapshot(outsider, key);
      return { gameId, revision, status, game: savedGame, historySummary, chat };
    }));
    await writeFile(checkpointPath, JSON.stringify(checkpoints), { mode: 0o600 });
  } finally {
    await Promise.all(contexts.map(context => context.close()));
  }
});
