# MCP ZAP Server Documentation

This directory contains the Astro + Starlight source for the public documentation site at <https://danieltse.org/mcp-zap-server/>.

## Public Docs Policy

Only commit documentation here if it helps external users or operators adopt the project.

Good fits for `/docs`:

- installation and quick-start guides
- authentication and client configuration
- scanning workflows and usage examples
- deployment and operator guidance that is safe to share publicly

Do not keep these in the public docs tree:

- sprint plans or milestone notes
- SLO baselines, internal dashboards, or alert contracts
- technical debt registers and backlog rankings
- implementation summaries written for a single task or private rollout

Keep private reviews and advisor notes outside this repository. All tracked
source, including drafts, metadata, and earlier commits, is publicly readable.
Removing a page from navigation or adding an ignore rule does not remove
content already committed to Git history.

## Project Layout

- `src/content/docs/` - public documentation content rendered by Starlight
- `scripts/sync-docs.mjs` - Astro integration generating shared pages from canonical repository Markdown
- `src/styles/terminal-ops.css` - terminal/operator visual theme customization
- `src/pages/[legacy].html.astro` - compatibility redirects for old Jekyll `.html` URLs
- `public/demo.html` - static demo video page

## Local Preview

```bash
cd docs
npm install
npm run dev
```

Useful checks:

```bash
npm run check
npm run build
```

The Pages workflow uses the pinned `audit-ci` development dependency to block
unaccepted findings of moderate severity or higher. Run the same check from
`docs/` with:

```bash
./node_modules/.bin/audit-ci --config audit-ci.jsonc
```

Temporary advisory exceptions are recorded in [`audit-ci.jsonc`](audit-ci.jsonc)
with a reason, owner, and UTC expiry. They apply to the named advisory across
its affected versions and paths. An exception accepts risk; it does not fix the
dependency. Review it when dependency versions or documentation behavior change.
The complete npm audit report stays visible in the workflow log. Confirm the
log contains an actual npm audit report: missing or empty output does not
establish that dependencies were checked successfully.

The public [dependency security status](src/content/docs/reference/dependency-security.md)
records known upstream findings and their deployment scope, owner and review
date. Keep it consistent with the actual scan reports and advisory status.

## Maintaining Shared Pages

The following pages have one maintained repository source. Their website
versions are generated into the ignored `src/content/docs/generated/`
directory; do not maintain a second copy of their bodies in the website tree.

| Page | Canonical source | Website route |
| --- | --- | --- |
| Self-Serve First Run | [SELF_SERVE_FIRST_RUN.md](getting-started/SELF_SERVE_FIRST_RUN.md) | `/getting-started/self-serve-first-run/` |
| AWS EC2 with Docker Compose | [AWS_EC2_COMPOSE_GUIDE.md](operator/runbooks/AWS_EC2_COMPOSE_GUIDE.md) | `/operations/aws-ec2-compose/` |
| AWS EKS Infrastructure Starter | [examples/aws-eks/README.md](../examples/aws-eks/README.md) | `/operations/aws-eks-infrastructure/` |
| Production Simulation Runbook | [PRODUCTION_SIMULATION_RUNBOOK.md](operator/runbooks/PRODUCTION_SIMULATION_RUNBOOK.md) | `/operations/production-simulation-runbook/` |
| Seeded API Gate Playbook | [SEEDED_API_GATE_PLAYBOOK.md](scanning/SEEDED_API_GATE_PLAYBOOK.md) | `/scanning/seeded-api-gate-playbook/` |
| Extension API Release Policy | [EXTENSION_API_RELEASE_POLICY.md](extensions/EXTENSION_API_RELEASE_POLICY.md) | `/extensions/extension-api-release-policy/` |
| Security Policy | [SECURITY.md](../SECURITY.md) | `/reference/security-policy/` |

The shared-docs integration in `astro.config.mjs` generates these pages before
Astro reads content during development, checks, and builds. The page titles and
existing routes are configured in `scripts/sync-docs.mjs`. It also maps the
first-run guide's repository-relative Cursor example link to its GitHub source
URL.

The development server watches the canonical sources, including root
`SECURITY.md`, and regenerates their website pages when they change. Keep
existing section headings or retain an explicit anchor when renaming them so
inbound links continue to work. Keep the root `SECURITY.md` as the security
policy GitHub reads. `npm run preview` serves the last build.

## Notes

- The old Jekyll site was replaced by this Astro app.
- Legacy public URLs such as `SECURITY_MODES.html` are redirected to the new route structure.
- GitHub Pages builds this directory via [`.github/workflows/pages.yml`](../.github/workflows/pages.yml).
- Pushes to `dev` validate and build the docs without publishing them. Publishing runs only for pushes or manual workflow runs on `main` or `master`; a green `dev` build does not mean the public site has changed.
