import test from 'node:test';
import assert from 'node:assert/strict';
import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createSetupController, runCommand } from '../server/setup.js';

const KEY = 'private-scanner-key-that-must-not-leak';
const ZAP_KEY = 'private-zap-key-that-must-not-leak';
const TRUSTED = {
  'docker-compose.yml': 'services: fixture-only\n',
  '.env.example': 'IMAGE_TAG=v0.16.0\nMCP_API_KEY=placeholder\n',
  'bin/bootstrap-local.sh': '#!/bin/bash\n# trusted bootstrap fixture\n',
  'bin/self-serve-doctor.sh': '#!/bin/bash\n# trusted doctor fixture\n',
};
const ok = (stdout = '') => ({ code: 0, stdout, stderr: '' });
const envText = (key = KEY) => `MCP_API_KEY=${key}\nZAP_API_KEY=${ZAP_KEY}\nMCP_SECURITY_MODE=api-key\nMCP_SECURITY_ENABLED=true\nJWT_ENABLED=false\nIMAGE_TAG=v0.16.0\n`;

function scanner(directory, { id = 'a'.repeat(64), healthy = true, running = true, host = '127.0.0.1', key = KEY, project = 'scanner' } = {}) {
  return {
    Id: id,
    Config: {
      Image: 'dtkmn/mcp-zap-server:v0.16.0',
      Labels: {
        'com.docker.compose.project': project,
        'com.docker.compose.service': 'mcp-server',
        'com.docker.compose.project.working_dir': directory,
        'com.docker.compose.project.config_files': path.join(directory, 'docker-compose.yml'),
      },
      Env: [`MCP_API_KEY=${key}`, `ZAP_API_KEY=${ZAP_KEY}`, 'MCP_SECURITY_MODE=api-key', 'MCP_SECURITY_ENABLED=true', 'JWT_ENABLED=false'],
    },
    State: { Running: running, Health: { Status: healthy ? 'healthy' : 'unhealthy' } },
    HostConfig: { PortBindings: { '7456/tcp': [{ HostIp: host, HostPort: '7456' }] } },
  };
}

async function fixture(t, { existing = true, containers, env = {}, runnerOverride, probePort = async () => false } = {}) {
  const base = await fs.mkdtemp(path.join(await fs.realpath(os.tmpdir()), 'mcp-zap-setup-test-'));
  t.after(() => fs.rm(base, { recursive: true, force: true }));
  const home = path.join(base, 'home');
  const bundleRoot = path.join(base, 'bundle');
  const directory = existing ? path.join(home, 'scanner') : path.join(home, '.mcp-zap-server');
  await fs.mkdir(home, { mode: 0o700 });
  await fs.mkdir(path.join(bundleRoot, 'resources/scanner/bin'), { recursive: true, mode: 0o700 });
  for (const [relative, bytes] of Object.entries(TRUSTED)) await fs.writeFile(path.join(bundleRoot, 'resources/scanner', relative), bytes, { mode: 0o600 });
  if (existing) {
    await fs.mkdir(path.join(directory, 'bin'), { recursive: true, mode: 0o700 });
    for (const [relative, bytes] of Object.entries(TRUSTED)) await fs.writeFile(path.join(directory, relative), bytes, { mode: 0o600 });
    await fs.writeFile(path.join(directory, '.env'), envText(), { mode: 0o600 });
  }
  const state = { containers: containers ?? (existing ? [scanner(directory)] : []), calls: [], onBootstrap: null, onDoctor: null };
  const runner = async (command, args, options) => {
    state.calls.push({ command, args, options });
    const overridden = await runnerOverride?.(command, args, options, state);
    if (overridden !== undefined) return overridden;
    if (command === 'docker' && args[0] === 'ps') return ok(state.containers.map((container) => container.Id).join('\n'));
    if (command === 'docker' && args[0] === 'inspect') return ok(JSON.stringify(state.containers));
    if (command === 'docker' && args[0] === 'context') return ok(JSON.stringify('unix:///private/docker.sock'));
    if (command === 'docker' && args.includes('--help')) return ok('  --wait  Wait for services\n  --wait-timeout  Timeout\n');
    if (command === '/bin/bash' && args[0]?.endsWith('bootstrap-local.sh')) return state.onBootstrap ? state.onBootstrap() : ok();
    if (command === '/bin/bash' && args[0]?.endsWith('self-serve-doctor.sh')) return state.onDoctor ? state.onDoctor() : ok();
    if (['docker', '/bin/bash', 'curl', 'openssl'].includes(command)) return ok('fixture version');
    return { code: 127, stdout: '', stderr: 'not found' };
  };
  const controller = createSetupController({ home, bundleRoot, platform: 'darwin', runner, probePort, env: { PATH: '/fixture/bin', ...env } });
  return { ...state, state, controller, base, directory, bundleRoot, home, runner };
}

