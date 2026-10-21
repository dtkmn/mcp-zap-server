import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import {
  CallToolRequestSchema, ListToolsRequestSchema,
  ListPromptsRequestSchema, GetPromptRequestSchema,
  ToolListChangedNotificationSchema,
} from '@modelcontextprotocol/sdk/types.js';
import { recoveryFailure } from './setup.js';

const EMPTY = { type: 'object', properties: {}, additionalProperties: false };
const READ_ONLY = { readOnlyHint: true, destructiveHint: false, openWorldHint: false };
export const LOCAL_TOOLS = [
  {
    name: 'scanner_check_prerequisites',
    description: 'Read-only local Docker/prerequisite and existing scanner ownership check. Use to diagnose readiness before offering recovery. Does not install software, change Docker context or start containers.',
    inputSchema: EMPTY, annotations: READ_ONLY,
  },
  {
    name: 'scanner_setup',
    description: 'Only after the user explicitly approves the specific local installation or resume action, pass confirm=true. A scan request or failed preflight is not approval. Installs a new scanner, reuses a healthy scanner or resumes a known unchanged installation; never automatically restarts unhealthy/customized installations, rotates keys or changes other clients.',
    inputSchema: { type: 'object', properties: { confirm: { type: 'boolean', const: true, description: 'User explicitly requested and approved local scanner setup.' } }, required: ['confirm'], additionalProperties: false },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true },
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
export const PROTOCOL_INSTRUCTIONS = `MCP ZAP Connect provides local scanner setup tools even when the scanner is offline.
For setup: check prerequisites, explain required changes, and call scanner_setup with confirm=true only after an explicit user setup request and approval. Installing this extension is not a setup request.
Reuse healthy existing installations and preserve credentials, restrictions and all Codex/Cursor settings. Missing Docker or OS permissions require user interaction; do not claim they were installed.
For an existing-scanner status-only request: call zap_passive_scan_status once with {} and stop. Do not call setup, crawl, scan, passive-wait or report tools.
Before starting any crawl, scan, import, automation or other mutating workflow, require current local readiness and a successful harmless native status call. The bridge checks the current Docker context, ownership and credentials again before every forwarded request; an old connection is not readiness evidence.
If readiness fails, stop the requested workflow and explain the returned recovery code and next action. Never launch Docker, change context or startup settings, start/recreate containers, reinstall, rotate keys or repair any client settings without the user explicitly approving that specific action. A scan request does not approve setup or recovery; declining leaves everything unchanged.
After approved recovery, refresh tools and recheck authenticated readiness and native status before the original requested workflow. Preserve the original target, scope and limits; do not invent a target or broaden scope.
Never automatically retry or replay a failed or timed-out workflow call: it may already have started. Inspect read-only status and ask the user before any new attempt.
A doctor result is readiness evidence; a successful native tool call proves this connection. Scanning requires a separate user request with target and scope.`;

// These operations only inspect already-existing work. Unknown tools fail
// closed into the full readiness gate, even if they have no MCP annotations.
const READ_ONLY_TOOLS = new Set([
  'zap_passive_scan_status', 'zap_crawl_status', 'zap_attack_status', 'zap_active_scan_status',
  'zap_spider_status', 'zap_client_spider_status', 'zap_ajax_spider_status', 'zap_ajax_spider_results',
  'zap_scan_job_status', 'zap_scan_job_list', 'zap_scan_job_dead_letter_list',
  'zap_automation_plan_status', 'zap_automation_plan_artifacts',
  'zap_findings_summary', 'zap_findings_details', 'zap_get_findings_summary', 'zap_findings_diff',
  'zap_alerts', 'zap_alert_details', 'zap_alert_instances', 'zap_hosts', 'zap_sites', 'zap_urls',
  'zap_report_read', 'zap_report_read_chunk', 'zap_view_templates',
  'zap_contexts_list', 'zap_users_list', 'zap_scan_policies_list', 'zap_scan_policy_view',
  'zap_scan_history_list', 'zap_scan_history_get',
]);

function nativeStatusSucceeded(reply) {
  if (!reply || reply.isError || reply.structuredContent?.ok === false) return false;
  let status = reply.content?.filter((entry) => entry.type === 'text').map((entry) => entry.text).join('\n') ?? '';
  try {
    // Scanner releases may wrap the native text in one JSON string layer.
    const decoded = JSON.parse(status);
    if (typeof decoded !== 'string') return false;
    status = decoded;
  } catch { /* Plain native status text is also supported. */ }
  return /^Passive scan status:\s*\r?\nCompleted: (yes|no)\s*\r?\nRecords remaining: \d+\s*\r?\nActive tasks: (\d+|unknown)\s*\r?\nScan only in scope: (true|false)(\s|$)/.test(status);
}

const result = (value) => ({ content: [{ type: 'text', text: JSON.stringify(value) }], ...(value.ok === false ? { isError: true } : {}) });
const failure = (code) => result(recoveryFailure(code));
const connectionCode = (error) => [401, 403].includes(error?.status ?? error?.code) ? 'AUTHENTICATION_FAILED' : 'SCANNER_CONNECTION_FAILED';
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
  } catch (error) {
    await client.close().catch(() => {});
    const safe = new Error('Scanner connection failed. Check local readiness and authentication.');
    safe.code = connectionCode(error);
    throw safe;
  }
}

