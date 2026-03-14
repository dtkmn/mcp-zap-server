# Release Notes - Version 0.5.0

**Release Date:** March 14, 2026

## Highlights

- Added persistent scan job queueing and storage with in-memory and Postgres backends.
- Added queue leadership coordination for single-node and Postgres-backed multi-replica deployments.
- Added startup security validation and pluggable JWT token revocation storage.
- Migrated the public docs site from Jekyll to Astro + Starlight with legacy URL redirects.

## Security and Operational Readiness

- Added `SecurityStartupValidator` to fail fast on insecure placeholder API key configurations in stricter environments.
- Added `TokenRevocationStore` backends and integrated revocation persistence into JWT lifecycle handling.
- Added `PostgresSchemaReadinessValidator` to verify shared-state schema availability before runtime usage.
- Added Flyway migrations for shared Postgres state, including `scan_jobs` and `jwt_token_revocation` tables.

## Scan Orchestration and Storage

- Added `ScanJob`, `ScanJobQueueService`, and `ScanJobStore` implementations for in-memory and Postgres storage.
- Added queue leadership coordination using Postgres advisory locks or single-node mode.
- Expanded scan queue retry and backoff controls along with scheduling support for queue dispatch.
- Added `ContextUserService` to better manage ZAP contexts, users, and authenticated scan setup.

## Platform and Dependency Updates

- Gradle wrapper: `9.4.0`
- Docker build image: `gradle:9.4.0-jdk25`
- CycloneDX plugin: `3.2.2`
- Added Flyway and PostgreSQL runtime dependencies for shared-state support

## Documentation and Developer Experience

- Expanded public docs with authenticated scanning best practices, production readiness, HA Compose simulation, queue coordination, retry policy, and JWT rotation guidance.
- Replaced the Jekyll docs site with Astro + Starlight while preserving legacy `.html` routes for existing links and bookmarks.
- Updated README, changelog, and Pages workflow for the new docs platform.

## Validation

- `./gradlew test`
- `cd docs && npm run check`
- `cd docs && npm run build`

## Full Changelog

- [`CHANGELOG.md`](./CHANGELOG.md)
