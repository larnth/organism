import { randomUUID } from 'node:crypto';
import { expect, type APIRequestContext } from '@playwright/test';

export async function csrf(request: APIRequestContext, path: string) {
  const response = await request.get(path);
  expect(response.ok()).toBeTruthy();
  const token = (await response.text()).match(/name="__anti-forgery-token"[^>]*value="([^"]+)"/);
  if (!token) throw new Error('Missing CSRF token');
  return token[1];
}

export async function register(request: APIRequestContext, name: string) {
  // Disposable credentials stay in memory and never enter traces or stdout.
  const password = randomUUID();
  const registration = await request.post('/register', {
    form: { player: name, password, 'password-confirm': password,
      '__anti-forgery-token': await csrf(request, '/register') }, maxRedirects: 0,
  });
  expect(registration.status()).toBe(302);
  const login = await request.post('/login', {
    form: { player: name, password, '__anti-forgery-token': await csrf(request, '/login') }, maxRedirects: 0,
  });
  expect(login.status()).toBe(302);
  const session = await request.get('/api/v1/organism/session');
  expect(session.status()).toBe(200);
  expect((await session.json()).player).toBe(name);
}
