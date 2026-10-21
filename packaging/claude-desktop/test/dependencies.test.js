import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { cp, mkdir, mkdtemp, open, readFile, realpath, rm, symlink, writeFile, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { VERSIONED_MANIFEST_SCHEMAS } from '@anthropic-ai/mcpb';

const require = createRequire(import.meta.url);
const sdkRequire = createRequire(import.meta.resolve('@modelcontextprotocol/sdk/types.js'));
const builderRequire = createRequire(import.meta.resolve('@anthropic-ai/mcpb'));
const promptsRequire = createRequire(builderRequire.resolve('@inquirer/prompts'));
const promptEditorRequire = createRequire(promptsRequire.resolve('@inquirer/editor'));
const editorRequire = createRequire(promptEditorRequire.resolve('external-editor'));
const tmp = editorRequire('tmp');

test('the builder editor resolves the pinned patched tmp package', () => {
  assert.equal(editorRequire('tmp/package.json').version, '0.2.7');
  assert.equal(require('../package.json').overrides.tmp, '0.2.7');
});

test('the builder tmp rejects traversal strings and non-string path options', async (t) => {
  const base = await mkdtemp(join(tmpdir(), 'mcp-zap-tmp-path-'));
  t.after(() => rm(base, { recursive: true, force: true }));
  for (const [field, value] of [
    ['prefix', '../escaped-'],
    ['prefix', ['../escaped-']],
    ['prefix', Buffer.from('../escaped-')],
    ['template', '../escaped-XXXXXX'],
    ['template', ['../escaped-XXXXXX']],
  ]) {
    assert.throws(() => tmp.tmpNameSync({ tmpdir: base, [field]: value }));
  }
  const normal = tmp.tmpNameSync({ tmpdir: base, prefix: 'safe-', postfix: '.txt' });
  assert.equal(dirname(normal), await realpath(base));
});

test('the builder tmp rejects a symlink directory outside its temporary root', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'mcp-zap-tmp-link-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const base = join(root, 'base');
  const outside = join(root, 'outside');
  await mkdir(base);
  await mkdir(outside);
  await symlink(outside, join(base, 'linked'), 'dir');
  assert.throws(() => tmp.tmpNameSync({ tmpdir: base, dir: 'linked' }));
});

test('the builder editor preserves temporary-file content, permissions and cleanup', async (t) => {
  const { ExternalEditor } = promptEditorRequire('external-editor');
  const editor = new ExternalEditor('initial text', { postfix: '.txt', mode: 0o600 });
  t.after(() => rm(editor.tempFile, { force: true }));
  assert.equal(await readFile(editor.tempFile, 'utf8'), 'initial text');
  const file = await open(editor.tempFile, 'r+');
  try {
    assert.equal((await file.stat()).mode & 0o777, 0o600);
    await file.truncate(0);
    await file.writeFile('updated text');
  } finally {
    await file.close();
  }
  editor.readTemporaryFile();
  assert.equal(editor.text, 'updated text');
  editor.cleanup();
  await assert.rejects(access(editor.tempFile), { code: 'ENOENT' });
});

test('runtime and build consumers resolve the same patched Zod package', () => {
  const expected = require.resolve('zod/package.json');
  const version = require('zod/package.json').version;
  assert.equal(version, '4.6.7');
  for (const consumer of [sdkRequire, builderRequire]) {
    assert.equal(consumer.resolve('zod/package.json'), expected);
  }
});

for (const [name, z] of [
  ['SDK runtime', sdkRequire('zod/v4').z],
  ['MCPB builder', builderRequire('zod').z],
]) {
  test(`${name} rejects oversized invalid arrays before collecting element errors`, () => {
    for (const schema of [z.array(z.number()).max(2), z.array(z.number()).length(2)]) {
      const reply = schema.safeParse(Array(32).fill('invalid'));
      assert.equal(reply.success, false);
      assert.deepEqual(reply.error.issues.map((issue) => issue.code), ['too_big']);
      assert.deepEqual(schema.parse([1, 2]), [1, 2]);
      const invalidElement = schema.safeParse(['invalid', 2]);
      assert.equal(invalidElement.success, false);
      assert.equal(invalidElement.error.issues[0].code, 'invalid_type');
    }
  });
}

test('MCPB manifest validation preserves record values, required flags and strict fields', async () => {
  const manifest = JSON.parse(await readFile(new URL('../manifest.json', import.meta.url), 'utf8'));
  const schema = VERSIONED_MANIFEST_SCHEMAS['0.3'];
  assert.equal(schema.safeParse(manifest).success, true);
  const invalidEnv = structuredClone(manifest);
  invalidEnv.server.mcp_config.env.MCP_ZAP_INSTALL_DIR = 123;
  const invalidRequired = structuredClone(manifest);
  invalidRequired.user_config.installation_directory.required = 'yes';
  for (const invalid of [invalidEnv, invalidRequired, { ...manifest, unexpected: true }]) {
    assert.equal(schema.safeParse(invalid).success, false);
  }
});

for (const [label, key, version, message] of [
  ['a nested vulnerable Zod', 'node_modules/consumer/node_modules/zod', '3.25.76', /Every locked Zod package must be a patched release/],
  ['a root vulnerable tmp', 'node_modules/tmp', '0.0.33', /Every locked tmp package must be a patched release/],
  ['a nested vulnerable tmp', 'node_modules/consumer/node_modules/tmp', '0.2.6', /Every locked tmp package must be a patched release/],
  ['an unverified tmp prerelease', 'node_modules/consumer/node_modules/tmp', '0.2.7-rc.1', /Every locked tmp package must be a patched release/],
]) {
  test(`the actual build rejects ${label} before staging or installing`, async (t) => {
    const temporary = await mkdtemp(join(tmpdir(), 'mcp-zap-dependency-guard-'));
    t.after(() => rm(temporary, { recursive: true, force: true }));
    const source = fileURLToPath(new URL('..', import.meta.url));
    const fixture = join(temporary, 'packaging/claude-desktop');
    await mkdir(join(fixture, 'scripts'), { recursive: true });
    for (const name of ['package.json', 'manifest.json', 'scripts/build.mjs']) {
      await cp(join(source, name), join(fixture, name));
    }
    const lock = JSON.parse(await readFile(join(source, 'package-lock.json'), 'utf8'));
    lock.packages[key] = { version };
    await writeFile(join(fixture, 'package-lock.json'), JSON.stringify(lock));
    await symlink(join(source, 'node_modules'), join(fixture, 'node_modules'), 'dir');
    const reply = spawnSync(process.execPath, [join(fixture, 'scripts/build.mjs')], {
      cwd: fixture, encoding: 'utf8', timeout: 10000,
    });
    assert.equal(reply.status, 1, reply.stderr);
    assert.match(reply.stderr, message);
    await assert.rejects(access(join(temporary, 'build')), { code: 'ENOENT' });
  });
}
