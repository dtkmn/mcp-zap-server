import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { createSetupController } from './setup.js';
import { createDesktopServer } from './mcp.js';

// GUI apps may not inherit a shell's PATH. This affects only this extension process.
process.env.PATH = [...new Set(['/opt/homebrew/bin', '/usr/local/bin', resolve(homedir(), '.docker/bin'), '/Applications/Docker.app/Contents/Resources/bin', ...(process.env.PATH || '/usr/bin:/bin:/usr/sbin:/sbin').split(':')])].join(':');
const controller = createSetupController({ bundleRoot: resolve(dirname(fileURLToPath(import.meta.url)), '..') });
const app = createDesktopServer({ controller });
let closing = false;
async function close() {
  if (closing) return;
  closing = true;
  await app.close().catch(() => {});
  process.exit(0);
}
process.on('SIGINT', close);
process.on('SIGTERM', close);
process.stdin.on('end', close);
try {
  await app.server.connect(new StdioServerTransport());
} catch {
  process.stderr.write('MCP ZAP Connect could not start. Check the extension runtime and configuration.\n');
  process.exitCode = 1;
}