export function createDesktopServer({ controller, connect = connectScanner }) {
  const server = new Server({ name: 'mcp-zap-connect', version: '0.1.0' }, {
    capabilities: { tools: { listChanged: true }, prompts: {} }, instructions: PROTOCOL_INSTRUCTIONS,
  });
  let upstream;
  let remoteTools = [];
  let pending;
  let connectedIdentity;
  async function disconnect() {
    const previous = upstream;
    upstream = undefined;
    remoteTools = [];
    connectedIdentity = undefined;
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
    // Never trust cached Docker ownership, context, health or credentials.
    const connection = await controller.connection();
    const identity = JSON.stringify([connection.url, connection.apiKey, connection.identity]);
    if (upstream && identity !== connectedIdentity) await disconnect();
    if (upstream) {
      if (refreshExisting) await refresh(upstream);
      return upstream;
    }
    if (pending) {
      const candidate = await pending;
      if (connectedIdentity !== identity) return scanner(refreshExisting);
      if (refreshExisting) await refresh(candidate);
      return candidate;
    }
    pending = (async () => {
      await disconnect();
      const candidate = await connect(connection);
      try {
        candidate.onclose = () => {
          if (upstream === candidate) {
            upstream = undefined;
            remoteTools = [];
            connectedIdentity = undefined;
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
        connectedIdentity = identity;
        return candidate;
      } catch {
        await candidate.close().catch(() => {});
        const error = new Error('Scanner tool discovery failed.');
        error.code = 'TOOL_DISCOVERY_FAILED';
        throw error;
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
    let dispatched = false;
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
        if (!args || typeof args !== 'object' || Array.isArray(args) || args.confirm !== true || Object.keys(args).some((key) => key !== 'confirm')) return failure('CONFIRMATION_REQUIRED');
        const outcome = await withProgress(request, extra, 'Local scanner setup is in progress; existing data is retained.', () => controller.setup({ confirm: true }));
        if (outcome.ok) {
          await disconnect();
          try { await scanner(); } catch { /* Readiness remains explicit if reconnect fails. */ }
          await server.sendToolListChanged().catch(() => {});
        }
        return result(outcome);
      }
      if (name === STATUS.name) requireEmpty(args);
      let client = await scanner();
      if (!remoteTools.some((tool) => tool.name === name)) return failure('UNKNOWN_TOOL');
      if (!READ_ONLY_TOOLS.has(name)) {
        const readiness = await withProgress(request, extra, 'Checking current authenticated readiness before the requested workflow.', () => controller.doctor());
        if (!readiness?.ok || readiness.readinessVerified !== true) {
          await disconnect();
          return failure(readiness?.code ?? 'DOCTOR_FAILED');
        }
        // Re-read identity after the doctor, then refresh discovery. Setup or a
        // user change may have replaced the container/credential in between.
        const authenticatedClient = await scanner(true);
        if (authenticatedClient !== client) return failure('SCANNER_CONNECTION_FAILED');
        if (!remoteTools.some((tool) => tool.name === STATUS.name)) return failure('NATIVE_STATUS_FAILED');
        let status;
        try { status = await client.callTool({ name: STATUS.name, arguments: {} }, undefined, { timeout: 60000 }); }
        catch { await disconnect(); return failure('NATIVE_STATUS_FAILED'); }
        if (!nativeStatusSucceeded(status)) { await disconnect(); return failure('NATIVE_STATUS_FAILED'); }
        // If the user changed the local installation during the status call,
        // stop rather than sending a workflow to a different scanner.
        const verifiedClient = await scanner();
        if (verifiedClient !== client) return failure('SCANNER_CONNECTION_FAILED');
        if (!remoteTools.some((tool) => tool.name === name)) return failure('UNKNOWN_TOOL');
      }
      // Never replay a tool call after a timeout/disconnection: it may have side effects.
      dispatched = true;
      const reply = await client.callTool({ name, arguments: args }, undefined, { timeout: 60000 });
      if (reply.isError) { await disconnect(); return failure(name === STATUS.name ? 'NATIVE_STATUS_FAILED' : 'TOOL_CALL_FAILED'); }
      return reply;
    } catch (error) {
      await disconnect();
      const code = dispatched ? 'TOOL_CALL_FAILED' : error?.message === 'Expected empty arguments.' ? 'INVALID_ARGUMENTS' : error?.code;
      return failure(code ?? 'SCANNER_CONNECTION_FAILED');
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