test('construction and unconfirmed setup have no runtime or installation side effects', async (t) => {
  const f = await fixture(t, { existing: false });
  assert.equal(f.state.calls.length, 0);
  assert.equal((await f.controller.setup({ confirm: false })).code, 'CONFIRMATION_REQUIRED');
  assert.equal(f.state.calls.length, 0);
  await assert.rejects(fs.stat(f.directory), { code: 'ENOENT' });
});

test('healthy installation is discovered and credentials stay internal', async (t) => {
  const f = await fixture(t);
  const prerequisites = await f.controller.prerequisites();
  assert.equal(prerequisites.ok, true);
  assert.equal(prerequisites.healthy, true);
  assert.equal(JSON.stringify(prerequisites).includes(KEY), false);
  assert.deepEqual(await f.controller.connection(), { url: 'http://127.0.0.1:7456/mcp', apiKey: KEY, identity: `unix:///private/docker.sock\0${'a'.repeat(64)}` });
  const result = await f.controller.setup({ confirm: true });
  assert.equal(result.code, 'SCANNER_REUSED');
  assert.equal(result.changed, false);
  const scripts = f.state.calls.filter((call) => call.command === '/bin/bash' && !call.args.includes('--version'));
  assert.equal(scripts.length, 1);
  assert.deepEqual(scripts[0].args, [path.join(f.directory, 'bin/self-serve-doctor.sh'), '--skip-tool-call']);
  assert.equal(scripts[0].options.env.MCP_API_KEY, KEY);
  assert.equal(JSON.stringify(scripts[0].args).includes(KEY), false);
});

test('missing Docker returns installation guidance without leaking diagnostics', async (t) => {
  const f = await fixture(t, { existing: false, runnerOverride: (command) => command === 'docker' ? { code: 127, stdout: KEY, stderr: ZAP_KEY } : undefined });
  const result = await f.controller.prerequisites();
  assert.equal(result.code, 'DOCKER_MISSING');
  assert.equal(result.helpUrl, 'https://docs.docker.com/get-docker/');
  assert.equal(JSON.stringify(result).includes(KEY), false);
  assert.equal(JSON.stringify(result).includes(ZAP_KEY), false);
  await assert.rejects(fs.stat(f.directory), { code: 'ENOENT' });
});

test('duplicates and unrelated Docker port ownership fail closed', async (t) => {
  const f = await fixture(t);
  f.state.containers.push(scanner(f.directory, { id: 'b'.repeat(64) }));
  assert.equal((await f.controller.prerequisites()).code, 'DUPLICATE_SCANNERS');
  f.state.containers.pop();
  f.state.containers.push({ ...scanner(f.directory, { id: 'b'.repeat(64), project: 'unrelated' }), Config: { Image: 'unrelated/app:1', Labels: { 'com.docker.compose.project': 'unrelated' }, Env: [] } });
  assert.equal((await f.controller.prerequisites()).code, 'PORT_CONFLICT');
});

