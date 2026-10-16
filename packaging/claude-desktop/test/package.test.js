import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { packExtension, unpackExtension } from '@anthropic-ai/mcpb';

test('actual MCPB packing keeps the public template and excludes private credentials', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'mcp-zap-package-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const stage = join(root, 'stage');
  const unpacked = join(root, 'unpacked');
  await mkdir(join(stage, 'resources/scanner'), { recursive: true });
  await mkdir(join(stage, 'server'));
  await writeFile(join(stage, 'manifest.json'), await readFile(new URL('../manifest.json', import.meta.url)));
  await writeFile(join(stage, '.mcpbignore'), await readFile(new URL('../.mcpbignore', import.meta.url)));
  await writeFile(join(stage, 'server/index.js'), 'export {};\n');
  await writeFile(join(stage, 'SETUP.md'), '# Matching setup guide\n');
  await writeFile(join(stage, 'resources/scanner/.env.example'), 'MCP_API_KEY=placeholder\n');
  await writeFile(join(stage, 'resources/scanner/.env'), 'MCP_API_KEY=private-must-not-ship\n');
  await writeFile(join(stage, '.env.local'), 'PRIVATE=private-must-not-ship\n');
  const artifact = join(root, 'fixture.mcpb');
  assert.equal(await packExtension({ extensionPath: stage, outputPath: artifact, silent: true }), true);
  assert.equal(await unpackExtension({ mcpbPath: artifact, outputDir: unpacked, silent: true }), true);
  assert.equal(await readFile(join(unpacked, 'resources/scanner/.env.example'), 'utf8'), 'MCP_API_KEY=placeholder\n');
  assert.equal(await readFile(join(unpacked, 'SETUP.md'), 'utf8'), '# Matching setup guide\n');
  await assert.rejects(readFile(join(unpacked, 'resources/scanner/.env')), { code: 'ENOENT' });
  await assert.rejects(readFile(join(unpacked, '.env.local')), { code: 'ENOENT' });
});
