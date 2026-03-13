# MCP ZAP Server Documentation

This directory contains the source files for the public [GitHub Pages documentation site](https://dtkmn.github.io/mcp-zap-server/).

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

## Public Documentation Pages

### Getting Started
- `index.md` - Docs landing page
- `MCP_CLIENT_AUTHENTICATION.md` - Open WebUI, Cursor, Claude Desktop, and other MCP clients
- `AUTHENTICATION_QUICK_START.md` - Quick auth setup
- `SECURITY_MODES.md` - Overview of the supported security modes
- `SECURITY_MODE_EXAMPLES.md` - Configuration examples

### Scanning Workflows
- `AJAX_SPIDER.md` - Browser-based crawling for client-rendered apps
- `AUTHENTICATED_SCANNING_BEST_PRACTICES.md` - Context, auth, and user-driven scan flows

### Deployment & Operations
- `JWT_AUTHENTICATION.md` - JWT setup guide
- `JWT_KEY_ROTATION_RUNBOOK.md` - JWT key rotation procedure
- `QUICK_START_JWT.md` - Fast JWT setup
- `PRODUCTION_CHECKLIST.md` - Public production-readiness baseline
- `LOCAL_HA_COMPOSE.md` - Local multi-replica Compose simulation
- `QUEUE_COORDINATOR_LEADER_ELECTION.md` - Multi-replica leader election configuration
- `SCAN_QUEUE_RETRY_POLICY.md` - Queue retry behavior and operator notes
- `NATIVE_IMAGE_PERFORMANCE.md` - JVM vs native-image tradeoffs
- `demo.html` - Demo video page

## Local Preview

```bash
cd docs
bundle install
bundle exec jekyll serve
```

Then open `http://localhost:4000/security-gateway/`.

## Adding or Updating Pages

1. Create or edit a page under `/docs`.
2. Add frontmatter if the page should be published:
   ```yaml
   ---
   layout: default
   title: Page Title
   ---
   ```
3. Link it from `index.md` or another public page if users should discover it easily.
4. Keep the page user-facing; move internal notes elsewhere before committing.

## Notes

- The site uses the `just-the-docs` theme configured in [`_config.yml`](./_config.yml).
- `demo.html` is published as a static page.
- `_site/` is build output and should not be treated as source.
