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

## Notes

- The old Jekyll site was replaced by this Astro app.
- Legacy public URLs such as `SECURITY_MODES.html` are redirected to the new route structure.
- GitHub Pages builds this directory via [`.github/workflows/pages.yml`](../.github/workflows/pages.yml).
- Pushes to `dev` validate and build the docs without publishing them. Publishing runs only for pushes or manual workflow runs on `main` or `master`; a green `dev` build does not mean the public site has changed.