test('an unrelated generic mcp-server service is not adopted as this product', async (t) => {
  const f = await fixture(t, { existing: false });
  const foreign = scanner('/unrelated/checkout', { project: 'foreign' });
  foreign.Config.Image = 'unrelated/mcp-server:1';
  foreign.Config.Env = [];
  foreign.HostConfig.PortBindings = {};
  f.state.containers.push(foreign);
  const result = await f.controller.prerequisites();
  assert.equal(result.ok, true);
  assert.equal(result.existingScanner, false);
  assert.equal(result.setupRequired, true);
  foreign.HostConfig.PortBindings = { '7456/tcp': [{ HostIp: '127.0.0.1', HostPort: '7456' }] };
  assert.equal((await f.controller.prerequisites()).code, 'PORT_CONFLICT');
});

test('healthy reuse preserves unrelated host and Docker listeners on demo ports', async (t) => {
  const probed = [];
  const f = await fixture(t, { probePort: async (port) => { probed.push(port); return port === 3001; } });
  const foreign = scanner('/unrelated/checkout', { id: 'b'.repeat(64), project: 'foreign' });
  foreign.Config.Image = 'unrelated/demo:1';
  foreign.Config.Labels['com.docker.compose.service'] = 'demo';
  foreign.HostConfig.PortBindings = { '80/tcp': [{ HostIp: '127.0.0.1', HostPort: '3002' }] };
  f.state.containers.push(foreign);
  assert.equal((await f.controller.setup({ confirm: true })).code, 'SCANNER_REUSED');
  assert.deepEqual(probed, []);
  assert.equal(f.state.calls.some((call) => call.args[0]?.endsWith('bootstrap-local.sh')), false);
  f.state.containers[0].State.Running = false;
  assert.equal((await f.controller.setup({ confirm: true })).code, 'PORT_CONFLICT');
});

test('another checkout using the same Compose project is not adopted', async (t) => {
  const f = await fixture(t);
  const unrelated = scanner(f.directory, { id: 'b'.repeat(64) });
  unrelated.Config.Image = 'zaproxy/zap-stable:2.17.0';
  unrelated.Config.Labels['com.docker.compose.service'] = 'zap';
  unrelated.Config.Labels['com.docker.compose.project.working_dir'] = path.join(f.home, 'other');
  f.state.containers.push(unrelated);
  assert.equal((await f.controller.prerequisites()).code, 'OWNERSHIP_CONFLICT');
});

test('explicit directory cannot replace the detected owner', async (t) => {
  const f = await fixture(t, { env: { MCP_ZAP_INSTALL_DIR: '/different/checkout' } });
  assert.equal((await f.controller.prerequisites()).code, 'OWNERSHIP_CONFLICT');
});

test('inherited and configured credentials are compared independently', async (t) => {
  for (const key of ['MCP_API_KEY', 'MCP_ZAP_API_KEY']) {
    const f = await fixture(t, { env: { [key]: 'conflicting-private-value' } });
    const result = await f.controller.setup({ confirm: true });
    assert.equal(result.code, 'CREDENTIAL_CONFLICT');
    assert.equal(JSON.stringify(result).includes('conflicting-private-value'), false);
    assert.equal(f.state.calls.some((call) => call.args[0]?.endsWith('bootstrap-local.sh')), false);
  }
});

test('private dotenv values are read as data and must match the effective key', async (t) => {
  const f = await fixture(t);
  await fs.writeFile(path.join(f.directory, '.env'), envText('$(do-not-execute-this)'), { mode: 0o600 });
  const result = await f.controller.doctor();
  assert.equal(result.code, 'CREDENTIAL_CONFLICT');
  assert.equal(f.state.calls.some((call) => call.args.join(' ').includes('do-not-execute')), false);
});

test('API-key mode and loopback-only publication are required', async (t) => {
  const f = await fixture(t);
  f.state.containers[0].HostConfig.PortBindings['7456/tcp'][0].HostIp = '0.0.0.0';
  assert.equal((await f.controller.prerequisites()).code, 'UNSAFE_BINDING');
  f.state.containers[0].HostConfig.PortBindings['7456/tcp'][0].HostIp = '127.0.0.1';
  f.state.containers[0].Config.Env.push('MCP_SECURITY_MODE=jwt');
  assert.equal((await f.controller.prerequisites()).code, 'AUTH_UNSUPPORTED');
});

