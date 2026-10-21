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
./bin/bootstrap-local.sh
./dev.sh
```

The bootstrap creates or preserves private local settings. `dev.sh` builds the
checkout with `docker-compose.dev.yml`, waits for health, and runs the existing
authenticated doctor before reporting ready. For the published-image install
and client connection workflow, use [Self-Serve First Run](./docs/getting-started/SELF_SERVE_FIRST_RUN.md).

For the shared governance wiring, use the
[Core integration reference](https://danieltse.org/mcp-gateway-core/reference/zap-integration/).
It maps the published libraries to application code and exercises allowed and
denied calls through initialized MCP sessions with synthetic test credentials.

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

## Contribution License

Unless explicitly stated otherwise, any contribution intentionally submitted
for inclusion in this repository is submitted under the Apache License 2.0,
without additional terms or conditions.

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
- `./gradlew dockerTest` for Docker/Testcontainers changes
- `cd docs && npm run check && npm run build` for docs-site changes

CI validates pull requests targeting `main` or `dev`, pushes to `main`, and
manual workflow runs. Snyk uses the same branch events for trusted contributions,
plus weekly and manual runs. Docs validation runs for pull requests targeting
`main` or `master`; Pages publishing runs for pushes or manual runs on those
branches. Run relevant checks locally before pushing development or feature
branches, which do not trigger CI, Snyk, or Pages on push. Updates to an open
qualifying pull request still run its pull-request checks.

The normal build excludes Docker-tagged tests. CI runs them for pull requests targeting `main`, pushes to `main` before image publication, manual workflow runs, and releases. Development and feature pushes skip them.

### Java test coverage

Generate coverage from the existing non-Docker tests with:

```bash
./gradlew clean test jacocoTestReport
```

Open `build/reports/jacoco/test/html/index.html` for the local report. SonarQube
imports `build/reports/jacoco/test/jacocoTestReport.xml`. The report measures
production Java code; it does not measure the Python helper suite, browser
crawl completeness or ZAP vulnerability detection. No Gradle coverage minimum
is added. Sonar's existing quality gate still applies; importing coverage can
activate its existing condition for coverage on new code. Use uncovered lines
and branches to identify meaningful test gaps.
Sonar retains the existing resource and extension-example analysis scope. The
local JaCoCo report covers the application's main source set; Sonar's line
coverage can differ because it also includes the example Java files. Sonar's
overall coverage metric combines line and branch coverage.

When Docker integration testing is appropriate, use
`./gradlew clean test dockerTest jacocoTestReport` to include both suites. The
report task itself does not start Docker tests. Use a clean build when comparing
coverage baselines so earlier Docker execution data cannot inflate the result.

CI runs a clean build, any required Docker tests, coverage generation, and
eligible Sonar analysis in one Gradle invocation so the unit suite runs once.
Build scans are disabled for this invocation. CI attempts to upload available
test reports even when a build or analysis fails.
Sonar analysis runs only for trusted events with a `SONAR_TOKEN` repository
secret; fork and Dependabot pull requests still generate local coverage artifacts.
Missing credentials produce an explicit notice rather than a false claim that
Sonar coverage was published.

For maintainers enabling CI analysis, create a dedicated Sonar analysis token
and store it as the GitHub Actions repository secret `SONAR_TOKEN`. Use an
analysis-scoped organization token if the account plan supports it. Before the
first authenticated CI run, turn off **Automatic Analysis** under the Sonar
project's **Administration > Analysis method**; Automatic Analysis cannot import
coverage and conflicts with CI analysis. Coordinate this switch with delivery
of the workflow so the project retains a working analysis path. Confirm the
completed Sonar background task and displayed coverage before closing the
tracking issue. For an authorized local upload, supply `SONAR_TOKEN` privately
in the environment and select the actual branch explicitly, for example
`./gradlew sonar -Dsonar.branch.name=dev` for a `dev` checkout. Do not upload
development code as the `main` baseline or put the token in commands, tracked
files or build scans.

If you cannot run something locally, say so in the PR and explain why.

### Snyk dependency checks

Snyk checks source dependencies, the built container and rendered Helm manifests
separately. Source checks retain the root Gradle build's compile, runtime, test
and sample-extension dependency scopes. The independent extension example needs
its own compile-classpath check after the local API publication is staged. The
docs and Claude Desktop npm lockfiles and Go healthcheck are separate targets.
When adding a manifest, update the configured scan targets and coverage checks.

A successful scan job does not mean the project has no known vulnerabilities.
[Snyk's test exit codes](https://docs.snyk.io/developer-tools/snyk-cli/commands/test)
distinguish findings from execution failures. Finding-related exits preserve the
reports and produce a warning. Execution, dependency-extraction,
report-validation, upload and monitoring failures must fail the job. Inspect the
uploaded Snyk diagnostics to distinguish findings from
missing coverage before deciding what to fix.

Container application coverage is checked against the executable JAR extracted
from the image. Operating-system results, a dependency count or the embedded
SBOM alone cannot establish that every bundled runtime library was identified.
Treat unmatched libraries and unidentified external dependencies as coverage
gaps; investigate their reported names and versions rather than suppressing the
check.

Result categories identify configured projects and container layers consistently
across branches using [GitHub's SARIF categories](https://docs.github.com/en/code-security/reference/code-scanning/sarif-files/sarif-support).
A category migration needs a matching main-branch baseline before pull-request
comparison can be established. Historical configurations are
not automatically deleted: verify replacement findings and coverage first, then
handle any specifically approved retirement separately.

## Documentation Standards

Public docs should help external users adopt and operate the project safely.

- prefer concrete examples over abstract descriptions
- document defaults, limits, and failure modes
- avoid stale screenshots unless they add real value
- keep internal testing progress, account history, validation logs and rollout
  notes out of public docs, metadata and bundled guides

All tracked files, drafts, frontmatter, screenshots, and Git history are public.
Keep private reviews, advisor notes, and rollout assessments outside this
repository. Turn what validation teaches you into user-facing instructions and
troubleshooting; keep the underlying run records private. A draft flag or a
hidden navigation entry does not make source content private. Use factual,
dated notices for archived documentation and verify copyable commands against
the current configuration before publishing.

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
