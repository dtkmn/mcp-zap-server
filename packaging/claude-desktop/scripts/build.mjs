import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { unpackExtension } from '@anthropic-ai/mcpb';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(root, '../..');
const output = join(repo, 'build/claude-desktop');
const staging = join(output, 'bundle');
const pkg = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
const manifest = JSON.parse(await readFile(join(root, 'manifest.json'), 'utf8'));
const lock = JSON.parse(await readFile(join(root, 'package-lock.json'), 'utf8'));
if (manifest.version !== pkg.version || lock.version !== pkg.version || lock.packages?.['']?.version !== pkg.version) {
  throw new Error('Package, manifest and lockfile versions must match before distribution.');
}
function patchedZod(version) {
  if (!/^\d+\.\d+\.\d+$/.test(version)) return false;
  const [major, minor, patch] = version.split('.').map(Number);
  return major > 4 || (major === 4 && (minor > 6 || (minor === 6 && patch >= 6)));
}
const zodPackages = Object.entries(lock.packages || {}).filter(([name]) => name === 'node_modules/zod' || name.endsWith('/node_modules/zod'));
if (!zodPackages.length || zodPackages.some(([, value]) => !patchedZod(value.version))) {
  throw new Error('Every locked Zod package must be a patched release (>=4.6.6).');
}
function assertPatchedTmp(lockfile) {
  const packages = Object.entries(lockfile.packages || {}).filter(([name]) => name === 'node_modules/tmp' || name.endsWith('/node_modules/tmp'));
  if (!packages.length || packages.some(([, value]) => {
    if (!/^\d+\.\d+\.\d+$/.test(value.version)) return true;
    const [major, minor, patch] = value.version.split('.').map(Number);
    return !(major > 0 || minor > 2 || (minor === 2 && patch >= 7));
  })) {
    throw new Error('Every locked tmp package must be a patched release (>=0.2.7).');
  }
}
assertPatchedTmp(lock);
await rm(staging, { recursive: true, force: true });
await mkdir(join(staging, 'resources/scanner/bin'), { recursive: true });
for (const name of ['server', 'manifest.json', '.mcpbignore', 'package.json', 'package-lock.json', 'README.md', 'SETUP.md']) {
  await cp(join(root, name), join(staging, name), { recursive: true });
}
await cp(join(repo, 'LICENSE'), join(staging, 'LICENSE'));
const files = ['docker-compose.yml', '.env.example', 'bin/bootstrap-local.sh', 'bin/self-serve-doctor.sh'];
const sources = {};
for (const name of files) {
  const bytes = await readFile(join(repo, name));
  await writeFile(join(staging, 'resources/scanner', name), bytes, { mode: name.endsWith('.sh') ? 0o755 : 0o644 });
  sources[name] = createHash('sha256').update(bytes).digest('hex');
}
await writeFile(join(staging, 'resources/scanner/source-sha256.json'), JSON.stringify(sources, null, 2) + '\n');
execFileSync('npm', ['ci', '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund'], { cwd: staging, stdio: 'inherit' });
const cli = join(root, 'node_modules/.bin/mcpb');
execFileSync(cli, ['validate', join(staging, 'manifest.json')], { stdio: 'inherit' });
const artifact = join(output, `mcp-zap-connect-claude-desktop-${pkg.version}.mcpb`);
execFileSync(cli, ['pack', staging, artifact], { stdio: 'inherit' });
// Verify the archive, not just staging: MCPB's default ignores include .env*.
const unpacked = join(output, 'unpacked');
await rm(unpacked, { recursive: true, force: true });
if (!await unpackExtension({ mcpbPath: artifact, outputDir: unpacked, silent: true })) throw new Error('The built archive could not be unpacked.');
assertPatchedTmp(JSON.parse(await readFile(join(unpacked, 'package-lock.json'), 'utf8')));
const archiveZod = JSON.parse(await readFile(join(unpacked, 'node_modules/zod/package.json'), 'utf8'));
if (!patchedZod(archiveZod.version) || archiveZod.version !== pkg.dependencies.zod) {
  throw new Error('The actual MCPB must contain the pinned patched Zod runtime.');
}
for (const name of files) {
  const bytes = await readFile(join(unpacked, 'resources/scanner', name));
  if (createHash('sha256').update(bytes).digest('hex') !== sources[name]) throw new Error(`The archive is missing or changed a required scanner resource: ${name}`);
}
const guide = await readFile(join(root, 'SETUP.md'));
if (!guide.equals(await readFile(join(unpacked, 'SETUP.md')))) throw new Error('The archive must contain the matching user setup guide.');
const guideArtifact = join(output, `mcp-zap-connect-claude-desktop-${pkg.version}-setup.md`);
await writeFile(guideArtifact, guide);
for (const file of [artifact, guideArtifact]) {
  const bytes = await readFile(file);
  await writeFile(file + '.sha256', `${createHash('sha256').update(bytes).digest('hex')}  ${file.split('/').at(-1)}\n`);
}
console.log(`Built ${artifact}`);
console.log(`Built ${guideArtifact}`);
