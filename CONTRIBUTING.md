# Contributing to MCP ZAP Server

Thanks for contributing. This project is useful only if it stays trustworthy, reproducible, and safe to run against real targets.

## Before You Start

- Use [GitHub Discussions](https://github.com/dtkmn/mcp-zap-server/discussions) for questions, usage help, and early ideas.
- Use GitHub Issues for actionable bugs and scoped feature proposals.
- Do not open public issues for security problems. Use GitHub private vulnerability reporting or follow [SECURITY.md](./SECURITY.md).

## Good Contributions

Strong contributions usually do at least one of these well:

- fix a real bug with a minimal reproduction
- improve operator safety or security defaults
- tighten documentation for actual adopter pain points
- add tests for behavior that can regress
- simplify a rough edge without expanding the maintenance burden

Weak contributions usually look like:

- drive-by refactors with no user-facing value
- broad rewrites that mix style changes with behavior changes
- speculative features without a clear operating model
- automation added before the current workflow is stable

## Development Setup

### Application

```bash
./gradlew test
./gradlew build
```

### Local stack

```bash
cp .env.example .env
docker compose up -d
```

See [README.md](./README.md) and [QUICK_START_SECURITY.md](./QUICK_START_SECURITY.md) for local setup and auth details.

### Docs site

```bash
cd docs
npm ci
npm run check
npm run build
```

## Project Layout

- `src/main/java/` - Spring Boot runtime and MCP/ZAP integration
- `src/test/java/` - unit, integration, and architecture tests
- `src/main/resources/migration/` - Flyway migrations
- `docs/` - public documentation site
- `helm/` - Helm chart and deployment values
- `.github/` - CI, release, docs, and repository automation

## Contribution Workflow

1. Start from the latest `main`.
2. Keep changes focused. One pull request should solve one problem.
3. Add or update tests when behavior changes.
4. Update docs when the user-facing contract changes.
5. Explain the operational impact in the PR description.

## Pull Request Expectations

Every pull request should include:

- a clear problem statement
- a short summary of the chosen approach
- test evidence
- docs updates when configuration, behavior, or public workflows changed

If you changed scan behavior, auth, queueing, rate limiting, or deployment defaults, call that out explicitly. Those are not cosmetic changes.

## Testing Expectations

Run the smallest meaningful set locally before opening a PR:

- `./gradlew test` for Java changes
- `./gradlew build` for dependency or packaging changes
- `cd docs && npm run check && npm run build` for docs-site changes

If you cannot run something locally, say so in the PR and explain why.

## Documentation Standards

Public docs should help external users adopt and operate the project safely.

- prefer concrete examples over abstract descriptions
- document defaults, limits, and failure modes
- avoid stale screenshots unless they add real value
- keep private rollout notes out of the public docs tree

## Security Contributions

This repository wraps a security testing tool. That means sloppy changes can create real-world abuse or trust problems.

- default-safe behavior matters
- auth and authorization changes need extra scrutiny
- URL validation changes need explicit justification
- never weaken guardrails without documenting the tradeoff

For vulnerabilities, use GitHub private vulnerability reporting or email as described in [SECURITY.md](./SECURITY.md).

## Review and Merge

Maintainers may ask you to split oversized PRs, add tests, or tighten docs before merge. That is not bureaucracy. It is how we keep the repo usable.

By contributing, you agree to follow the [Code of Conduct](./CODE_OF_CONDUCT.md).