test('API-key request mode preserves independently enabled JWT services', async (t) => {
  const f = await fixture(t);
  f.state.containers[0].Config.Env.push('JWT_ENABLED=true');
  await fs.writeFile(path.join(f.directory, '.env'), envText().replace('JWT_ENABLED=false', 'JWT_ENABLED=true'), { mode: 0o600 });
  const connection = await f.controller.connection();
  assert.equal(connection.apiKey, KEY);
  const outcome = await f.controller.setup({ confirm: true });
  assert.equal(outcome.code, 'SCANNER_REUSED');
  assert.match(await fs.readFile(path.join(f.directory, '.env'), 'utf8'), /JWT_ENABLED=true/);
  assert.equal(f.state.calls.some((call) => call.args[0]?.endsWith('bootstrap-local.sh')), false);
});

test('a symlink in the installation ancestry is rejected', async (t) => {
  const f = await fixture(t, { existing: false });
  const real = path.join(f.home, 'real');
  await fs.mkdir(real);
  await fs.symlink(real, path.join(f.home, 'linked'));
  const controller = createSetupController({ home: f.home, bundleRoot: f.bundleRoot, platform: 'darwin', runner: f.runner, probePort: async () => false, env: { MCP_ZAP_INSTALL_DIR: path.join(f.home, 'linked/scanner') } });
  assert.equal((await controller.prerequisites()).code, 'UNSAFE_INSTALL_DIRECTORY');
  await assert.rejects(fs.stat(path.join(real, 'scanner')), { code: 'ENOENT' });
});

test('private env symlinks and shared-readable credentials are rejected', async (t) => {
  const f = await fixture(t);
  await fs.chmod(path.join(f.directory, '.env'), 0o644);
  assert.equal((await f.controller.prerequisites()).code, 'INSECURE_ENV_FILE');
  await fs.unlink(path.join(f.directory, '.env'));
  const external = path.join(f.home, 'private-env');
  await fs.writeFile(external, envText(), { mode: 0o600 });
  await fs.symlink(external, path.join(f.directory, '.env'));
  assert.equal((await f.controller.prerequisites()).code, 'UNSAFE_INSTALL_FILE');
});

test('healthy reuse accepts an older unused bootstrap without changing private settings', async (t) => {
  const f = await fixture(t);
  const bootstrap = path.join(f.directory, 'bin/bootstrap-local.sh');
  const olderBootstrap = '#!/bin/bash\n# previous release bootstrap fixture\n';
  await fs.writeFile(bootstrap, olderBootstrap);
  const settings = await fs.readFile(path.join(f.directory, '.env'));
  const mode = (await fs.stat(path.join(f.directory, '.env'))).mode;
  assert.equal((await f.controller.doctor()).code, 'READY');
  const reused = await f.controller.setup({ confirm: true });
  assert.equal(reused.code, 'SCANNER_REUSED');
  assert.equal(reused.changed, false);
  assert.deepEqual(await fs.readFile(path.join(f.directory, '.env')), settings);
  assert.equal((await fs.stat(path.join(f.directory, '.env'))).mode, mode);
  assert.equal(await fs.readFile(bootstrap, 'utf8'), olderBootstrap);
  assert.equal(f.state.calls.some((call) => call.args[0]?.endsWith('bootstrap-local.sh')), false);
});

test('resuming an older bootstrap requires manual review and preserves the installation', async (t) => {
  const f = await fixture(t);
  const bootstrap = path.join(f.directory, 'bin/bootstrap-local.sh');
  const olderBootstrap = '#!/bin/bash\n# previous release bootstrap fixture\n';
  await fs.writeFile(bootstrap, olderBootstrap);
  f.state.containers[0].State.Running = false;
  const settings = await fs.readFile(path.join(f.directory, '.env'));
  assert.equal((await f.controller.setup({ confirm: true })).code, 'SCRIPT_MISMATCH');
  assert.deepEqual(await fs.readFile(path.join(f.directory, '.env')), settings);
  assert.equal(await fs.readFile(bootstrap, 'utf8'), olderBootstrap);
  assert.equal(f.state.calls.some((call) => call.args[0]?.endsWith('.sh')), false);
});

