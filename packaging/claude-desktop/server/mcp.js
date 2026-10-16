import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import {
  CallToolRequestSchema, ListToolsRequestSchema,
  ListPromptsRequestSchema, GetPromptRequestSchema,
  ToolListChangedNotificationSchema,
} from '@modelcontextprotocol/sdk/types.js';

const EMPTY = { type: 'object', properties: {}, additionalProperties: false };
const READ_ONLY = { readOnlyHint: true, destructiveHint: false, openWorldHint: false };
export const LOCAL_TOOLS = [
  {
    name: 'scanner_check_prerequisites',
    description: 'Read-only local Docker/prerequisite and existing scanner ownership check. Call before setup. Does not install software or start containers.',
    inputSchema: EMPTY, annotations: READ_ONLY,
  },
  {
    name: 'scanner_setup',
    description: 'Only after the user explicitly requests scanner setup and approves changes, pass confirm=true. Reuses a healthy existing scanner; otherwise runs the shared release bootstrap in a private directory after prerequisite/ownership checks. Never scans, rotates keys or changes other clients.',
    inputSchema: { type: 'object', properties: { confirm: { type: 'boolean', const: true, description: 'User explicitly requested and approved local scanner setup.' } }, required: ['confirm'], additionalProperties: false },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: true },
  },
  {
    name: 'scanner_check_readiness',
    description: 'Run the shared authenticated doctor with --skip-tool-call. Does not start containers or invoke scan/status tools. Report failures before continuing.',
    inputSchema: EMPTY, annotations: READ_ONLY,
  },
];
const STATUS = {
  name: 'zap_passive_scan_status',
  description: 'Read the existing scanner passive-analysis status. Does not start a scan. For connection verification call this once with empty arguments, then stop.',
  inputSchema: EMPTY, annotations: READ_ONLY,
};
const INSTRUCTIONS = `MCP ZAP Connect provides local scanner setup tools even when the scanner is offline.
For setup: check prerequisites, explain required changes, and call scanner_setup with confirm=true only after an explicit user setup request and approval. Installing this extension is not a setup request.
Reuse healthy existing installations and preserve credentials, restrictions and all Codex/Cursor settings. Missing Docker or OS permissions require user interaction; do not claim they were installed.
For an existing-scanner status-only request: call zap_passive_scan_status once with {} and stop. Do not call setup, crawl, scan, passive-wait or report tools.
A doctor result is readiness evidence; a successful native tool call proves this connection. Scanning requires a separate user request with target and scope.`;

const result = (value) => ({ content: [{ type: 'text', text: JSON.stringify(value) }], ...(value.ok === false ? { isError: true } : {}) });
const failure = (message) => result({ ok: false, message });
function requireEmpty(args) {
  if (!args || typeof args !== 'object' || Array.isArray(args) || Object.keys(args).length) throw new Error('Expected empty arguments.');
}

export async function connectScanner({ url, apiKey }) {
  const client = new Client({ name: 'mcp-zap-connect-desktop', version: '0.1.0' });
  // Credentials remain inside this process, never in argv or diagnostic output.
  const transport = new StreamableHTTPClientTransport(new URL(url), {
    requestInit: { headers: { 'X-API-Key': apiKey } },
  });
  try {
    await client.connect(transport, { timeout: 15000 });
    return client;
  } catch {
    await client.close().catch(() => {});
    throw new Error('Scanner connection failed. Check local readiness and authentication.');
  }
}

