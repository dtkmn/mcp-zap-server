import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const generatedRoot = join(repoRoot, 'docs/src/content/docs/generated');

const pages = [
  {
    source: 'docs/getting-started/SELF_SERVE_FIRST_RUN.md',
    slug: 'getting-started/self-serve-first-run',
    title: 'Self-Serve First Run',
    description: 'Start the local server, connect your MCP client, and generate your first scan report.',
    links: {
      '../../examples/cursor/mcp.json': 'https://github.com/dtkmn/mcp-zap-server/blob/main/examples/cursor/mcp.json',
    },
  },
  {
    source: 'docs/operator/runbooks/PRODUCTION_SIMULATION_RUNBOOK.md',
    slug: 'operations/production-simulation-runbook',
    title: 'Production Simulation Runbook',
    description: 'Run a staged proof before recommending a deployment as production-ready.',
  },
  {
    source: 'docs/scanning/SEEDED_API_GATE_PLAYBOOK.md',
    slug: 'scanning/seeded-api-gate-playbook',
    title: 'Seeded API Gate Playbook',
    description: 'Adopt seeded API traffic, reviewed baselines, and enforcement without turning CI security into noise.',
  },
  {
    source: 'docs/extensions/EXTENSION_API_RELEASE_POLICY.md',
    slug: 'extensions/extension-api-release-policy',
    title: 'Extension API Release Policy',
    description: 'Publication stages, versioning rules, and compatibility gates for mcp-zap-extension-api.',
  },
  {
    source: 'SECURITY.md',
    slug: 'reference/security-policy',
    title: 'Security Policy',
    description: 'Responsible vulnerability reporting, supported versions, security controls, and operator responsibilities.',
  },
];

/** @returns {import('astro').AstroIntegration} */
export default function sharedDocs() {
  const sources = pages.map((page) => join(repoRoot, page.source));
  return {
    name: 'mcp-zap-shared-docs',
    hooks: {
      'astro:config:setup': async ({ command, addWatchFile, logger }) => {
        if (command === 'preview') return;
        for (const source of sources) addWatchFile(source);
        await generatePages();
        logger.info(`Generated ${pages.length} website pages from canonical repository docs.`);
      },
      'astro:server:setup': ({ server }) => {
        server.watcher.add(sources);
      },
    },
  };
}

async function generatePages() {
  const generatedPages = await Promise.all(pages.map(async (page) => {
    const source = await readFile(join(repoRoot, page.source), 'utf8');
    let body = source.replace(/^# .+\r?\n(?:\r?\n)*/, '').trimEnd();
    for (const [from, to] of Object.entries(page.links ?? {})) {
      body = body.replaceAll(`](${from})`, `](${to})`);
    }
    return {
      path: join(generatedRoot, `${page.slug}.md`),
      content: [
        '---',
        `title: ${JSON.stringify(page.title)}`,
        `description: ${JSON.stringify(page.description)}`,
        `slug: ${JSON.stringify(page.slug)}`,
        'editUrl: false',
        '---',
        '',
        `<!-- Generated from ${page.source}. Edit the canonical source, not this file. -->`,
        '',
        body,
        '',
      ].join('\n'),
    };
  }));

  await rm(generatedRoot, { recursive: true, force: true });
  for (const page of generatedPages) {
    await mkdir(dirname(page.path), { recursive: true });
    await writeFile(page.path, page.content);
  }
}
