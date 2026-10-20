import test from 'node:test';
import assert from 'node:assert/strict';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { ToolListChangedNotificationSchema } from '@modelcontextprotocol/sdk/types.js';
import { createDesktopServer, LOCAL_TOOLS, PROTOCOL_INSTRUCTIONS } from '../server/mcp.js';

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
const nativeStatus = () => ({ content: [{ type: 'text', text: 'Passive scan status:\nCompleted: yes\nRecords remaining: 0\nActive tasks: 0\nScan only in scope: false\nPassive analysis backlog is drained.' }] });
const ready = async () => ({ ok: true, code: 'READY', readinessVerified: true });

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
  assert.equal(connectionChecks, 2, 'An established status call rechecks current Docker ownership and credentials.');
  current.onclose();
  await client.callTool({ name: 'zap_passive_scan_status', arguments: {} });
  assert.equal(connects, 2);
  assert.equal(calls, 2);
});

test('workflow preflight authenticates, verifies native status, and forwards original target and scope once', async (t) => {
  const events = [];
  let connects = 0;
  const args = { url: 'http://fixture.invalid:3000', strategy: 'http', scope: 'original-scope' };
  const response = { content: [{ type: 'text', text: 'fixture workflow accepted' }] };
  const { client } = await session(t, {
    controller: {
      connection: async () => { events.push('connection'); return { url: 'http://127.0.0.1:7456/mcp', apiKey: 'private', identity: 'first' }; },
      doctor: async () => { events.push('doctor'); return ready(); },
    },
    connect: async () => {
      connects++;
      return {
        listTools: async () => { events.push('tools'); return { tools: [tool('zap_passive_scan_status'), tool('zap_crawl_start')] }; },
        callTool: async (request) => { events.push(request); return request.name === 'zap_passive_scan_status' ? nativeStatus() : response; },
        close: async () => {},
      };
    },
  });
  assert.deepEqual(await client.callTool({ name: 'zap_crawl_start', arguments: args }), response);
  assert.equal(connects, 1);
  assert.deepEqual(events.filter((event) => typeof event === 'object'), [
    { name: 'zap_passive_scan_status', arguments: {} },
    { name: 'zap_crawl_start', arguments: args },
  ]);
  assert.ok(events.indexOf('doctor') < events.findIndex((event) => event?.name === 'zap_passive_scan_status'));
});

test('workflow preflight accepts the scanner JSON-encoded native status string', async (t) => {
  const calls = [];
  const args = { url: 'http://fixture.invalid:3000', strategy: 'http', scope: 'original-scope' };
  const status = { content: [{ type: 'text', text: JSON.stringify('Passive scan status:\nCompleted: yes\nRecords remaining: 0\nActive tasks: 0\nScan only in scope: false\nPassive analysis backlog is drained. It is safe to read findings or generate a report.') }], isError: false };
  const response = { content: [{ type: 'text', text: 'fixture workflow accepted' }] };
  const { client } = await session(t, {
    controller: { connection: async () => ({ url: 'http://127.0.0.1:7456/mcp', apiKey: 'private' }), doctor: ready },
    connect: async () => ({
      listTools: async () => ({ tools: [tool('zap_passive_scan_status'), tool('zap_crawl_start')] }),
      callTool: async (request) => { calls.push(request); return request.name === 'zap_passive_scan_status' ? status : response; },
      close: async () => {},
    }),
  });
  assert.deepEqual(await client.callTool({ name: 'zap_crawl_start', arguments: args }), response);
  assert.deepEqual(calls, [
    { name: 'zap_passive_scan_status', arguments: {} },
    { name: 'zap_crawl_start', arguments: args },
  ]);
});