test('modified doctor scripts are never executed during healthy reuse or resume', async (t) => {
  const f = await fixture(t);
  await fs.writeFile(path.join(f.directory, 'bin/self-serve-doctor.sh'), '#!/bin/bash\nmalicious-command\n');
  assert.equal((await f.controller.doctor()).code, 'SCRIPT_MISMATCH');
  assert.equal((await f.controller.setup({ confirm: true })).code, 'SCRIPT_MISMATCH');
  f.state.containers[0].State.Running = false;
  assert.equal((await f.controller.setup({ confirm: true })).code, 'SCRIPT_MISMATCH');
  assert.equal(f.state.calls.some((call) => call.args[0]?.endsWith('.sh')), false);
});

test('a linked doctor is rejected even when it contains the trusted bytes', async (t) => {
  const f = await fixture(t);
  const doctor = path.join(f.directory, 'bin/self-serve-doctor.sh');
  const external = path.join(f.home, 'external-doctor.sh');
  await fs.writeFile(external, TRUSTED['bin/self-serve-doctor.sh']);
  await fs.unlink(doctor);
  await fs.symlink(external, doctor);
  assert.equal((await f.controller.doctor()).code, 'UNSAFE_INSTALL_FILE');
  assert.equal((await f.controller.setup({ confirm: true })).code, 'UNSAFE_INSTALL_FILE');
  assert.equal(f.state.calls.some((call) => call.args[0]?.endsWith('.sh')), false);
});

test('custom Compose settings are preserved for reuse but never started', async (t) => {
  const f = await fixture(t);
  const compose = path.join(f.directory, 'docker-compose.yml');
  await fs.writeFile(compose, 'services: customized\n');
  assert.equal((await f.controller.setup({ confirm: true })).code, 'SCANNER_REUSED');
  f.state.containers[0].State.Running = false;
  assert.equal((await f.controller.setup({ confirm: true })).code, 'START_MANUALLY');
  assert.equal(await fs.readFile(compose, 'utf8'), 'services: customized\n');
});

test('foreign host listeners prevent any staging', async (t) => {
  const f = await fixture(t, { existing: false, probePort: async (port) => port === 3001 });
  assert.equal((await f.controller.setup({ confirm: true })).code, 'PORT_CONFLICT');
  await assert.rejects(fs.stat(f.directory), { code: 'ENOENT' });
});

test('confirmed new setup stages exact files privately and only invokes fixed bootstrap', async (t) => {
  const f = await fixture(t, { existing: false });
  f.state.onBootstrap = async () => {
    await fs.writeFile(path.join(f.directory, '.env'), envText(), { mode: 0o600 });
    f.state.containers = [scanner(f.directory, { project: 'mcp-zap-server' })];
    return ok('private diagnostics ' + KEY);
  };
  const result = await f.controller.setup({ confirm: true });
  assert.equal(result.code, 'SCANNER_STARTED');
  assert.equal(JSON.stringify(result).includes(KEY), false);
  assert.equal((await fs.stat(f.directory)).mode & 0o777, 0o700);
  for (const [relative, bytes] of Object.entries(TRUSTED)) assert.equal(await fs.readFile(path.join(f.directory, relative), 'utf8'), bytes);
  const invocation = f.state.calls.find((call) => call.args[0]?.endsWith('bootstrap-local.sh'));
  assert.deepEqual(invocation.args, [path.join(f.directory, 'bin/bootstrap-local.sh'), '--start']);
  assert.equal(invocation.options.timeoutMs, 420_000);
});

