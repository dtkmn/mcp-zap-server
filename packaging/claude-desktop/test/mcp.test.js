import test from 'node:test';
import assert from 'node:assert/strict';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { ToolListChangedNotificationSchema } from '@modelcontextprotocol/sdk/types.js';
import { createDesktopServer, LOCAL_TOOLS } from '../server/mcp.js';

async function session(t, options) {
  const app = createDesktopServer(options);
  const client = new Client({ name: 'test', version: '1.0.0' });
  const [left, right] = InMemoryTransport.createLinkedPair();
  await app.server.connect(left);
  await client.connect(right);
  t.after(async () => { await client.close(); await app.close(); });
  return { app, client };
}
const text = (reply) => JSON.parse(reply.content[0].text);
const tool = (name) => ({ name, description: name, inputSchema: { type: 'object', properties: {} } });

test('setup tools remain usable with missing Docker/scanner and no key', async (t) => {
  let connects = 0;
  const { client } = await session(t, {
    controller: {
      connection: async () => { throw new Error('private-secret-must-not-leak'); },
      prerequisites: async () => ({ ok: false, code: 'DOCKER_MISSING', message: 'Install Docker Desktop.' }),
    },
    connect: async () => { connects++; },
  });
  assert.equal((await client.listTools()).tools.length, 4);
  assert.equal(connects, 0);
  const check = await client.callTool({ name: 'scanner_check_prerequisites', arguments: {} });
  assert.equal(text(check).code, 'DOCKER_MISSING');
  const status = await client.callTool({ name: 'zap_passive_scan_status', arguments: {} });
  assert.equal(status.isError, true);
  assert.doesNotMatch(JSON.stringify(status), /private-secret/);
});

test('setup rejects missing confirmation and arbitrary extra arguments without executing it', async (t) => {
  let calls = 0;
  const { client } = await session(t, { controller: { setup: async () => { calls++; } } });
  for (const args of [{}, { confirm: false }, { confirm: true, command: 'rm -rf /' }, { confirm: true, force: true }]) {
    assert.equal((await client.callTool({ name: 'scanner_setup', arguments: args })).isError, true);
  }
  assert.equal(calls, 0);
});

test('native tools are paginated, preserved and forwarded exactly once', async (t) => {
  const calls = [];
  let pages = 0;
  const expected = { content: [{ type: 'text', text: '{"recordsToScan":0}' }] };
  const remoteStatus = { ...tool('zap_passive_scan_status'), annotations: { readOnlyHint: true } };
  const { client } = await session(t, {
    controller: { connection: async () => ({ url: 'http://127.0.0.1:7456/mcp', apiKey: 'private' }) },
    connect: async () => ({
      listTools: async (args) => { pages++; return args.cursor ? { tools: [remoteStatus, tool('scanner_setup')] } : { tools: [tool('zap_crawl_start')], nextCursor: 'next' }; },
      callTool: async (args) => { calls.push(args); return expected; },
      close: async () => {},
    }),
  });
  const list = await client.listTools();
  assert.equal(pages, 2);
  assert.equal(list.tools.filter((entry) => entry.name === 'scanner_setup').length, 1);
  assert.deepEqual(list.tools.find((entry) => entry.name === 'zap_passive_scan_status'), remoteStatus);
  assert.deepEqual(await client.callTool({ name: 'zap_passive_scan_status', arguments: {} }), expected);
  assert.deepEqual(calls, [{ name: 'zap_passive_scan_status', arguments: {} }]);
});

test('failed downstream tool calls are redacted and never automatically replayed', async (t) => {
  let calls = 0;
  const { client } = await session(t, {
    controller: { connection: async () => ({ url: 'http://127.0.0.1:7456/mcp', apiKey: 'private' }) },
    connect: async () => ({
      listTools: async () => ({ tools: [tool('zap_passive_scan_status')] }),
      callTool: async () => { calls++; throw new Error('Authorization private-secret'); },
      close: async () => {},
    }),
  });
  const reply = await client.callTool({ name: 'zap_passive_scan_status', arguments: {} });
  assert.equal(reply.isError, true);
  assert.doesNotMatch(JSON.stringify(reply), /private-secret/);
  assert.equal(calls, 1);
});