test('native status normalization rejects errors, JSON objects and malformed status without workflow dispatch', async (t) => {
  const validText = nativeStatus().content[0].text;
  const encoded = { content: [{ type: 'text', text: JSON.stringify(validText) }] };
  const cases = [
    { ...encoded, isError: true },
    { ...encoded, structuredContent: { ok: false } },
    { ...nativeStatus(), structuredContent: { ok: false } },
    { content: [{ type: 'text', text: JSON.stringify({ status: validText }) }] },
    { content: [{ type: 'text', text: JSON.stringify([validText]) }] },
    { content: [{ type: 'text', text: JSON.stringify(JSON.stringify(validText)) }] },
    { content: [{ type: 'text', text: JSON.stringify(validText).slice(0, -1) }] },
    { content: [{ type: 'text', text: JSON.stringify(validText.replace('Records remaining: 0', 'Records remaining: -1')) }] },
    { content: [{ type: 'text', text: JSON.stringify(validText.replace('Active tasks: 0\n', '')) }] },
    { content: [{ type: 'text', text: JSON.stringify('unrelated output\n' + validText) }] },
  ];
  for (const status of cases) {
    const calls = [];
    const { client } = await session(t, {
      controller: { connection: async () => ({ url: 'http://127.0.0.1:7456/mcp', apiKey: 'private' }), doctor: ready },
      connect: async () => ({
        listTools: async () => ({ tools: [tool('zap_passive_scan_status'), tool('zap_crawl_start')] }),
        callTool: async (request) => { calls.push(request.name); return status; }, close: async () => {},
      }),
    });
    const reply = await client.callTool({ name: 'zap_crawl_start', arguments: { url: 'http://fixture.invalid' } });
    assert.equal(text(reply).code, 'NATIVE_STATUS_FAILED');
    assert.deepEqual(calls, ['zap_passive_scan_status']);
  }
});

test('offline-to-ready recovery requires explicit setup, fresh discovery and harmless proof before the workflow', async (t) => {
  let available = false;
  let setupCalls = 0;
  let pages = 0;
  const calls = [];
  const { client } = await session(t, {
    controller: {
      connection: async () => {
        if (!available) { const error = new Error('private stderr and key'); error.code = 'SCANNER_STOPPED'; throw error; }
        return { url: 'http://127.0.0.1:7456/mcp', apiKey: 'unchanged-private-key' };
      },
      doctor: ready,
      setup: async () => { setupCalls++; available = true; return { ok: true, code: 'SCANNER_STARTED', readinessVerified: true }; },
    },
    connect: async () => ({
      listTools: async () => { pages++; return { tools: [tool('zap_passive_scan_status'), tool('zap_crawl_start')] }; },
      callTool: async (request) => { calls.push(request); return request.name === 'zap_passive_scan_status' ? nativeStatus() : { content: [{ type: 'text', text: 'started fixture' }] }; },
      close: async () => {},
    }),
  });
  assert.equal((await client.listTools()).tools.length, 4);
  const stopped = text(await client.callTool({ name: 'zap_crawl_start', arguments: { url: 'http://fixture.invalid' } }));
  assert.equal(stopped.code, 'SCANNER_STOPPED');
  assert.equal(stopped.recovery.needsConfirmation, true);
  assert.equal(setupCalls, 0);
  assert.deepEqual(calls, []);
  await client.callTool({ name: 'scanner_setup', arguments: { confirm: false } });
  assert.equal(setupCalls, 0);
  await client.callTool({ name: 'scanner_setup', arguments: { confirm: true } });
  assert.equal(setupCalls, 1);
  assert.deepEqual(calls, [], 'Approved setup discovers tools but does not start the requested workflow.');
  const arguments_ = { url: 'http://fixture.invalid', strategy: 'http' };
  await client.callTool({ name: 'zap_crawl_start', arguments: arguments_ });
  assert.ok(pages >= 2);
  assert.deepEqual(calls, [{ name: 'zap_passive_scan_status', arguments: {} }, { name: 'zap_crawl_start', arguments: arguments_ }]);
});