test('doctor timeout and thrown errors never expose private output', async (t) => {
  const f = await fixture(t);
  f.state.onDoctor = () => ({ code: 1, timedOut: true, stdout: KEY, stderr: ZAP_KEY });
  const result = await f.controller.doctor();
  assert.equal(result.code, 'DOCTOR_FAILED');
  assert.equal(JSON.stringify(result).includes(KEY), false);
  f.state.onDoctor = () => { throw new Error(KEY); };
  assert.equal(JSON.stringify(await f.controller.doctor()).includes(KEY), false);
});

test('setup is serialized and connection cannot race a running bootstrap', async (t) => {
  const f = await fixture(t, { existing: false });
  let release, entered;
  const enteredPromise = new Promise((resolve) => { entered = resolve; });
  f.state.onBootstrap = () => { entered(); return new Promise((resolve) => { release = resolve; }); };
  const first = f.controller.setup({ confirm: true });
  await enteredPromise;
  assert.equal((await f.controller.setup({ confirm: true })).code, 'SETUP_BUSY');
  await assert.rejects(f.controller.connection(), { code: 'SETUP_BUSY' });
  release({ code: 1, stdout: KEY, stderr: KEY });
  assert.equal((await first).code, 'SETUP_FAILED');
});

test('shell startup hooks are not passed to fixed scripts and remote Docker is refused', async (t) => {
  const f = await fixture(t, { env: { BASH_ENV: '/untrusted/startup', SHELLOPTS: 'xtrace' } });
  assert.equal((await f.controller.doctor()).ok, true);
  const invocation = f.state.calls.find((call) => call.args[0]?.endsWith('self-serve-doctor.sh'));
  assert.equal(invocation.options.env.BASH_ENV, undefined);
  assert.equal(invocation.options.env.SHELLOPTS, undefined);
  const remote = await fixture(t, { env: { DOCKER_HOST: 'tcp://remote.invalid:2375' } });
  assert.equal((await remote.controller.prerequisites()).code, 'REMOTE_DOCKER_UNSUPPORTED');
  assert.equal(remote.state.calls.length, 0);
});

test('connection also rejects a remote selected Docker context before reading containers', async (t) => {
  const f = await fixture(t, { runnerOverride: (command, args) => command === 'docker' && args[0] === 'context' ? ok(JSON.stringify('ssh://remote.invalid')) : undefined });
  await assert.rejects(f.controller.connection(), { code: 'REMOTE_DOCKER_UNSUPPORTED' });
  assert.equal(f.state.calls.some((call) => call.args[0] === 'inspect'), false);
});

test('Docker can stop or change contexts after an earlier healthy connection without causing recovery side effects', async (t) => {
  let available = true;
  let endpoint = 'unix:///private/docker.sock';
  const f = await fixture(t, { runnerOverride: (command, args) => {
    if (command === 'docker' && args[0] === 'context') return ok(JSON.stringify(endpoint));
    if (command === 'docker' && args[0] === 'ps' && !available) return { code: 1, stdout: KEY, stderr: ZAP_KEY };
    return undefined;
  } });
  await f.controller.connection();
  available = false;
  await assert.rejects(f.controller.connection(), { code: 'DOCKER_UNAVAILABLE' });
  const offline = await f.controller.doctor();
  assert.equal(offline.code, 'DOCKER_UNAVAILABLE');
  assert.equal(offline.recovery.action, 'start_docker');
  assert.equal(offline.recovery.automaticRetry, false);
  assert.doesNotMatch(JSON.stringify(offline), /private-scanner-key|private-zap-key/);
  endpoint = 'ssh://remote.invalid';
  const before = f.state.calls.length;
  await assert.rejects(f.controller.connection(), { code: 'REMOTE_DOCKER_UNSUPPORTED' });
  assert.equal(f.state.calls.slice(before).some((call) => ['ps', 'inspect', 'info'].includes(call.args[0])), false);
  assert.equal(f.state.calls.some((call) => call.args[0]?.endsWith('bootstrap-local.sh')), false);
});

