import { spawn, spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdirSync, openSync, closeSync, existsSync, readFileSync } from 'node:fs';
import { deepStrictEqual } from 'node:assert/strict';
import { createServer } from 'node:net';
import { resolve, delimiter } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const root = fileURLToPath(new URL('../../', import.meta.url));
const owner = randomUUID();
const database = `organism-acceptance-${owner}`;
const container = process.env.ORGANISM_TEST_MONGO_CONTAINER ?? 'organism-responsive-mongo';
const output = resolve(root, 'target', `acceptance-${owner}`);
const env = { ...process.env };
const javaHome = process.env.ORGANISM_TEST_JAVA_HOME
  ?? (existsSync('/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home')
    ? '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home' : env.JAVA_HOME);
if (javaHome) {
  env.JAVA_HOME = javaHome;
  env.PATH = `${javaHome}/bin${delimiter}${env.PATH ?? ''}`;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: root, env, encoding: 'utf8', timeout: 120_000, ...options,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} exited ${result.status}: ${result.stderr ?? ''}`);
  return result.stdout;
}

function mongo(code) {
  return run('docker', ['exec', container, 'mongosh', '--quiet', '--eval', code]);
}

async function availablePort() {
  const listener = createServer();
  await new Promise((resolve, reject) => {
    listener.once('error', reject);
    listener.listen(0, '127.0.0.1', resolve);
  });
  const port = listener.address().port;
  await new Promise((resolve, reject) => listener.close(error => error ? reject(error) : resolve()));
  return port;
}

function alive(pid) {
  // A process group can briefly contain only reaped/unsignalable browser
  // zombies on macOS. Inspect live members instead of using kill(0) as proof.
  const listing = run('ps', ['-axo', 'pid=,pgid=,stat=']);
  return listing.split('\n').some(line => {
    const [, group, state] = line.trim().split(/\s+/);
    return Number(group) === pid && state && !state.startsWith('Z');
  });
}

async function stop(child) {
  if (!child?.pid || !alive(child.pid)) return;
  console.log(`Stopping owned process group ${child.pid}`);
  try { process.kill(-child.pid, 'SIGTERM'); }
  catch (error) { if (alive(child.pid)) throw error; }
  for (let attempt = 0; attempt < 30 && alive(child.pid); attempt++) await delay(100);
  if (alive(child.pid)) {
    try { process.kill(-child.pid, 'SIGKILL'); }
    catch (error) { if (alive(child.pid)) throw error; }
  }
  for (let attempt = 0; attempt < 30 && alive(child.pid); attempt++) await delay(100);
  if (alive(child.pid)) throw new Error(`Owned process group ${child.pid} did not stop; preserving ${database}.`);
}

let server;
let tests;
let ownedDatabase = false;
let interrupted = false;
async function ready(child, baseURL) {
  let startupError;
  child.once('error', error => { startupError = error; });
  for (let attempt = 0; attempt < 180; attempt++) {
    if (startupError) throw startupError;
    if (interrupted) throw new Error('Acceptance interrupted during startup.');
    if (child.exitCode !== null) throw new Error(`Backend stopped before readiness; see ${output}`);
    try {
      const health = await fetch(`${baseURL}/healthz`, { signal: AbortSignal.timeout(1000) });
      const fixture = health.ok && await fetch(`${baseURL}/api/v1/organism/games/acceptance-bot-completion`, { signal: AbortSignal.timeout(1000) });
      if (fixture?.ok && (await fixture.json()).status === 'completed') return;
    } catch { /* Wait for this JVM, not an unrelated existing server. */ }
    await delay(500);
  }
  throw new Error(`Backend not ready after the bounded startup window; see ${output}`);
}
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    interrupted = true;
    if (tests?.pid && alive(tests.pid)) process.kill(-tests.pid, signal);
  });
}

try {
  if (process.argv.length > 2) throw new Error('This runner takes no arguments. Configure Java/Mongo through documented environment variables.');
  // Inspect only the selected local dependency. Never start/stop a shared container.
  const inspected = JSON.parse(run('docker', ['inspect', '--format', '{{json .NetworkSettings.Ports}}', container]));
  const mongoBinding = inspected['27017/tcp']?.find(binding => ['127.0.0.1', '0.0.0.0'].includes(binding.HostIp));
  if (!mongoBinding || !/^\d+$/.test(mongoBinding.HostPort)) throw new Error('Mongo must be published on this machine before acceptance starts.');
  mkdirSync(output, { recursive: true });
  console.log(`Building ORGANISM clients; evidence: ${output}`);
  run('npx', ['--no-install', 'shadow-cljs', 'release', 'organism'], { stdio: 'inherit', timeout: 300_000 });
  run('npm', ['run', 'build', '--prefix', 'client'], { stdio: 'inherit' });
  if (interrupted) throw new Error('Acceptance interrupted before database creation.');
  mongo(`const name=${JSON.stringify(database)}; if(db.getMongo().getDBNames().includes(name)) throw new Error('Database already exists'); db.getSiblingDB(name).acceptanceOwnership.insertOne({_id:${JSON.stringify(owner)}});`);
  ownedDatabase = true;
  const port = await availablePort();
  const baseURL = `http://127.0.0.1:${port}`;
  const checkpoint = resolve(output, 'restart-checkpoint.json');
  const serverArgs = ['with-profile', '+project/test', 'run', '-m', 'clojure.main', 'test/clj/organism/acceptance_server.clj', '--port', String(port)];
  const serverEnv = { ...env, ORGANISM_CLIENT: 'legacy', HOST: '127.0.0.1', PORT: String(port), MONGO_HOST: '127.0.0.1', MONGO_PORT: mongoBinding.HostPort, MONGO_DATABASE: database };
  const serverLog = openSync(resolve(output, 'server.log'), 'w', 0o600);
  server = spawn('lein', serverArgs, {
    cwd: root, detached: true,
    env: serverEnv,
    stdio: ['ignore', serverLog, serverLog],
  });
  closeSync(serverLog);
  await ready(server, baseURL);
  console.log(`Isolated acceptance: ${baseURL}; database ${database}`);
  tests = spawn('npx', ['--no-install', 'playwright', 'test', '--config', 'playwright.acceptance.config.ts', 'legacy-play.spec.ts', 'modern-contract.spec.ts'], {
    cwd: resolve(root, 'client'), detached: true, stdio: 'inherit',
    env: { ...env, ORGANISM_TEST_URL: baseURL, ORGANISM_TEST_DATABASE: database, ORGANISM_TEST_OUTPUT: resolve(output, 'browser'), ORGANISM_TEST_CHECKPOINT: checkpoint },
  });
  const exitCode = await new Promise((resolve, reject) => {
    tests.once('error', reject);
    tests.once('exit', (code, signal) => resolve(signal || code === null ? 1 : code));
  });
  process.exitCode = interrupted ? 130 : exitCode;
  if (!interrupted && exitCode === 0) {
    const saved = JSON.parse(readFileSync(checkpoint, 'utf8'));
    if (!Array.isArray(saved) || saved.length !== 2 || new Set(saved.map(item => item.gameId)).size !== 2) {
      throw new Error('Both active-game and completed-game restart checkpoints are required.');
    }
    await stop(server);
    const restartLog = openSync(resolve(output, 'restart.log'), 'w', 0o600);
    server = spawn('lein', serverArgs, { cwd: root, detached: true, env: { ...serverEnv, ORGANISM_CLIENT: 'modern' }, stdio: ['ignore', restartLog, restartLog] });
    closeSync(restartLog);
    await ready(server, baseURL);
    for (const before of saved) {
      const response = await fetch(`${baseURL}/api/v1/organism/games/${encodeURIComponent(before.gameId)}`);
      if (!response.ok) throw new Error(`Restart could not reopen ${before.gameId}`);
      const after = await response.json();
      deepStrictEqual(Object.fromEntries(Object.keys(before).map(key => [key, after[key]])), before);
    }
    console.log(`Restart verified: ${saved.length} exact durable snapshots, including history, chat, revision and winner.`);
    tests = spawn('npx', ['--no-install', 'playwright', 'test', '--config', 'playwright.acceptance.config.ts', 'modern-play.spec.ts', 'modern-session.spec.ts'], {
      cwd: resolve(root, 'client'), detached: true, stdio: 'inherit',
      env: { ...env, ORGANISM_TEST_URL: baseURL, ORGANISM_TEST_DATABASE: database, ORGANISM_TEST_OUTPUT: resolve(output, 'react-browser') },
    });
    const modernExit = await new Promise((resolve, reject) => {
      tests.once('error', reject);
      tests.once('exit', (code, signal) => resolve(signal || code === null ? 1 : code));
    });
    process.exitCode = interrupted ? 130 : modernExit;
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = interrupted ? 130 : 1;
} finally {
  try {
    await stop(tests);
    await stop(server);
    if (ownedDatabase) {
      mongo(`const name=${JSON.stringify(database)}; const owned=db.getSiblingDB(name); if(!/^organism-acceptance-[0-9a-f-]{36}$/.test(name) || !owned.acceptanceOwnership.findOne({_id:${JSON.stringify(owner)}})) throw new Error('Cleanup refused: ownership marker missing'); const result=owned.dropDatabase(); if(result.ok!==1 || db.getMongo().getDBNames().includes(name)) throw new Error('Database cleanup not verified'); print('Owned acceptance database removed');`);
      console.log(`Cleanup verified: ${database} removed; owned server/test processes stopped.`);
    }
  } catch (error) {
    console.error(`Cleanup needs attention: ${error.message}`);
    process.exitCode = 1;
  }
}
