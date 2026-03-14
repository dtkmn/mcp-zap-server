# MCP ZAP Server Documentation

This directory contains the Astro + Starlight source for the public [GitHub Pages documentation site](https://dtkmn.github.io/mcp-zap-server/).

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

## Project Layout

- `src/content/docs/` - public documentation content rendered by Starlight
- `src/styles/mr-robot.css` - theme customization for the fsociety/Mr. Robot look
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