test('readiness distinguishes stopped, unhealthy and removed installations without starting them', async (t) => {
  const f = await fixture(t, { env: {} });
  const explicit = createSetupController({ home: f.home, bundleRoot: f.bundleRoot, platform: 'darwin', runner: f.runner, probePort: async () => false, env: { MCP_ZAP_INSTALL_DIR: f.directory } });
  const before = await fs.readFile(path.join(f.directory, '.env'));
  f.state.containers[0].State.Running = false;
  assert.equal((await explicit.doctor()).code, 'SCANNER_STOPPED');
  f.state.containers[0].State.Running = true;
  f.state.containers[0].State.Health.Status = 'unhealthy';
  assert.equal((await explicit.doctor()).code, 'SCANNER_UNHEALTHY');
  f.state.containers = [];
  const removed = await explicit.doctor();
  assert.equal(removed.code, 'SCANNER_REMOVED');
  assert.equal(removed.recovery.needsConfirmation, true);
  assert.equal(f.state.calls.some((call) => call.args[0]?.endsWith('bootstrap-local.sh')), false);
  assert.deepEqual(await fs.readFile(path.join(f.directory, '.env')), before);
});

test('explicitly approved resume preserves private settings and never replaces keys', async (t) => {
  const f = await fixture(t);
  f.state.containers[0].State.Running = false;
  const before = await fs.readFile(path.join(f.directory, '.env'));
  const mode = (await fs.stat(path.join(f.directory, '.env'))).mode;
  assert.equal((await f.controller.doctor()).code, 'SCANNER_STOPPED');
  assert.equal((await f.controller.setup({ confirm: false })).code, 'CONFIRMATION_REQUIRED');
  assert.equal(f.state.calls.some((call) => call.args[0]?.endsWith('bootstrap-local.sh')), false);
  f.state.onBootstrap = () => { f.state.containers[0].State.Running = true; return ok(); };
  const resumed = await f.controller.setup({ confirm: true });
  assert.equal(resumed.code, 'SCANNER_STARTED');
  assert.deepEqual(await fs.readFile(path.join(f.directory, '.env')), before);
  assert.equal((await fs.stat(path.join(f.directory, '.env'))).mode, mode);
  const bootstrap = f.state.calls.filter((call) => call.args[0]?.endsWith('bootstrap-local.sh'));
  assert.equal(bootstrap.length, 1);
  assert.deepEqual(bootstrap[0].args.slice(1), ['--start']);
});

test('missing or incomplete private settings are never regenerated during recovery', async (t) => {
  const f = await fixture(t);
  f.state.containers[0].State.Running = false;
  await fs.unlink(path.join(f.directory, '.env'));
  assert.equal((await f.controller.setup({ confirm: true })).code, 'START_MANUALLY');
  await assert.rejects(fs.stat(path.join(f.directory, '.env')), { code: 'ENOENT' });
  await fs.writeFile(path.join(f.directory, '.env'), `MCP_API_KEY=${KEY}\n`, { mode: 0o600 });
  assert.equal((await f.controller.setup({ confirm: true })).code, 'START_MANUALLY');
  assert.equal(await fs.readFile(path.join(f.directory, '.env'), 'utf8'), `MCP_API_KEY=${KEY}\n`);
  assert.equal(f.state.calls.some((call) => call.args[0]?.endsWith('bootstrap-local.sh')), false);
});

test('each connection checks current container identity and credential consistency', async (t) => {
  const f = await fixture(t);
  const first = await f.controller.connection();
  f.state.containers[0].Id = 'b'.repeat(64);
  const replaced = await f.controller.connection();
  assert.notEqual(replaced.identity, first.identity);
  f.state.containers[0].Config.Env.push('MCP_API_KEY=changed-private-key');
  await assert.rejects(f.controller.connection(), { code: 'CREDENTIAL_CONFLICT' });
  assert.equal((await f.controller.doctor()).code, 'CREDENTIAL_CONFLICT');
  assert.equal(f.state.calls.some((call) => call.args[0]?.endsWith('bootstrap-local.sh')), false);
});