test('a cached connection cannot bypass stopped Docker, changed context or mismatched credentials', async (t) => {
  let failureCode;
  let doctorCalls = 0;
  let nativeCalls = 0;
  const { client } = await session(t, {
    controller: {
      connection: async () => {
        if (failureCode) { const error = new Error('private stderr credential'); error.code = failureCode; throw error; }
        return { url: 'http://127.0.0.1:7456/mcp', apiKey: 'private' };
      },
      doctor: async () => { doctorCalls++; return ready(); },
    },
    connect: async () => ({ listTools: async () => ({ tools: [tool('zap_passive_scan_status'), tool('zap_crawl_start')] }), callTool: async () => { nativeCalls++; return nativeStatus(); }, close: async () => {} }),
  });
  await client.listTools();
  for (const code of ['DOCKER_UNAVAILABLE', 'REMOTE_DOCKER_UNSUPPORTED', 'CREDENTIAL_CONFLICT', 'SCANNER_REMOVED', 'SCANNER_UNHEALTHY', 'PORT_CONFLICT', 'OWNERSHIP_CONFLICT']) {
    failureCode = code;
    const reply = await client.callTool({ name: 'zap_crawl_start', arguments: { url: 'http://fixture.invalid' } });
    assert.equal(text(reply).code, code);
    assert.doesNotMatch(JSON.stringify(reply), /private stderr credential/);
    assert.equal(text(reply).recovery.automaticRetry, false);
  }
  assert.equal(doctorCalls, 0);
  assert.equal(nativeCalls, 0);
});

test('new container or credential identity replaces a cached transport without modifying settings', async (t) => {
  let identity = 'container-one';
  let key = 'private-one';
  const connections = [];
  const calls = [];
  const { client } = await session(t, {
    controller: { connection: async () => ({ url: 'http://127.0.0.1:7456/mcp', apiKey: key, identity }) },
    connect: async (connection) => {
      connections.push(connection);
      const current = identity;
      return { listTools: async () => ({ tools: [tool('zap_passive_scan_status')] }), callTool: async () => { calls.push(current); return nativeStatus(); }, close: async () => {} };
    },
  });
  await client.listTools();
  identity = 'container-two';
  key = 'private-two';
  await client.callTool({ name: 'zap_passive_scan_status', arguments: {} });
  assert.equal(connections.length, 2);
  assert.equal(connections[1].apiKey, 'private-two');
  assert.deepEqual(calls, ['container-two']);
});

test('doctor and native error results block workflow dispatch, including subsequent attempts', async (t) => {
  let doctorResult = { ok: false, code: 'DOCTOR_FAILED', message: 'private doctor key' };
  let statusResult = { isError: true, content: [{ type: 'text', text: 'private status key' }] };
  const calls = [];
  const { client } = await session(t, {
    controller: { connection: async () => ({ url: 'http://127.0.0.1:7456/mcp', apiKey: 'private' }), doctor: async () => doctorResult },
    connect: async () => ({
      listTools: async () => ({ tools: [tool('zap_passive_scan_status'), tool('zap_attack_start')] }),
      callTool: async (request) => { calls.push(request.name); return statusResult; }, close: async () => {},
    }),
  });
  const request = { name: 'zap_attack_start', arguments: { url: 'http://fixture.invalid' } };
  assert.equal(text(await client.callTool(request)).code, 'DOCTOR_FAILED');
  assert.deepEqual(calls, []);
  doctorResult = await ready();
  for (let attempt = 0; attempt < 2; attempt++) {
    const reply = await client.callTool(request);
    assert.equal(text(reply).code, 'NATIVE_STATUS_FAILED');
    assert.doesNotMatch(JSON.stringify(reply), /private status key|private doctor key/);
  }
  statusResult = { content: [{ type: 'text', text: '{"ok":false,"message":"private key"}' }] };
  assert.equal(text(await client.callTool(request)).code, 'NATIVE_STATUS_FAILED');
  assert.deepEqual(calls, ['zap_passive_scan_status', 'zap_passive_scan_status', 'zap_passive_scan_status']);
});

test('workflow timeout or error is sanitized and is never retried, replayed or followed by setup', async (t) => {
  let connections = 0;
  const calls = [];
  const { client } = await session(t, {
    controller: { connection: async () => ({ url: 'http://127.0.0.1:7456/mcp', apiKey: 'private' }), doctor: ready, setup: () => assert.fail('No automatic recovery.') },
    connect: async () => {
      connections++;
      return {
        listTools: async () => ({ tools: [tool('zap_passive_scan_status'), tool('zap_crawl_start')] }),
        callTool: async (request) => {
          calls.push(request.name);
          if (request.name === 'zap_passive_scan_status') return nativeStatus();
          throw new Error('timeout Authorization X-API-Key=private-key');
        }, close: async () => {},
      };
    },
  });
  const reply = await client.callTool({ name: 'zap_crawl_start', arguments: { url: 'http://fixture.invalid' } });
  assert.equal(text(reply).code, 'TOOL_CALL_FAILED');
  assert.equal(text(reply).recovery.action, 'inspect_last_call');
  assert.doesNotMatch(JSON.stringify(reply), /Authorization|private-key/);
  assert.deepEqual(calls, ['zap_passive_scan_status', 'zap_crawl_start']);
  assert.equal(connections, 1);
});