export function createDesktopServer({ controller, connect = connectScanner }) {
  const server = new Server({ name: 'mcp-zap-connect', version: '0.1.0' }, {
    capabilities: { tools: { listChanged: true }, prompts: {} }, instructions: INSTRUCTIONS,
  });
  let upstream;
  let remoteTools = [];
  let pending;
  async function disconnect() {
    const previous = upstream;
    upstream = undefined;
    remoteTools = [];
    if (previous) await previous.close().catch(() => {});
  }
  async function refresh(candidate) {
    const tools = [];
    const seen = new Set();
    let cursor;
    do {
      const page = await candidate.listTools(cursor ? { cursor } : {}, { timeout: 10000 });
      tools.push(...page.tools);
      cursor = page.nextCursor;
      if (cursor && seen.has(cursor)) throw new Error('Repeated tool-list cursor.');
      if (cursor) seen.add(cursor);
    } while (cursor);
    remoteTools = tools.filter((tool) => !LOCAL_TOOLS.some((local) => local.name === tool.name));
  }
  async function scanner(refreshExisting = false) {
    if (upstream) {
      if (refreshExisting) await refresh(upstream);
      return upstream;
    }
    if (pending) return pending;
    pending = (async () => {
      const connection = await controller.connection();
      await disconnect();
      const candidate = await connect(connection);
      try {
        candidate.onclose = () => {
          if (upstream === candidate) {
            upstream = undefined;
            remoteTools = [];
            void server.sendToolListChanged().catch(() => {});
          }
        };
        candidate.setNotificationHandler?.(ToolListChangedNotificationSchema, async () => {
          if (upstream !== candidate) return;
          try {
            await refresh(candidate);
            await server.sendToolListChanged();
          } catch {
            if (upstream === candidate) await disconnect();
          }
        });
        await refresh(candidate);
        upstream = candidate;
        return candidate;
      } catch {
        await candidate.close().catch(() => {});
        throw new Error('Scanner tool discovery failed.');
      }
    })();
    try { return await pending; } finally { pending = undefined; }
  }
  server.setRequestHandler(ListToolsRequestSchema, async () => {
    try { await scanner(true); } catch { await disconnect(); /* Local setup tools remain available offline. */ }
    return { tools: [...LOCAL_TOOLS, ...remoteTools, ...(!remoteTools.some((t) => t.name === STATUS.name) ? [STATUS] : [])] };
  });
  async function withProgress(request, extra, message, operation) {
    const progressToken = request.params._meta?.progressToken;
    let progress = 0;
    const notify = () => {
      if (progressToken !== undefined) void extra.sendNotification({
        method: 'notifications/progress', params: { progressToken, progress: progress++, message },
      }).catch(() => {});
    };
    notify();
    const timer = progressToken === undefined ? undefined : setInterval(notify, 5000);
    try { return await operation(); } finally { clearInterval(timer); }
  }
  server.setRequestHandler(CallToolRequestSchema, async (request, extra) => {
    const { name, arguments: args = {} } = request.params;
    try {
      if (name === 'scanner_check_prerequisites') {
        requireEmpty(args);
        return result(await withProgress(request, extra, 'Checking local Docker requirements and existing scanner ownership.', () => controller.prerequisites()));
      }
      if (name === 'scanner_check_readiness') {
        requireEmpty(args);
        return result(await withProgress(request, extra, 'Checking authenticated local scanner readiness.', () => controller.doctor()));
      }
      if (name === 'scanner_setup') {
        if (args.confirm !== true || Object.keys(args).some((key) => key !== 'confirm')) return failure('Setup requires only confirm=true after an explicit user setup request and approval.');
        const outcome = await withProgress(request, extra, 'Local scanner setup is in progress; existing data is retained.', () => controller.setup({ confirm: true }));
        if (outcome.ok) {
          await disconnect();
          try { await scanner(); } catch { /* Readiness remains explicit if reconnect fails. */ }
          await server.sendToolListChanged().catch(() => {});
        }
        return result(outcome);
      }
      if (name === STATUS.name) requireEmpty(args);
      const client = await scanner();
      if (!remoteTools.some((tool) => tool.name === name)) return failure('Unknown scanner tool. Check prerequisites and readiness.');
      // Never replay a tool call after a timeout/disconnection: it may have side effects.
      return await client.callTool({ name, arguments: args }, undefined, { timeout: 60000 });
    } catch {
      await disconnect();
      return failure('Operation failed. Check prerequisites/readiness and the selected installation. No automatic retry or scanner restart was performed.');
    }
  });
  server.setRequestHandler(ListPromptsRequestSchema, async () => ({ prompts: [{ name: 'setup-local-scanner', description: 'Guide local scanner setup while preserving existing installations and other clients.' }] }));
  server.setRequestHandler(GetPromptRequestSchema, async (request) => {
    if (request.params.name !== 'setup-local-scanner') throw new Error('Unknown prompt.');
    return { description: 'Local scanner setup', messages: [{ role: 'user', content: { type: 'text', text: 'Set up or reconnect my local MCP ZAP scanner. Check prerequisites first, reuse an existing healthy installation and preserve all other client settings. Explain any required changes before requesting approval. Verify with zap_passive_scan_status once only; do not run a scan.' } }] };
  });
  server.onerror = () => { /* Do not log transport exceptions containing private headers. */ };
  return { server, close: async () => { await disconnect(); await server.close(); } };
}