// These processes are only Node timers. Both ignore SIGTERM so the tests prove
// that the real runner escalates to the complete group, including grandchildren.
const PROCESS_TREE = `
  const { spawn } = require('node:child_process');
  const { writeFileSync } = require('node:fs');
  process.on('SIGTERM', () => {});
  const child = spawn(process.execPath, ['-e', 'process.on("SIGTERM", () => {}); process.stdout.write("ready"); setInterval(() => {}, 1000);'], { stdio: ['ignore', 'pipe', 'ignore'] });
  child.stdout.once('data', () => writeFileSync(process.env.MCP_ZAP_TEST_PID_FILE, JSON.stringify([process.pid, child.pid])));
  setInterval(() => {}, 1000);
`;

async function processFixture(t) {
  const base = await fs.mkdtemp(path.join(await fs.realpath(os.tmpdir()), 'mcp-zap-process-test-'));
  const pidFile = path.join(base, 'pids.json');
  t.after(async () => {
    try {
      const [group] = JSON.parse(await fs.readFile(pidFile, 'utf8'));
      if (Number.isSafeInteger(group) && group > 1) { try { process.kill(-group, 'SIGKILL'); } catch {} }
    } catch {}
    await fs.rm(base, { recursive: true, force: true });
  });
  return { pidFile, env: { ...process.env, MCP_ZAP_TEST_PID_FILE: pidFile } };
}

async function assertProcessesStopped(pids) {
  let active = [];
  for (let attempt = 0; attempt < 80; attempt++) {
    active = [];
    for (const pid of pids) {
      assert.equal(Number.isSafeInteger(pid) && pid > 1, true);
      try { process.kill(pid, 0); }
      catch (error) { if (error.code === 'ESRCH') continue; throw error; }
      // Container init may delay reaping an orphan; a zombie is no longer an
      // executing process and cannot retain bootstrap children or do work.
      if (process.platform === 'linux') {
        try { if (/^\d+ \(.+\) Z /.test(await fs.readFile(`/proc/${pid}/stat`, 'utf8'))) continue; }
        catch (error) { if (error.code === 'ENOENT') continue; throw error; }
      }
      active.push(pid);
    }
    if (!active.length) return;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  assert.deepEqual(active, [], 'Node test child or grandchild remained active');
}

test('real runner timeout stops the child and SIGTERM-resistant grandchild', { skip: process.platform === 'win32', timeout: 10_000 }, async (t) => {
  const f = await processFixture(t);
  const listeners = process.listenerCount('exit');
  const result = await runCommand(process.execPath, ['-e', PROCESS_TREE], { env: f.env, timeoutMs: 1200 });
  assert.equal(result.timedOut, true);
  assert.notEqual(result.code, 0);
  await assertProcessesStopped(JSON.parse(await fs.readFile(f.pidFile, 'utf8')));
  assert.equal(process.listenerCount('exit'), listeners);
});

test('real runner host exit kills its active child process group', { skip: process.platform === 'win32', timeout: 10_000 }, async (t) => {
  const f = await processFixture(t);
  const moduleUrl = new URL('../server/setup.js', import.meta.url).href;
  const wrapper = `
    import { runCommand } from ${JSON.stringify(moduleUrl)};
    import { existsSync } from 'node:fs';
    void runCommand(process.execPath, ['-e', ${JSON.stringify(PROCESS_TREE)}], { env: process.env, timeoutMs: 4000 });
    setInterval(() => { if (existsSync(process.env.MCP_ZAP_TEST_PID_FILE)) process.exit(0); }, 20);
  `;
  const result = await runCommand(process.execPath, ['--input-type=module', '-e', wrapper], { env: f.env, timeoutMs: 6000 });
  assert.equal(result.code, 0);
  assert.equal(result.timedOut, false);
  await assertProcessesStopped(JSON.parse(await fs.readFile(f.pidFile, 'utf8')));
});