test('replacement during native readiness proof blocks the original workflow instead of using a new scanner', async (t) => {
  let identity = 'before-status';
  const calls = [];
  const { client } = await session(t, {
    controller: { connection: async () => ({ url: 'http://127.0.0.1:7456/mcp', apiKey: 'private', identity }), doctor: ready },
    connect: async () => ({
      listTools: async () => ({ tools: [tool('zap_passive_scan_status'), tool('zap_crawl_start')] }),
      callTool: async (request) => { calls.push(request.name); identity = 'after-status'; return nativeStatus(); }, close: async () => {},
    }),
  });
  assert.equal(text(await client.callTool({ name: 'zap_crawl_start', arguments: { url: 'http://fixture.invalid' } })).code, 'SCANNER_CONNECTION_FAILED');
  assert.deepEqual(calls, ['zap_passive_scan_status']);
});

test('error flags override valid-looking status and workflow errors never expose upstream diagnostics', async (t) => {
  let statusError = true;
  const calls = [];
  const { client } = await session(t, {
    controller: { connection: async () => ({ url: 'http://127.0.0.1:7456/mcp', apiKey: 'private' }), doctor: ready },
    connect: async () => ({
      listTools: async () => ({ tools: [tool('zap_passive_scan_status'), { ...tool('zap_new_workflow'), annotations: { readOnlyHint: true } }] }),
      callTool: async (request) => {
        calls.push(request.name);
        if (request.name === 'zap_passive_scan_status') return { ...nativeStatus(), isError: statusError };
        return { isError: true, content: [{ type: 'text', text: 'stderr private-key environment' }] };
      }, close: async () => {},
    }),
  });
  const request = { name: 'zap_new_workflow', arguments: { url: 'http://fixture.invalid' } };
  assert.equal(text(await client.callTool(request)).code, 'NATIVE_STATUS_FAILED');
  assert.deepEqual(calls, ['zap_passive_scan_status']);
  statusError = false;
  const failure = await client.callTool(request);
  assert.equal(text(failure).code, 'TOOL_CALL_FAILED');
  assert.doesNotMatch(JSON.stringify(failure), /stderr|private-key|environment/);
  assert.deepEqual(calls, ['zap_passive_scan_status', 'zap_passive_scan_status', 'zap_new_workflow']);
});

test('read-only polls and report chunks recheck connection but do not run full preflight', async (t) => {
  let checks = 0;
  const calls = [];
  const { client } = await session(t, {
    controller: {
      connection: async () => { checks++; return { url: 'http://127.0.0.1:7456/mcp', apiKey: 'private' }; },
      doctor: () => assert.fail('No expensive doctor for reads.'),
    },
    connect: async () => ({
      listTools: async () => ({ tools: [tool('zap_crawl_status'), tool('zap_report_read_chunk')] }),
      callTool: async (request) => { calls.push(request); return { content: [{ type: 'text', text: 'fixture read' }] }; }, close: async () => {},
    }),
  });
  await client.callTool({ name: 'zap_crawl_status', arguments: { crawlId: 'fixture' } });
  await client.callTool({ name: 'zap_report_read_chunk', arguments: { offset: 0 } });
  assert.equal(checks, 2);
  assert.deepEqual(calls.map((entry) => entry.name), ['zap_crawl_status', 'zap_report_read_chunk']);
});

test('protocol instructions do not turn scan requests into lifecycle permission', () => {
  assert.match(PROTOCOL_INSTRUCTIONS, /A scan request does not approve setup or recovery/);
  assert.match(PROTOCOL_INSTRUCTIONS, /original target, scope and limits/);
  assert.match(PROTOCOL_INSTRUCTIONS, /Never automatically retry or replay/);
});
