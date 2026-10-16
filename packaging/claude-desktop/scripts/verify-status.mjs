import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const bundle = resolve(process.argv[2] || resolve(root, '../../build/claude-desktop/unpacked'));
const client = new Client({ name: 'mcp-zap-connect-status-verifier', version: '0.1.0' });
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [resolve(bundle, 'server/index.js')],
  env: Object.fromEntries(Object.entries(process.env).filter(([key]) => ['HOME', 'PATH', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG', 'MCP_ZAP_INSTALL_DIR', 'MCP_ZAP_API_KEY', 'MCP_API_KEY'].includes(key))),
  stderr: 'pipe',
});
// Do not echo private child/transport diagnostics into verification reports.
transport.stderr?.resume();
try {
  await client.connect(transport, { timeout: 20000 });
  const listed = await client.listTools({}, { timeout: 20000 });
  const names = listed.tools.map((tool) => tool.name);
  for (const required of ['scanner_check_prerequisites', 'scanner_setup', 'scanner_check_readiness', 'zap_passive_scan_status']) {
    if (!names.includes(required)) throw new Error('A required packaged tool is missing.');
  }
  console.log('Packaged stdio initialization and setup/status discovery: PASS');
  const reply = await client.callTool({ name: 'zap_passive_scan_status', arguments: {} }, undefined, { timeout: 30000 });
  if (reply.isError) throw new Error('Status returned an error.');
  // The result contains status data only. No setup/lifecycle/scan/report tool is invoked.
  console.log(JSON.stringify({ tool: 'zap_passive_scan_status', result: reply }, null, 2));
  console.log('Packaged native-protocol status verification: PASS (not Claude Desktop UI/account verification)');
} catch {
  console.error('Packaged status verification failed; no other tool or lifecycle action was attempted.');
  process.exitCode = 1;
} finally {
  await client.close().catch(() => {});
}
