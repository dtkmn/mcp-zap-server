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