test('successful setup refreshes the tool list without calling any scanner tool', async (t) => {
  let ready = false;
  let setupCalls = 0;
  let changed = 0;
  const { client } = await session(t, {
    controller: {
      connection: async () => { if (!ready) throw new Error('offline'); return { url: 'http://127.0.0.1:7456/mcp', apiKey: 'private' }; },
      setup: async (args) => { assert.deepEqual(args, { confirm: true }); ready = true; setupCalls++; return { ok: true, reused: true }; },
    },
    connect: async () => ({ listTools: async () => ({ tools: [tool('zap_passive_scan_status'), tool('zap_findings_summary')] }), close: async () => {}, callTool: async () => { assert.fail('Setup must not call native scanner tools.'); } }),
  });
  client.setNotificationHandler(ToolListChangedNotificationSchema, () => { changed++; });
  assert.equal((await client.listTools()).tools.length, LOCAL_TOOLS.length + 1);
  assert.equal(text(await client.callTool({ name: 'scanner_setup', arguments: { confirm: true } })).reused, true);
  assert.equal(setupCalls, 1);
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(changed, 1);
  assert.equal((await client.listTools()).tools.length, LOCAL_TOOLS.length + 2);
});

test('read-only local checks reject extra fields before invoking scripts', async (t) => {
  const { client } = await session(t, { controller: { prerequisites: () => assert.fail('must not run'), doctor: () => assert.fail('must not run') } });
  for (const name of ['scanner_check_prerequisites', 'scanner_check_readiness', 'zap_passive_scan_status']) {
    assert.equal((await client.callTool({ name, arguments: { command: 'scan' } })).isError, true);
  }
});

test('tool changes refresh through both notifications and a fresh host catalogue request', async (t) => {
  let current = [tool('zap_passive_scan_status')];
  let handler;
  let changed = 0;
  const { client } = await session(t, {
    controller: { connection: async () => ({ url: 'http://127.0.0.1:7456/mcp', apiKey: 'private' }) },
    connect: async () => ({
      listTools: async () => ({ tools: current }),
      setNotificationHandler: (_schema, callback) => { handler = callback; },
      close: async () => {},
    }),
  });
  client.setNotificationHandler(ToolListChangedNotificationSchema, () => { changed++; });
  await client.listTools();
  current = [tool('zap_passive_scan_status'), tool('zap_findings_summary')];
  await handler();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(changed, 1);
  assert.ok((await client.listTools()).tools.some((entry) => entry.name === 'zap_findings_summary'));
  current = [tool('zap_passive_scan_status')];
  assert.ok(!(await client.listTools()).tools.some((entry) => entry.name === 'zap_findings_summary'));
});

test('a closed connection is replaced before the next explicit call without replay', async (t) => {
  let connects = 0;
  let current;
  let calls = 0;
  let connectionChecks = 0;
  const { client } = await session(t, {
    controller: { connection: async () => { connectionChecks++; return { url: 'http://127.0.0.1:7456/mcp', apiKey: 'private' }; } },
    connect: async () => {
      connects++;
      current = {
        listTools: async () => ({ tools: [tool('zap_passive_scan_status')] }),
        callTool: async () => { calls++; return { content: [{ type: 'text', text: 'status' }] }; },
        close: async () => {},
      };
      return current;
    },
  });
  await client.listTools();
  await client.callTool({ name: 'zap_passive_scan_status', arguments: {} });
  assert.equal(connectionChecks, 1, 'An established status call does not repeat Docker prerequisite/ownership checks.');
  current.onclose();
  await client.callTool({ name: 'zap_passive_scan_status', arguments: {} });
  assert.equal(connects, 2);
  assert.equal(calls, 2);
});
