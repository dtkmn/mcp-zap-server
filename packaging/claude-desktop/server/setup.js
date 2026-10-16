import { spawn } from 'node:child_process';
import { promises as fs } from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';

const URL = 'http://127.0.0.1:7456/mcp';
const PORTS = [7456, 8090, 3001, 3002];
const FILES = ['docker-compose.yml', '.env.example', 'bin/bootstrap-local.sh', 'bin/self-serve-doctor.sh'];
const DOCKER_INSTALL = 'https://docs.docker.com/get-docker/';
const INSPECT_FORMAT = '{"Id":{{json .Id}},"Config":{"Image":{{json .Config.Image}},"Labels":{{json .Config.Labels}},"Env":{{json .Config.Env}}},"State":{{json .State}},"NetworkSettings":{"Ports":{{json .NetworkSettings.Ports}}},"HostConfig":{"PortBindings":{{json .HostConfig.PortBindings}}}}';
const OVERRIDES = ['ZAP_API_KEY', 'MCP_SECURITY_MODE', 'MCP_SECURITY_ENABLED', 'JWT_ENABLED', 'MCP_ZAP_BIND_ADDRESS', 'IMAGE_TAG', 'ZAP_IMAGE', 'LOCAL_ZAP_WORKPLACE_FOLDER', 'COMPOSE_PROJECT_NAME'];

class SetupError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}
const fail = (code, message) => { throw new SetupError(code, message); };
const publicError = (error) => ({
  ok: false,
  code: error instanceof SetupError ? error.code : 'CHECK_FAILED',
  message: error instanceof SetupError ? error.message : 'The local check failed. Review the installation privately and retry.',
  ...(error?.code === 'DOCKER_MISSING' ? { helpUrl: DOCKER_INSTALL } : {}),
});

// No shell, bounded output, and a separate process group so a timeout also stops
// bootstrap's Docker/curl children. Captured output never enters public results.
export async function runCommand(command, args, { cwd, env, timeoutMs = 15_000 } = {}) {
  return new Promise((resolve) => {
    let stdout = '', stderr = '', timedOut = false, outputLimit = false, done = false;
    let child;
    const stop = (signal) => {
      if (!child?.pid) return;
      try { process.kill(process.platform === 'win32' ? child.pid : -child.pid, signal); } catch {}
    };
    let killTimer;
    const terminate = () => { stop('SIGTERM'); killTimer = setTimeout(() => stop('SIGKILL'), 500); killTimer.unref(); };
    const finish = (code) => {
      if (done) return;
      done = true; clearTimeout(timer);
      if (timedOut || outputLimit) stop('SIGKILL');
      clearTimeout(killTimer);
      process.off('exit', onExit);
      resolve({ code, stdout, stderr, timedOut, outputLimit });
    };
    try {
      child = spawn(command, args, { cwd, env, shell: false, detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
    } catch { resolve({ code: 127, stdout: '', stderr: '' }); return; }
    const onExit = () => stop('SIGKILL');
    process.once('exit', onExit);
    const timer = setTimeout(() => { timedOut = true; terminate(); }, timeoutMs);
    const collect = (field, data) => {
      if (outputLimit) return;
      if (field === 'stdout') stdout += data.toString(); else stderr += data.toString();
      if (Buffer.byteLength(stdout) + Buffer.byteLength(stderr) > 2 * 1024 * 1024) { outputLimit = true; terminate(); }
    };
    child.stdout.on('data', (data) => collect('stdout', data));
    child.stderr.on('data', (data) => collect('stderr', data));
    child.once('error', () => finish(127));
    child.once('close', (code) => finish(code ?? 1));
  });
}

async function portInUse(port) {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host: '127.0.0.1', port });
    let done = false;
    const finish = (occupied) => { if (!done) { done = true; socket.destroy(); resolve(occupied); } };
    socket.once('connect', () => finish(true));
    socket.once('error', (error) => finish(error.code !== 'ECONNREFUSED'));
    socket.setTimeout(750, () => finish(true));
  });
}

function parseEnv(text) {
  const values = {};
  for (const line of text.split(/\r?\n/)) {
    const match = line.match(/^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
    if (!match) continue;
    let value = match[2].trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    else value = value.replace(/\s+#.*$/, '').trim();
    values[match[1]] = value;
  }
  return values;
}

export function createSetupController({ env = process.env, platform = process.platform, home = os.homedir(), bundleRoot, runner = runCommand, probePort = portInUse } = {}) {
  let busy = false;
  const uid = process.getuid?.();
  const runtimeEnv = { ...env };
  // These variables make even a fixed Bash script execute unrelated startup code
  // or trace secrets. They are not scanner configuration or Docker context.
  for (const key of ['BASH_ENV', 'ENV', 'SHELLOPTS', 'BASHOPTS', 'CDPATH', 'IFS']) delete runtimeEnv[key];
  const run = (command, args, options = {}) => runner(command, args, { env: runtimeEnv, timeoutMs: 15_000, ...options });
  const success = (code, message, extra = {}) => ({ ok: true, code, message, ...extra });

  function checkPlatform() {
    if (!['darwin', 'linux'].includes(platform)) fail('PLATFORM_UNSUPPORTED', 'Automatic local setup currently supports macOS and Linux with Bash. Use the documented manual setup on this platform.');
    if (env.DOCKER_HOST && !env.DOCKER_HOST.startsWith('unix://')) fail('REMOTE_DOCKER_UNSUPPORTED', 'Select a local Docker engine before using this extension. Remote Docker hosts cannot establish this loopback connection.');
    if (env.COMPOSE_FILE) fail('ENVIRONMENT_CONFLICT', 'An inherited COMPOSE_FILE is set. Resolve that override before local setup; no settings were changed.');
  }

  async function safeDirectory(directory, allowMissing = false) {
    if (typeof directory !== 'string' || !path.isAbsolute(directory) || path.resolve(directory) !== directory || /[\0\r\n]/.test(directory) || directory === path.parse(directory).root) fail('UNSAFE_INSTALL_DIRECTORY', 'Choose an absolute installation directory without linked path components.');
    const parts = directory.slice(path.parse(directory).root.length).split(path.sep);
    let current = path.parse(directory).root;
    for (let index = 0; index < parts.length; index++) {
      current = path.join(current, parts[index]);
      let stat;
      try { stat = await fs.lstat(current); }
      catch (error) {
        if (error.code === 'ENOENT' && allowMissing && index === parts.length - 1) {
          const parent = await fs.stat(path.dirname(current));
          if (uid !== undefined && parent.uid !== uid) fail('UNSAFE_INSTALL_DIRECTORY', 'The installation parent must be owned by your user.');
          return false;
        }
        fail('UNSAFE_INSTALL_DIRECTORY', 'The installation directory or its parent is unavailable. Choose an existing user-owned parent.');
      }
      if (stat.isSymbolicLink() || !stat.isDirectory()) fail('UNSAFE_INSTALL_DIRECTORY', 'Linked installation directories or parents are not supported. Choose a real user-owned directory.');
      if (index === parts.length - 1 && uid !== undefined && stat.uid !== uid) fail('UNSAFE_INSTALL_DIRECTORY', 'The installation directory must be owned by your user.');
    }
    return true;
  }

  async function readFile(file, { optional = false, secret = false } = {}) {
    let stat;
    try { stat = await fs.lstat(file); }
    catch (error) { if (optional && error.code === 'ENOENT') return null; fail('INSTALLATION_INCOMPLETE', 'A required local installation file is missing or unreadable.'); }
    if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1 || (uid !== undefined && stat.uid !== uid)) fail('UNSAFE_INSTALL_FILE', 'Installation files must be regular, unlinked files owned by your user.');
    if (secret && (stat.mode & 0o077)) fail('INSECURE_ENV_FILE', 'Restrict the private .env file to your user before continuing.');
    return fs.readFile(file);
  }

  async function inventory() {
    const listing = await run('docker', ['ps', '-a', '--no-trunc', '--format', '{{.ID}}']);
    if (listing.code !== 0) fail('DOCKER_UNAVAILABLE', 'Docker could not list containers. Start your existing local Docker engine and retry.');
    const ids = listing.stdout.trim().split(/\s+/).filter(Boolean);
    if (ids.some((id) => !/^[a-f0-9]{12,64}$/.test(id)) || ids.length > 256) fail('DOCKER_INVENTORY_UNAVAILABLE', 'Docker ownership could not be established safely. Review the local container inventory.');
    if (!ids.length) return [];
    const inspected = await run('docker', ['inspect', '--format', INSPECT_FORMAT, ...ids]);
    if (inspected.code !== 0 || inspected.outputLimit || inspected.timedOut) fail('DOCKER_INVENTORY_UNAVAILABLE', 'Docker ownership could not be established safely. Retry after Docker is ready.');
    try {
      const containers = inspected.stdout.trim().startsWith('[') ? JSON.parse(inspected.stdout) : inspected.stdout.trim().split('\n').filter(Boolean).map((line) => JSON.parse(line));
      if (!Array.isArray(containers) || containers.length !== ids.length || containers.some((container) => !container?.Config || !container?.State)) throw new Error();
      return containers;
    } catch { fail('DOCKER_INVENTORY_UNAVAILABLE', 'Docker returned an unreadable container inventory. No local settings were changed.'); }
  }

  const labels = (container) => container.Config.Labels ?? {};
  const bindings = (container) => Object.entries(container.HostConfig?.PortBindings ?? container.NetworkSettings?.Ports ?? {}).flatMap(([containerPort, entries]) => (entries ?? []).map((entry) => ({ containerPort, port: Number(entry.HostPort), host: entry.HostIp })));
  const containerEnv = (container) => Object.fromEntries((container.Config.Env ?? []).map((value) => { const split = value.indexOf('='); return [value.slice(0, split), value.slice(split + 1)]; }));

  async function context() {
    checkPlatform();
    const dockerContext = await run('docker', ['context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}']);
    let endpoint;
    try { endpoint = JSON.parse(dockerContext.stdout); } catch {}
    if (dockerContext.code !== 0 || typeof endpoint !== 'string' || !endpoint.startsWith('unix://')) fail('REMOTE_DOCKER_UNSUPPORTED', 'This extension requires a local Docker engine using a Unix socket. Keep remote Docker deployments on their existing setup path.');
    const containers = await inventory();
    // A generic Compose service name is not product identity: unrelated apps can
    // also name a service mcp-server. Their published ports remain conflicts.
    const scanners = containers.filter((container) => /^dtkmn\/mcp-zap-server(?::|@|$)/.test(container.Config.Image ?? ''));
    if (scanners.length > 1) fail('DUPLICATE_SCANNERS', 'More than one scanner installation was found. Select and resolve the duplicate installations before continuing.');
    const scanner = scanners[0];
    const healthy = Boolean(scanner?.State.Running && scanner.State.Health?.Status === 'healthy');
    const owner = scanner && labels(scanner)['com.docker.compose.project.working_dir'];
    const explicit = env.MCP_ZAP_INSTALL_DIR?.trim();
    if (scanner && (!owner || !labels(scanner)['com.docker.compose.project'] || labels(scanner)['com.docker.compose.service'] !== 'mcp-server')) fail('OWNERSHIP_UNKNOWN', 'The existing scanner has no supported Compose ownership metadata. Keep it unchanged and use manual setup.');
    if (explicit && owner && explicit !== owner) fail('OWNERSHIP_CONFLICT', 'The selected installation directory differs from the existing scanner owner. Resolve that choice before continuing.');
    const directory = explicit || owner || path.join(home, '.mcp-zap-server');
    const exists = await safeDirectory(directory, !scanner);
    const envBytes = exists ? await readFile(path.join(directory, '.env'), { optional: true, secret: true }) : null;
    const settings = envBytes ? parseEnv(envBytes.toString()) : {};
    const project = scanner ? labels(scanner)['com.docker.compose.project'] : (settings.COMPOSE_PROJECT_NAME || path.basename(directory).toLowerCase().replace(/[^a-z0-9_-]/g, '').replace(/^[^a-z0-9]+/, ''));
    if (!/^[a-z0-9][a-z0-9_-]*$/.test(project)) fail('COMPOSE_PROJECT_INVALID', 'The installation directory does not produce a valid Compose project name. Choose a simple directory name.');
    for (const container of containers) {
      const own = labels(container)['com.docker.compose.project'] === project;
      if (own && labels(container)['com.docker.compose.project.working_dir'] !== directory) fail('OWNERSHIP_CONFLICT', 'Another checkout owns containers with this Compose project name. No containers were changed.');
      const requiredPorts = healthy ? [7456] : PORTS;
      if (container.State.Running && bindings(container).some((binding) => requiredPorts.includes(binding.port)) && !own) fail('PORT_CONFLICT', 'Another Docker installation occupies a required scanner port. Preserve it and resolve the conflict before setup.');
    }
    if (scanner) {
      const configFiles = labels(scanner)['com.docker.compose.project.config_files'];
      if (configFiles && configFiles !== path.join(directory, 'docker-compose.yml')) fail('COMPOSE_LAYOUT_UNSUPPORTED', 'The existing scanner uses a different Compose file layout. Use its existing manual setup instructions.');
      const published = bindings(scanner).filter((binding) => binding.port === 7456 && binding.containerPort === '7456/tcp');
      if (published.length !== 1 || published[0].host !== '127.0.0.1' || bindings(scanner).some((binding) => binding.containerPort === '7456/tcp' && binding.host !== '127.0.0.1')) fail('UNSAFE_BINDING', 'The scanner must publish port 7456 only on 127.0.0.1 for this local extension. Keep the existing deployment unchanged and review its binding.');
    }
    const effective = scanner ? containerEnv(scanner) : null;
    // JWT services may be enabled independently; SecurityConfig selects the
    // actual request authentication using MCP_SECURITY_MODE.
    if (effective && (effective.MCP_SECURITY_MODE !== 'api-key' || effective.MCP_SECURITY_ENABLED !== 'true' || !effective.MCP_API_KEY || /[\0\r\n]/.test(effective.MCP_API_KEY))) fail('AUTH_UNSUPPORTED', 'The existing scanner does not use the supported authenticated API-key configuration. Use that deployment’s existing authentication instructions.');
    const apiKey = effective?.MCP_API_KEY;
    const configuredKey = apiKey || settings.MCP_API_KEY;
    for (const key of ['MCP_API_KEY', 'MCP_ZAP_API_KEY']) {
      if (env[key] && (!configuredKey || env[key] !== configuredKey)) fail('CREDENTIAL_CONFLICT', 'A supplied or inherited API key differs from the scanner’s effective credential. Resolve the credential choice privately; no key was replaced.');
    }
    if (apiKey && settings.MCP_API_KEY && settings.MCP_API_KEY !== apiKey) fail('CREDENTIAL_CONFLICT', 'The scanner credential differs from its private .env file. Resolve the effective configuration privately before continuing.');
    const effectiveSettings = effective ? { ...settings, ZAP_API_KEY: effective.ZAP_API_KEY, MCP_SECURITY_MODE: effective.MCP_SECURITY_MODE, MCP_SECURITY_ENABLED: effective.MCP_SECURITY_ENABLED, JWT_ENABLED: effective.JWT_ENABLED } : settings;
    for (const key of OVERRIDES) {
      if (env[key] && env[key] !== effectiveSettings[key] && !(key === 'COMPOSE_PROJECT_NAME' && env[key] === project)) fail('ENVIRONMENT_CONFLICT', 'An inherited scanner or Compose setting differs from the selected installation. Resolve the override before continuing; nothing was changed.');
    }
    for (const key of ['MCP_SECURITY_MODE', 'MCP_SECURITY_ENABLED', 'JWT_ENABLED', 'ZAP_API_KEY']) {
      if (effective && settings[key] && settings[key] !== effective[key]) fail('ENVIRONMENT_CONFLICT', 'The running scanner and .env authentication settings differ. Resolve the effective configuration privately before continuing.');
    }
    if (settings.MCP_ZAP_BIND_ADDRESS && settings.MCP_ZAP_BIND_ADDRESS !== '127.0.0.1') fail('UNSAFE_BINDING', 'The private .env selects a non-loopback binding. Review that deployment manually before using this extension.');
    if (!effective && ((settings.MCP_SECURITY_MODE && settings.MCP_SECURITY_MODE !== 'api-key') || settings.MCP_SECURITY_ENABLED === 'false' || settings.JWT_ENABLED === 'true')) fail('AUTH_UNSUPPORTED', 'The selected installation does not use the supported authenticated API-key configuration. Use its existing authentication instructions.');
    return { directory, exists, project, scanner, containers, settings, envBytes, apiKey, healthy };
  }

  async function prerequisitesInternal() {
    checkPlatform();
    const commands = [['/bin/bash', ['--version'], 'BASH_MISSING', 'Bash is required for the existing scanner setup scripts.'], ['docker', ['--version'], 'DOCKER_MISSING', 'Install Docker using its official instructions, then start the engine and retry.'], ['docker', ['info'], 'DOCKER_UNAVAILABLE', 'Start the existing local Docker engine and retry.'], ['docker', ['compose', 'version'], 'COMPOSE_MISSING', 'Docker Compose is required. Update your existing Docker installation using its official instructions.'], ['curl', ['--version'], 'CURL_MISSING', 'curl is required by the existing readiness script.'], ['openssl', ['version'], 'OPENSSL_MISSING', 'OpenSSL is required for securely generating new scanner keys.']];
    for (const [command, args, code, message] of commands) {
      const result = await run(command, args);
      if (result.code !== 0 || result.timedOut || result.outputLimit) fail(code, message);
    }
    const help = await run('docker', ['compose', 'up', '--help']);
    if (help.code !== 0 || !/(^|\s)--wait(?:\s|,|$)/m.test(help.stdout) || !help.stdout.includes('--wait-timeout')) fail('COMPOSE_TOO_OLD', 'Update Docker Compose to a version supporting --wait and --wait-timeout.');
    const ctx = await context();
    // Healthy reuse does not start demos or ZAP, so those ports need not be
    // available. Keep the MCP endpoint ownership check for every path.
    for (const port of ctx.healthy ? [7456] : PORTS) {
      const ownedListener = ctx.containers.some((container) => container.State.Running && labels(container)['com.docker.compose.project'] === ctx.project && bindings(container).some((binding) => binding.port === port));
      if (!ownedListener && await probePort(port)) fail('PORT_CONFLICT', 'A required scanner port is occupied by another application. Preserve that application and resolve the conflict before setup.');
    }
    return ctx;
  }

  async function trustedFile(relative) {
    if (!bundleRoot || !path.isAbsolute(bundleRoot)) fail('BUNDLE_INCOMPLETE', 'The extension is missing its packaged scanner resources. Reinstall the extension.');
    const file = path.join(bundleRoot, 'resources', 'scanner', relative);
    const bytes = await readFile(file);
    return bytes;
  }

  async function verifyScripts(directory) {
    await safeDirectory(path.join(directory, 'bin'));
    for (const relative of FILES.filter((file) => file.startsWith('bin/'))) {
      if (!(await readFile(path.join(directory, relative))).equals(await trustedFile(relative))) fail('SCRIPT_MISMATCH', 'The installation scripts differ from this extension’s packaged scripts. Do not execute them through this extension; review or update the installation manually.');
    }
  }

  async function stage(directory) {
    const contents = await Promise.all(FILES.map(async (relative) => [relative, await trustedFile(relative)]));
    await safeDirectory(directory, true);
    await fs.mkdir(directory, { mode: 0o700 });
    await fs.mkdir(path.join(directory, 'bin'), { mode: 0o700 });
    for (const [relative, bytes] of contents) await fs.writeFile(path.join(directory, relative), bytes, { flag: 'wx', mode: relative.startsWith('bin/') ? 0o700 : 0o600 });
  }

  async function doctorInternal(ctx) {
    if (!ctx.healthy || !ctx.apiKey) fail('SCANNER_NOT_READY', 'The selected scanner is not healthy and authenticated. Start or diagnose it through its existing setup before checking readiness.');
    await verifyScripts(ctx.directory);
    const result = await run('/bin/bash', [path.join(ctx.directory, 'bin/self-serve-doctor.sh'), '--skip-tool-call'], { cwd: ctx.directory, env: { ...runtimeEnv, MCP_API_KEY: ctx.apiKey }, timeoutMs: 150_000 });
    if (result.code !== 0 || result.timedOut || result.outputLimit) fail('DOCTOR_FAILED', 'Authenticated readiness did not pass. Existing settings and containers were preserved. Review private local diagnostics before retrying.');
    return success('READY', 'Authenticated readiness passed. No scanner tool was called.', { readinessVerified: true });
  }

  async function guarded(operation) {
    try { return await operation(); } catch (error) { return publicError(error); }
  }

  return {
    prerequisites: () => guarded(async () => {
      const ctx = await prerequisitesInternal();
      return success('PREREQUISITES_READY', ctx.healthy ? 'Prerequisites passed and an existing healthy scanner can be reused.' : 'Prerequisites passed. Starting a scanner still requires your explicit setup confirmation.', { existingInstallation: ctx.exists, existingScanner: Boolean(ctx.scanner), healthy: ctx.healthy, setupRequired: !ctx.healthy });
    }),
    setup: ({ confirm = false } = {}) => guarded(async () => {
      if (busy) fail('SETUP_BUSY', 'Another setup or readiness check is already running. Wait for it to finish.');
      if (confirm !== true) return { ok: false, code: 'CONFIRMATION_REQUIRED', message: 'Confirm local scanner setup before files or containers are changed. Existing installations will be reused when healthy.', needsConfirmation: true };
      busy = true;
      try {
        let ctx = await prerequisitesInternal();
        if (ctx.healthy) {
          await doctorInternal(ctx);
          return success('SCANNER_REUSED', 'The existing scanner was reused and authenticated readiness passed. No lifecycle changes or scan were performed.', { changed: false, readinessVerified: true });
        }
        if (ctx.scanner?.State.Running) fail('SCANNER_UNHEALTHY', 'The existing scanner is running but unhealthy. Preserve it and diagnose it manually; this extension will not restart it.');
        if (ctx.scanner && !ctx.envBytes) fail('START_MANUALLY', 'The stopped installation has no private .env file. Restore its settings and start it manually to avoid rotating existing keys.');
        if (!ctx.exists) { await stage(ctx.directory); ctx = await prerequisitesInternal(); }
        await verifyScripts(ctx.directory);
        if (!(await readFile(path.join(ctx.directory, 'docker-compose.yml'))).equals(await trustedFile('docker-compose.yml'))) fail('START_MANUALLY', 'The existing Compose file differs from this extension’s template. Start that customized deployment manually; a healthy owned scanner can then be reused without changing its Compose settings.');
        const result = await run('/bin/bash', [path.join(ctx.directory, 'bin/bootstrap-local.sh'), '--start'], { cwd: ctx.directory, timeoutMs: 420_000 });
        if (result.code !== 0 || result.timedOut || result.outputLimit) fail('SETUP_FAILED', 'Local scanner setup did not complete. Files and containers were retained for private diagnosis. Do not repeat setup until the reported local problem is resolved.');
        const started = await context();
        if (!started.healthy || !started.apiKey) fail('SCANNER_NOT_READY', 'Setup returned but a healthy authenticated scanner could not be established. Review private local diagnostics.');
        return success('SCANNER_STARTED', 'The release scanner started and its bootstrap readiness check passed. No scan was started.', { changed: true, readinessVerified: true });
      } finally { busy = false; }
    }),
    doctor: () => guarded(async () => {
      if (busy) fail('SETUP_BUSY', 'Another setup or readiness check is already running. Wait for it to finish.');
      busy = true;
      try { return await doctorInternal(await prerequisitesInternal()); } finally { busy = false; }
    }),
    connection: async () => {
      try {
        if (busy) fail('SETUP_BUSY', 'Wait for local setup to finish before connecting.');
        const ctx = await context();
        if (!ctx.healthy || !ctx.apiKey) fail('SCANNER_NOT_READY', 'A healthy authenticated local scanner is required before connecting.');
        return { url: URL, apiKey: ctx.apiKey };
      } catch (error) { const safe = publicError(error); throw new SetupError(safe.code, safe.message); }
    },
  };
}
